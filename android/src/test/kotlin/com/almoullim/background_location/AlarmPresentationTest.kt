package com.almoullim.background_location

import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.location.Location
import android.net.Uri
import android.os.Looper
import android.os.SystemClock
import android.view.WindowManager
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowNotificationManager

@Implements(NotificationManager::class)
class FullScreenAccessShadow : ShadowNotificationManager() {
    var fullScreenAllowed = false
    @Implementation(minSdk = 34)
    protected fun canUseFullScreenIntent(): Boolean = fullScreenAllowed
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class AlarmPresentationTest {
    private lateinit var store: TripStateStore
    private lateinit var context: Context
    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(TripStateStore.PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit()
        store = TripStateStore(context)
        store.start(mapOf("tripId" to "window-1", "destinationName" to "Destination",
            "destinationLatitude" to 25.0, "destinationLongitude" to 55.0, "radiusMeters" to 1000.0))
    }
    private fun ring() {
        store.location("window-1", Location("gps").apply {
            latitude = 25.0; longitude = 55.0; accuracy = 5f
            time = System.currentTimeMillis() + 1; elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        })
    }
    private fun alarmIntent(id: String = "window-1") = Intent(Intent.ACTION_VIEW,
        Uri.parse("mapalarmv3://mapalarmv3.com/hWakeUpAlert?tripId=$id"))

    @Test fun coldAlarmShowsAboveLockAndStopClearsAllWindowFlags() {
        ring()
        val activity = Robolectric.buildActivity(Activity::class.java, alarmIntent()).create().get()
        val controller = AlarmWindowController(activity)
        assertTrue(controller.showingAlarm)
        assertTrue(shadowOf(activity).showWhenLocked)
        assertTrue(shadowOf(activity).turnScreenOn)
        assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0)
        store.stop("window-1", "EXIT_ALARM")
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(controller.showingAlarm)
        assertFalse(shadowOf(activity).showWhenLocked)
        assertFalse(shadowOf(activity).turnScreenOn)
        assertEquals(0, activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        controller.dispose()
    }

    @Test fun warmActivityOnlyEnablesFlagsForCurrentAlarmAndClearsOnDetach() {
        val activity = Robolectric.buildActivity(Activity::class.java).create().get()
        val controller = AlarmWindowController(activity)
        ring()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(controller.showingAlarm)
        controller.onIntent(alarmIntent("old-trip"))
        assertFalse(controller.showingAlarm)
        controller.onIntent(alarmIntent())
        assertTrue(controller.showingAlarm)
        controller.dispose()
        assertFalse(shadowOf(activity).showWhenLocked)
        assertFalse(shadowOf(activity).turnScreenOn)
    }

    @Test fun stoppedTripCannotEnableLockScreenFlags() {
        ring(); store.stop("window-1")
        val activity = Robolectric.buildActivity(Activity::class.java, alarmIntent()).create().get()
        val controller = AlarmWindowController(activity)
        assertFalse(controller.showingAlarm)
        assertFalse(shadowOf(activity).showWhenLocked)
        controller.dispose()
    }

    @Test @Config(sdk = [24]) fun legacyLockScreenFlagsAreClearedOnStop() {
        ring()
        val activity = Robolectric.buildActivity(Activity::class.java, alarmIntent()).create().get()
        val controller = AlarmWindowController(activity)
        @Suppress("DEPRECATION")
        val flags = WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        assertEquals(flags, activity.window.attributes.flags and flags)
        store.stop("window-1")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, activity.window.attributes.flags and flags)
        controller.dispose()
    }

    @Test @Config(sdk = [34], shadows = [FullScreenAccessShadow::class])
    fun android14SpecialAccessIsCheckedIndependentlyOfNotificationAccess() {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val shadow = Shadow.extract<FullScreenAccessShadow>(manager)
        shadow.setNotificationsEnabled(true)
        shadow.fullScreenAllowed = false
        assertFalse(AlarmPresentationAccess.status(context)["canPresent"]!!)
        shadow.fullScreenAllowed = true
        assertTrue(AlarmPresentationAccess.status(context)["canPresent"]!!)
        shadow.setNotificationsEnabled(false)
        assertFalse(AlarmPresentationAccess.status(context)["canPresent"]!!)
    }
}