package com.almoullim.background_location

import android.content.Context
import android.location.Location
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Looper
import android.os.SystemClock
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.util.DataSource
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class NativeAlarmTest {
    private lateinit var context: Context
    private lateinit var store: TripStateStore
    private val config = mapOf("tripId" to "native-1", "destinationName" to "Destination",
        "destinationLatitude" to 25.0, "destinationLongitude" to 55.0, "radiusMeters" to 1000.0)
    private var time = 0L
    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(TripStateStore.PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit()
        store = TripStateStore(context)
        store.start(config)
        time = System.currentTimeMillis() + 1
    }
    private fun fix(latitude: Double, accuracy: Float = 10f) = Location("gps").apply {
        this.latitude = latitude; longitude = 55.0; this.accuracy = accuracy
        time = this@NativeAlarmTest.time++
        elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
    }
    @Test fun crossesRadiusOnceAndRestoresRingingWithoutFlutter() {
        assertEquals("TRACKING", store.location("native-1", fix(25.03))!!["phase"])
        val ringing = store.location("native-1", fix(25.005))!!
        assertEquals("RINGING", ringing["phase"])
        assertTrue((ringing["lastDistanceMeters"] as Double) < 1000)
        val again = store.location("native-1", fix(25.004))!!
        assertEquals(ringing["triggeredAtMs"], again["triggeredAtMs"])
        assertEquals("RINGING", TripStateStore(context).get()!!["phase"])
    }
    @Test fun stalePoorAccuracyAndOldTripCannotTrigger() {
        assertNull(store.location("old-trip", fix(25.0)))
        assertNull(store.location("native-1", fix(25.0, 2000f)))
        val stale = fix(25.0).apply { elapsedRealtimeNanos -= 121_000_000_000L }
        assertNull(store.location("native-1", stale))
        assertEquals("TRACKING", store.get()!!["phase"])
        store.stop("native-1")
        assertNull(store.location("native-1", fix(25.0)))
        store.audio("native-1", "PLAYING")
        assertEquals("STOPPED", store.get()!!["phase"])
    }
    @Test fun oneLoopingAlarmUsesAlarmAttributesAndReleasesOnStop() {
        ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource("alarm-test.wav"), ShadowMediaPlayer.MediaInfo(1000, 10))
        val players = mutableListOf<MediaPlayer>()
        val statuses = mutableListOf<String>()
        val owner = NativeAlarmPlayer(context, { _, status, _ -> statuses.add(status) },
            { MediaPlayer().also { players.add(it) } }, { it.setDataSource("alarm-test.wav") })
        owner.start("native-1")
        owner.start("native-1")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
        assertEquals(1, players.size)
        assertEquals(AudioAttributes.USAGE_ALARM, shadowOf(players.single()).audioAttributes.usage)
        assertTrue(players.single().isLooping)
        assertTrue(players.single().isPlaying)
        assertTrue(statuses.contains("PLAYING"))
        owner.stop()
        assertEquals(ShadowMediaPlayer.State.END, shadowOf(players.single()).state)
    }
    @Test fun stopWhilePreparingCannotPlayLater() {
        ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource("slow.wav"), ShadowMediaPlayer.MediaInfo(1000, 100))
        val statuses = mutableListOf<String>()
        val owner = NativeAlarmPlayer(context, { _, status, _ -> statuses.add(status) },
            { MediaPlayer() }, { it.setDataSource("slow.wav") })
        owner.start("native-1")
        owner.stop()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(20))
        assertFalse(statuses.contains("PLAYING"))
    }
    @Test fun preparationFailureIsReportedAndStopCancelsRetries() {
        var attempts = 0
        val statuses = mutableListOf<String>()
        val owner = NativeAlarmPlayer(context, { _, status, _ -> statuses.add(status) },
            { MediaPlayer() }, { attempts++; throw java.io.IOException("Missing test asset") })
        owner.start("native-1")
        assertTrue(statuses.contains("ERROR"))
        owner.stop()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(20))
        assertEquals(1, attempts)
    }
    private class RecordingAlarm : AlarmAudio {
        var started = false
        var stopped = false
        override fun start(tripId: String) { started = true }
        override fun stop() { stopped = true }
    }

    private fun nativeService(tripId: String = "native-1"): LocationUpdatesService {
        val service = org.robolectric.Robolectric.buildService(LocationUpdatesService::class.java).create().get()
        service.alarmAudio = RecordingAlarm()
        LocationUpdatesService::class.java.getDeclaredField("nativeTripId").apply {
            isAccessible = true; set(service, tripId)
        }
        LocationUpdatesService::class.java.getDeclaredField("mNotificationManager").apply {
            isAccessible = true; set(service, notifications())
        }
        LocationUpdatesService.NOTIFICATION_CHANNEL_ID = "map_alarm_native_tracking"
        LocationUpdatesService.NOTIFICATION_TITLE = "Map Alarm is active"
        LocationUpdatesService.NOTIFICATION_ACTION = "EXIT ALARM"
        LocationUpdatesService.NOTIFICATION_ACTION_CALLBACK = 0L
        context.getSharedPreferences("backgroundLocationPreferences", Context.MODE_PRIVATE)
            .edit().putBoolean("locationActive", true).commit()
        return service
    }

    private fun notifications() = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
    private fun trackingNotification() = shadowOf(notifications()).getNotification(12345677)
    private fun foregroundNotification() = shadowOf(notifications()).getNotification(12345678)

    @Test fun nativeLocationPostsAlarmAndExitStopsAudioWithoutFlutter() {
        val service = nativeService()
        val audio = service.alarmAudio as RecordingAlarm
        service.processNativeLocation(fix(25.03))
        val tracking = trackingNotification()
        assertNotNull(tracking)
        assertNull(foregroundNotification())
        service.processNativeLocation(fix(25.02))
        assertNotNull(trackingNotification())
        assertNull(foregroundNotification())
        assertEquals(1, shadowOf(notifications()).allNotifications.size)
        assertNull(tracking.fullScreenIntent)
        assertEquals("Localisation", shadowOf(tracking.contentIntent).savedIntent.action)
        // Simulate a leftover duplicate from the previous APK.
        notifications().notify(1001, tracking)
        service.processNativeLocation(fix(25.005))
        assertEquals("RINGING", store.get()!!["phase"])
        assertTrue(audio.started)
        val ringing = foregroundNotification()
        assertNotNull(ringing)
        assertNull(trackingNotification())
        assertEquals(12345678, shadowOf(service).lastForegroundNotificationId)
        assertNull(shadowOf(notifications()).getNotification(1001))
        assertEquals(1, shadowOf(notifications()).allNotifications.size)
        assertEquals(android.app.Notification.CATEGORY_ALARM, ringing.category)
        assertEquals(ringing.contentIntent, ringing.fullScreenIntent)
        assertNotEquals(tracking.contentIntent, ringing.contentIntent)
        val tap = shadowOf(ringing.contentIntent).savedIntent
        assertEquals(android.content.Intent.ACTION_VIEW, tap.action)
        assertEquals("mapalarmv3", tap.data!!.scheme)
        assertEquals("mapalarmv3.com", tap.data!!.host)
        assertEquals("/hWakeUpAlert", tap.data!!.path)
        assertEquals("Destination", tap.data!!.getQueryParameter("destinationName"))
        assertEquals("native-1", tap.data!!.getQueryParameter("tripId"))
        assertEquals(String.format(java.util.Locale.US, "%.2f km",
            (store.get()!!["lastDistanceMeters"] as Number).toDouble() / 1000.0),
            tap.data!!.getQueryParameter("distanceText"))
        assertNotNull(tap.component)
        assertTrue(tap.flags and android.content.Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertTrue(tap.flags and android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
        assertTrue(tap.flags and android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
        ringing.contentIntent.send()
        assertEquals(tap.data, shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity.data)
        val exit = shadowOf(ringing.actions.single().actionIntent).savedIntent
        assertEquals("ACTION_EXIT_ALARM", exit.action)
        service.onStartCommand(exit, 0, 1)
        assertTrue(audio.stopped)
        assertEquals("STOPPED", store.get()!!["phase"])
        assertNull(foregroundNotification())
        assertNull(shadowOf(notifications()).getNotification(1001))
        assertEquals(0, shadowOf(notifications()).allNotifications.size)
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    @Test fun ringingTapIsEncodedAndStableAcrossGpsUpdates() {
        store.stop("native-1")
        store.start(config + mapOf("tripId" to "native-2", "destinationName" to "Mall & Cafe / Gate #2"))
        time = System.currentTimeMillis() + 1
        val service = nativeService("native-2")
        service.processNativeLocation(fix(25.005))
        val first = foregroundNotification().contentIntent
        val uri = shadowOf(first).savedIntent.data!!
        assertEquals("Mall & Cafe / Gate #2", uri.getQueryParameter("destinationName"))
        assertNull(uri.fragment)
        assertEquals("native-2", uri.getQueryParameter("tripId"))
        service.processNativeLocation(fix(25.004))
        val updated = foregroundNotification().contentIntent
        assertEquals(first, updated)
        assertNull(foregroundNotification().fullScreenIntent)
        assertEquals(uri, shadowOf(updated).savedIntent.data)
        assertEquals(1, shadowOf(notifications()).allNotifications.size)
        assertNull(trackingNotification())
        assertNull(shadowOf(notifications()).getNotification(1001))
        service.triggerForegroundServiceStop()
    }

    @Test fun recreatedNotificationReadsPersistedRingingRoute() {
        val first = nativeService()
        first.processNativeLocation(fix(25.005))
        val uri = shadowOf(foregroundNotification().contentIntent).savedIntent.data
        first.onDestroy()
        assertEquals(0, shadowOf(notifications()).allNotifications.size)
        assertEquals("RINGING", TripStateStore(context).get()!!["phase"])
        val recreated = nativeService()
        recreated.updateNotification()
        val tap = shadowOf(foregroundNotification().contentIntent).savedIntent
        assertEquals(android.content.Intent.ACTION_VIEW, tap.action)
        assertEquals(uri, tap.data)
        assertNull(foregroundNotification().fullScreenIntent)
        assertEquals(1, shadowOf(notifications()).allNotifications.size)
        assertNull(trackingNotification())
        assertNull(shadowOf(notifications()).getNotification(1001))
        recreated.triggerForegroundServiceStop()
    }
    @Test fun disabledNotificationsKeepAudioAndTapFallback() {
        shadowOf(notifications()).setNotificationsEnabled(false)
        val service = nativeService()
        val audio = service.alarmAudio as RecordingAlarm
        service.processNativeLocation(fix(25.03))
        assertNotNull(trackingNotification())
        assertNull(foregroundNotification())
        service.processNativeLocation(fix(25.005))
        assertNull(trackingNotification())
        assertTrue(audio.started)
        assertEquals("RINGING", store.get()!!["phase"])
        assertNull(foregroundNotification().fullScreenIntent)
        assertNotNull(foregroundNotification().contentIntent)
        assertEquals(1, shadowOf(notifications()).allNotifications.size)
        service.triggerForegroundServiceStop()
    }

    @Test fun downgradedChannelDoesNotRequestFullScreen() {
        notifications().createNotificationChannel(android.app.NotificationChannel(
            "map_alarm_native_ringing", "Destination alarm", android.app.NotificationManager.IMPORTANCE_LOW))
        val service = nativeService()
        service.processNativeLocation(fix(25.005))
        assertTrue((service.alarmAudio as RecordingAlarm).started)
        assertNull(foregroundNotification().fullScreenIntent)
        assertEquals(1, shadowOf(notifications()).allNotifications.size)
        service.triggerForegroundServiceStop()
    }

    @Test @Config(sdk = [33]) fun android13GetsNewAlarmIdWithFullScreenIntentOnlyOnTransition() {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(
            android.Manifest.permission.USE_FULL_SCREEN_INTENT,
            android.Manifest.permission.POST_NOTIFICATIONS)
        val service = nativeService()
        service.processNativeLocation(fix(25.03))
        assertEquals(12345677, shadowOf(service).lastForegroundNotificationId)
        assertNotNull(trackingNotification())
        assertNull(foregroundNotification()) // SystemUI must not have seen the alarm key yet.
        service.processNativeLocation(fix(25.005))
        assertEquals(12345678, shadowOf(service).lastForegroundNotificationId)
        assertNull(trackingNotification())
        val first = foregroundNotification()
        assertNotNull(first.fullScreenIntent)
        assertEquals(first.contentIntent, first.fullScreenIntent)
        assertEquals(1, shadowOf(notifications()).allNotifications.size)
        service.processNativeLocation(fix(25.004))
        assertNull(foregroundNotification().fullScreenIntent)
        service.updateNotification()
        assertEquals(12345678, shadowOf(service).lastForegroundNotificationId)
        assertNull(trackingNotification())
        assertNull(foregroundNotification().fullScreenIntent)
        assertEquals(first.contentIntent, foregroundNotification().contentIntent)
        assertEquals(1, shadowOf(notifications()).allNotifications.size)
        service.triggerForegroundServiceStop("APP_STOP")
        assertEquals("STOPPED", store.get()!!["phase"])
        assertEquals(0, shadowOf(notifications()).allNotifications.size)
    }

    @Test fun exitDuringTrackingClearsBothIdsAndQueuedUpdatesCannotRepost() {
        val service = nativeService()
        val audio = service.alarmAudio as RecordingAlarm
        service.processNativeLocation(fix(25.03))
        val tracking = trackingNotification()
        notifications().notify(12345678, tracking) // stale alarm from an earlier build
        notifications().notify(1001, tracking)
        val exit = shadowOf(tracking.actions.single().actionIntent).savedIntent
        service.onStartCommand(exit, 0, 1)
        assertTrue(audio.stopped)
        assertEquals("STOPPED", store.get()!!["phase"])
        assertNull(trackingNotification())
        assertNull(foregroundNotification())
        assertEquals(0, shadowOf(notifications()).allNotifications.size)
        service.updateNotification()
        service.processNativeLocation(fix(25.0))
        assertEquals(0, shadowOf(notifications()).allNotifications.size)
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    @Test fun destroyDuringTrackingRemovesTrackingAndStaleAlarmNotifications() {
        val service = nativeService()
        service.processNativeLocation(fix(25.03))
        notifications().notify(12345678, trackingNotification())
        notifications().notify(1001, trackingNotification())
        service.onDestroy()
        assertEquals(0, shadowOf(notifications()).allNotifications.size)
        assertEquals("TRACKING", store.get()!!["phase"])
    }
}
