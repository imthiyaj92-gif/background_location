package com.almoullim.background_location

import android.Manifest
import android.app.Application
import java.lang.reflect.Proxy
import io.flutter.plugin.common.BinaryMessenger
import android.content.ComponentName
import android.content.ContextWrapper
import android.content.Context
import android.content.Intent
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class TripStateStoreTest {
    private lateinit var context: Context
    private lateinit var store: TripStateStore
    private fun config(id: String = "trip-1") = mapOf<String, Any>(
        "tripId" to id,
        "destinationName" to "Dubai Mall",
        "destinationLatitude" to 25.197197123456,
        "destinationLongitude" to 55.279376987654,
        "radiusMeters" to 2000.0,
    )

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(TripStateStore.PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit()
        store = TripStateStore(context)
    }

    @Test fun persistsExactCoordinatesRadiusNameAndStateAcrossStoreInstances() {
        assertNull(store.get())
        val expected = store.start(config())
        val restored = TripStateStore(context).get()!!
        assertEquals(expected, restored)
        assertEquals(2000.0, restored["radiusMeters"])
        assertEquals("Dubai Mall", restored["destinationName"])
        assertEquals("TRACKING", restored["phase"])
        assertNull(restored["lastDistanceMeters"])
        assertTrue(context.getSharedPreferences(TripStateStore.PREFERENCES, Context.MODE_PRIVATE).contains("trip"))
    }

    @Test fun repeatedStartIsIdempotentAndCannotChangeSettings() {
        val first = store.start(config())
        assertEquals(first, store.start(config()))
        expectError("TRIP_ID_CONFLICT") { store.start(config() + ("radiusMeters" to 500.0)) }
        expectError("ACTIVE_TRIP_EXISTS") { store.start(config("trip-2")) }
        assertEquals(first, store.get())
    }

    @Test fun rejectsInvalidCoordinatesRadiusAndIdentityWithoutWriting() {
        for (bad in listOf(
            config() + ("radiusMeters" to Double.NaN),
            config() + ("radiusMeters" to 0),
            config() + ("destinationLatitude" to 91.0),
            config() + ("destinationLongitude" to Double.POSITIVE_INFINITY),
            config() + ("tripId" to " "),
            config() + ("destinationName" to ""),
        )) expectError("INVALID_ARGUMENT") { store.start(bad) }
        assertNull(store.get())
    }

    @Test fun stopIsDurableIdempotentAndOldIdCannotStopNewTrip() {
        store.start(config())
        val stopped = store.stop("trip-1", "APP_STOP")
        assertEquals("STOPPED", TripStateStore(context).get()!!["phase"])
        assertEquals(stopped, store.stop("trip-1", "EXIT_ALARM"))
        expectError("TRIP_ALREADY_STOPPED") { store.start(config()) }
        val next = store.start(config("trip-2"))
        expectError("TRIP_ID_MISMATCH") { store.stop("trip-1") }
        assertEquals(next, store.get())
    }

    @Test fun corruptStateIsReportedInsteadOfOverwritten() {
        context.getSharedPreferences(TripStateStore.PREFERENCES, Context.MODE_PRIVATE)
            .edit().putString("trip", "broken JSON").commit()
        expectError("TRIP_STATE_CORRUPT") { store.get() }
        expectError("TRIP_STATE_CORRUPT") { store.start(config()) }
    }

    @Test fun exitAlarmKeepsExistingStopPathAndMarksTripStopped() {
        store.start(config())
        context.getSharedPreferences("backgroundLocationPreferences", Context.MODE_PRIVATE)
            .edit().putBoolean("locationActive", true).commit()
        Utils.setRequestingLocationUpdates(context, true)
        val service = Robolectric.buildService(LocationUpdatesService::class.java).create().get()
        service.onStartCommand(Intent(context, LocationUpdatesService::class.java).setAction("ACTION_EXIT_ALARM"), 0, 1)
        assertEquals("STOPPED", store.get()!!["phase"])
        assertEquals("EXIT_ALARM", store.get()!!["stopReason"])
        assertFalse(Utils.requestingLocationUpdates(context))
        assertFalse(context.getSharedPreferences("backgroundLocationPreferences", Context.MODE_PRIVATE).getBoolean("locationActive", true))
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    @Test fun cancelledQueuedStartDoesNotReactivateService() {
        store.start(config())
        store.stop("trip-1")
        val service = Robolectric.buildService(LocationUpdatesService::class.java).create().get()
        service.triggerForegroundServiceStart(Intent().putExtra("mapAlarmTripId", "trip-1"))
        assertEquals("STOPPED", store.get()!!["phase"])
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    @Test fun channelReadsSnapshotAndReturnsValidationErrors() {
        val bridge = BackgroundLocationService()
        BackgroundLocationService::class.java.getDeclaredField("context").apply {
            isAccessible = true
            set(bridge, context)
        }
        val empty = CapturedResult()
        bridge.onMethodCall(MethodCall("getTripState", null), empty)
        assertNull((empty.value as Map<*, *>)["trip"])
        assertNull(empty.errorCode)
        val invalid = CapturedResult()
        bridge.onMethodCall(MethodCall("startTrip", mapOf("radiusMeters" to -1)), invalid)
        assertEquals("INVALID_ARGUMENT", invalid.errorCode)
        assertNull(store.get())
        store.start(config())
        val stale = CapturedResult()
        bridge.onMethodCall(MethodCall("stopTrip", mapOf("tripId" to "old-trip")), stale)
        assertEquals("TRIP_ID_MISMATCH", stale.errorCode)
        assertEquals("TRACKING", store.get()!!["phase"])
    }

    @Test fun channelStartsOnceAndStopsMatchingTrip() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val messenger = Proxy.newProxyInstance(
            BinaryMessenger::class.java.classLoader,
            arrayOf(BinaryMessenger::class.java),
        ) { _, _, _ -> null } as BinaryMessenger
        val bridge = BackgroundLocationService()
        val countedContext = CountingContext(context)
        bridge.onAttachedToEngine(countedContext, messenger)
        val started = CapturedResult()
        bridge.onMethodCall(MethodCall("startTrip", config()), started)
        assertNull(started.errorCode)
        val intent = shadowOf(context as Application).nextStartedService
        assertEquals(LocationUpdatesService.ACTION_START_FOREGROUND_SERVICE, intent.action)
        assertEquals("trip-1", intent.getStringExtra("mapAlarmTripId"))
        val repeated = CapturedResult()
        bridge.onMethodCall(MethodCall("startTrip", config()), repeated)
        assertNull(repeated.errorCode)
        assertEquals(1, countedContext.foregroundStarts)
        val stopped = CapturedResult()
        bridge.onMethodCall(MethodCall("stopTrip", mapOf("tripId" to "trip-1")), stopped)
        assertNull(stopped.errorCode)
        assertEquals("STOPPED", store.get()!!["phase"])
        assertEquals("APP_STOP", store.get()!!["stopReason"])
    }

    @Test fun exitStillStopsWhenTripStorageIsCorrupt() {
        context.getSharedPreferences(TripStateStore.PREFERENCES, Context.MODE_PRIVATE)
            .edit().putString("trip", "broken JSON").commit()
        Utils.setRequestingLocationUpdates(context, true)
        val service = Robolectric.buildService(LocationUpdatesService::class.java).create().get()
        service.onStartCommand(Intent().setAction("ACTION_EXIT_ALARM"), 0, 1)
        assertFalse(Utils.requestingLocationUpdates(context))
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    @Test fun serviceDestructionDoesNotCancelPersistedTrip() {
        store.start(config())
        Robolectric.buildService(LocationUpdatesService::class.java).create().destroy()
        assertEquals("TRACKING", store.get()!!["phase"])
    }

    private fun expectError(code: String, block: () -> Unit) {
        try { block(); fail("Expected $code") } catch (error: TripStateException) {
            assertEquals(code, error.code)
        }
    }

    // Robolectric also records bindService in its started-services queue.
    // Count the actual foreground-start API, not that combined queue.
    private class CountingContext(base: Context) : ContextWrapper(base) {
        var foregroundStarts = 0
        override fun startForegroundService(intent: Intent): ComponentName? {
            foregroundStarts++
            return super.startForegroundService(intent)
        }
    }

    private class CapturedResult : MethodChannel.Result {
        var value: Any? = null
        var errorCode: String? = null
        override fun success(result: Any?) { value = result }
        override fun error(code: String, message: String?, details: Any?) { errorCode = code }
        override fun notImplemented() { fail("Method not implemented") }
    }
}
