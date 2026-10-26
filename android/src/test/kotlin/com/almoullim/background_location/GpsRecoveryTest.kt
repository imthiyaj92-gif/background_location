package com.almoullim.background_location

import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.Looper
import android.os.SystemClock
import com.google.android.gms.location.*
import com.google.android.gms.tasks.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy
import java.time.Duration

/** Real service recovery/stop methods with a controllable asynchronous location provider.
 * No network, Flutter engine, physical GPS, or real alarm playback is used.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class GpsRecoveryTest {
    private lateinit var service: LocationUpdatesService
    private lateinit var store: TripStateStore
    private lateinit var probeTask: TaskCompletionSource<Location>
    private lateinit var removalTask: TaskCompletionSource<Void>
    private lateinit var probeToken: CancellationToken
    private var requests = 0
    private var removals = 0
    private var rings = 0
    private var fixTime = 0L

    private fun field(name: String, value: Any?) {
        LocationUpdatesService::class.java.getDeclaredField(name).apply {
            isAccessible = true; set(service, value)
        }
    }

    @Before fun setup() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(TripStateStore.PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("backgroundLocationPreferences", Context.MODE_PRIVATE)
            .edit().clear().putBoolean("locationActive", true).commit()
        store = TripStateStore(context)
        store.start(mapOf("tripId" to "recovery", "destinationName" to "Offline destination",
            "destinationLatitude" to 25.0, "destinationLongitude" to 55.0, "radiusMeters" to 1000.0))
        fixTime = System.currentTimeMillis() + 1
        probeTask = TaskCompletionSource()
        removalTask = TaskCompletionSource()
        val provider = Proxy.newProxyInstance(javaClass.classLoader,
            arrayOf(FusedLocationProviderClient::class.java)) { _, method, args ->
            when (method.name) {
                "requestLocationUpdates" -> { requests++; Tasks.forResult<Void>(null) }
                "removeLocationUpdates" -> { removals++; removalTask.task }
                "getCurrentLocation" -> {
                    val request = args!![0] as CurrentLocationRequest
                    assertEquals(0L, request.maxUpdateAgeMillis)
                    probeToken = args[1] as CancellationToken
                    probeTask.task
                }
                else -> throw AssertionError("Unexpected provider method ${method.name}")
            }
        } as FusedLocationProviderClient
        service = Robolectric.buildService(LocationUpdatesService::class.java).create().get()
        field("nativeTripId", "recovery")
        field("isGoogleApiAvailable", true)
        field("mFusedLocationClient", provider)
        field("mLocationRequest", LocationRequest.create().setInterval(1000).setSmallestDisplacement(10f))
        field("mFusedLocationCallback", object : LocationCallback() {})
        field("mNotificationManager", context.getSystemService(Context.NOTIFICATION_SERVICE))
        service.alarmAudio = object : AlarmAudio {
            override fun start(tripId: String) { rings++ }
            override fun stop() {}
        }
        LocationUpdatesService.NOTIFICATION_CHANNEL_ID = "map_alarm_native_tracking"
        LocationUpdatesService.NOTIFICATION_ACTION = "EXIT ALARM"
        LocationUpdatesService.NOTIFICATION_ACTION_CALLBACK = 0L
        @Suppress("UNCHECKED_CAST")
        fun probe(done: () -> Unit): () -> Unit = LocationUpdatesService::class.java
            .getDeclaredMethod("probeFreshLocation", kotlin.jvm.functions.Function0::class.java)
            .apply { isAccessible = true }.invoke(service, done) as () -> Unit
        @Suppress("UNCHECKED_CAST")
        fun renew(done: (Boolean) -> Unit): () -> Unit = LocationUpdatesService::class.java
            .getDeclaredMethod("renewLocationSubscription", kotlin.jvm.functions.Function1::class.java)
            .apply { isAccessible = true }.invoke(service, done) as () -> Unit
        service.locationHealth = LocationHealthMonitor({ SystemClock.elapsedRealtime() }, { null }, ::probe, ::renew)
        service.requestLocationUpdates()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, requests)
    }

    private fun fix(latitude: Double) = Location("gps").apply {
        this.latitude = latitude; longitude = 55.0; accuracy = 5f
        time = fixTime++
        elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
    }
    private fun beginProbe() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(61))
        service.locationHealth!!.tick()
    }
    private fun failProbe() {
        probeTask.setResult(null)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, removals)
        assertEquals(1, requests) // Removal has not completed: no second subscription.
    }

    @Test fun freshOfflineProbeUsesUnchangedRadiusAndStartsNativeAlarm() {
        service.processNativeLocation(fix(25.03))
        beginProbe()
        probeTask.setResult(fix(25.005))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("RINGING", store.get()!!["phase"])
        assertEquals(1, rings)
        assertEquals(1, requests)
        assertEquals(0, removals)
        assertFalse(service.locationHealth!!.snapshot()["recoveryPending"] as Boolean)
        service.triggerForegroundServiceStop()
    }

    @Test fun freshFixDuringRemovalRestoresExactlyOneSubscription() {
        beginProbe(); failProbe()
        service.processNativeLocation(fix(25.03)) // Cancels recovery, but removal is in flight.
        removalTask.setResult(null)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(2, requests)
        assertEquals(1, removals)
        assertEquals("FRESH", service.locationHealth!!.snapshot()["state"])
        service.triggerForegroundServiceStop()
    }

    @Test fun failedRemovalNeverAddsADuplicateSubscription() {
        beginProbe(); failProbe()
        removalTask.setException(IllegalStateException("Provider rejected removal"))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, requests)
        assertEquals("SUBSCRIPTION_ERROR", service.locationHealth!!.snapshot()["state"])
        service.triggerForegroundServiceStop()
    }

    @Test fun exitDuringProbeCancelsItAndLateFixCannotRing() {
        beginProbe()
        service.onStartCommand(Intent().setAction("ACTION_EXIT_ALARM"), 0, 1)
        assertTrue(probeToken.isCancellationRequested)
        probeTask.setResult(fix(25.0))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, rings)
        assertEquals("STOPPED", store.get()!!["phase"])
        assertNull(service.locationHealth)
    }

    @Test fun appStopDuringRemovalCannotRegisterAgain() {
        beginProbe(); failProbe()
        // Same service stop path used by the method-channel stopTrip broadcast.
        val receiver = LocationUpdatesService::class.java.getDeclaredField("actionReceiver\$delegate")
            .apply { isAccessible = true }.get(service) as Lazy<*>
        (receiver.value as android.content.BroadcastReceiver).onReceive(service,
            Intent().putExtra(LocationUpdatesService.ACTION_SERVICE_REQUEST,
                LocationUpdatesService.ACTION_STOP_FOREGROUND_SERVICE))
        removalTask.setResult(null)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, requests)
        assertEquals("STOPPED", store.get()!!["phase"])
        assertNull(service.locationHealth)
    }

    @Test fun destructionCancelsRecoveryWithoutCancellingDurableTrip() {
        beginProbe()
        service.onDestroy()
        assertTrue(probeToken.isCancellationRequested)
        probeTask.setResult(fix(25.0))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, rings)
        assertEquals("TRACKING", store.get()!!["phase"])
        assertNull(service.locationHealth)
    }
}
