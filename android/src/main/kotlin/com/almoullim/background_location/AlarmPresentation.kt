package com.almoullim.background_location

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.view.WindowManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/** Presentation only: never starts/stops tracking or audio and never dismisses keyguard. */
internal object AlarmPresentationAccess {
    fun status(context: Context): Map<String, Boolean> {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notifications = NotificationManagerCompat.from(context).areNotificationsEnabled()
        val fullScreen = when {
            Build.VERSION.SDK_INT >= 34 -> manager.canUseFullScreenIntent()
            Build.VERSION.SDK_INT >= 29 -> ContextCompat.checkSelfPermission(context,
                Manifest.permission.USE_FULL_SCREEN_INTENT) == PackageManager.PERMISSION_GRANTED
            else -> true
        }
        val channel = if (Build.VERSION.SDK_INT >= 26) manager.getNotificationChannel("map_alarm_native_ringing") else null
        val high = channel == null || channel.importance >= NotificationManager.IMPORTANCE_HIGH
        return mapOf("notificationsAllowed" to notifications, "fullScreenAllowed" to fullScreen,
            "channelHighImportance" to high, "canPresent" to (notifications && fullScreen && high))
    }
}

internal class AlarmWindowController(private val activity: Activity) {
    private val preferences = activity.getSharedPreferences(TripStateStore.PREFERENCES, Context.MODE_PRIVATE)
    private var launchIntent: Intent? = activity.intent
    internal var showingAlarm = false
        private set
    private var disposed = false
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        activity.runOnUiThread { if (!disposed) refresh() }
    }
    init {
        preferences.registerOnSharedPreferenceChangeListener(listener)
        refresh()
    }
    fun onIntent(intent: Intent) { launchIntent = intent; activity.intent = intent; refresh() }
    private fun refresh() {
        val uri = launchIntent?.data
        val state = try { TripStateStore(activity).get() } catch (_: RuntimeException) { null }
        val show = launchIntent?.action == Intent.ACTION_VIEW && uri?.scheme == "mapalarmv3" &&
            uri.host == "mapalarmv3.com" && uri.path == "/hWakeUpAlert" &&
            state?.get("phase") == "RINGING" && state["tripId"] == uri.getQueryParameter("tripId")
        if (show != showingAlarm) setShowing(show)
    }
    private fun setShowing(show: Boolean) {
        showingAlarm = show
        if (Build.VERSION.SDK_INT >= 27) {
            activity.setShowWhenLocked(show)
            activity.setTurnScreenOn(show)
        } else {
            @Suppress("DEPRECATION")
            val flags = WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            if (show) activity.window.addFlags(flags) else activity.window.clearFlags(flags)
        }
        if (show) activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    fun dispose() {
        disposed = true
        preferences.unregisterOnSharedPreferenceChangeListener(listener)
        if (showingAlarm) setShowing(false)
    }
}