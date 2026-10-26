package com.almoullim.background_location

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.content.*
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.annotation.NonNull
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.PluginRegistry

import com.almoullim.background_location.PLUGIN_ID
import com.almoullim.background_location.TAG

class BackgroundLocationService : MethodChannel.MethodCallHandler,
    PluginRegistry.RequestPermissionsResultListener {
    companion object {
        val METHOD_CHANNEL_NAME = "${PLUGIN_ID}/methods"
        private const val REQUEST_PERMISSIONS_REQUEST_CODE = 34

        private var instance: BackgroundLocationService? = null

        /**
         * Requests the singleton instance of [BackgroundLocationService] or creates it,
         * if it does not yet exist.
         */
        fun getInstance(): BackgroundLocationService {
            if (instance == null) {
                instance = BackgroundLocationService()
            }
            return instance!!
        }
    }


    /**
     * Context that is set once attached to a FlutterEngine.
     * Context should no longer be referenced when detached.
     */
    private var context: Context? = null
    private lateinit var channel: MethodChannel
    private var activity: Activity? = null
    private var isAttached = false
    private var receiver: MyReceiver? = null
    private var service: LocationUpdatesService? = null

    /**
     * Signals whether the LocationUpdatesService is bound
     */
    private var bound: Boolean = false
    private var pendingNativeTrip: String? = null
    private var pendingNativeAt: Long = 0

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            bound = true
            val binder = service as LocationUpdatesService.LocalBinder
            this@BackgroundLocationService.service = binder.service
            requestLocation()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
        }
    }

    fun onAttachedToEngine(@NonNull context: Context, @NonNull messenger: BinaryMessenger) {
        this.context = context
        isAttached = true
        channel = MethodChannel(messenger, METHOD_CHANNEL_NAME)
        channel.setMethodCallHandler(this)

        receiver = MyReceiver()

        LocalBroadcastManager.getInstance(context).registerReceiver(
            receiver!!, IntentFilter(LocationUpdatesService.ACTION_BROADCAST)
        )
    }

    fun onDetachedFromEngine() {
        channel.setMethodCallHandler(null)
        context = null
        isAttached = false
    }

    fun setActivity(binding: ActivityPluginBinding?) {
        this.activity = binding?.activity

        if (this.activity != null) {
            if (Utils.requestingLocationUpdates(context!!)) {
                if (!checkPermissions()) {
                    requestPermissions()
                }
            }
        }
    }

    private fun startLocationService(
        startOnBoot: Boolean?,
        interval: Int?,
        fastestInterval: Int?,
        priority: Int?,
        distanceFilter: Double?,
        forceLocationManager: Boolean?,
        callbackHandle: Long?,
        locationCallback: Long?,
        nativeTripId: String? = null,
    ): Int {
        LocalBroadcastManager.getInstance(context!!).registerReceiver(
            receiver!!, IntentFilter(LocationUpdatesService.ACTION_BROADCAST)
        )
            val intent = Intent(context, LocationUpdatesService::class.java)
            intent.setAction(LocationUpdatesService.ACTION_START_FOREGROUND_SERVICE)
            intent.putExtra("startOnBoot", startOnBoot)
            intent.putExtra("interval", interval?.toLong())
            intent.putExtra("fastest_interval", fastestInterval?.toLong())
            intent.putExtra("priority", priority)
            intent.putExtra("distance_filter", distanceFilter)
            intent.putExtra("force_location_manager", forceLocationManager)
            intent.putExtra("callbackHandle", callbackHandle)
            intent.putExtra("locationCallback", locationCallback)
            if (nativeTripId != null) intent.putExtra("mapAlarmTripId", nativeTripId)

            ContextCompat.startForegroundService(context!!, intent)
            if (nativeTripId == null) context!!.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)

        return 0
    }

    private fun isLocationServiceRunning(): Boolean {
        val manager: ActivityManager =
            context!!.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        for (service in manager.getRunningServices(Integer.MAX_VALUE)) {
            if (LocationUpdatesService::class.java.getName() == service.service.className) {
                return service.foreground
            }
        }
        return false
    }

    private fun stopLocationService(synchronous: Boolean = false): Int {
        LocalBroadcastManager.getInstance(context!!).unregisterReceiver(receiver!!)

        val intent = Intent(context!!, LocationUpdatesService::class.java)
        intent.setAction("${context!!.packageName}.service_requests")
        intent.putExtra(
            LocationUpdatesService.ACTION_SERVICE_REQUEST,
            LocationUpdatesService.ACTION_STOP_FOREGROUND_SERVICE
        )
        if (synchronous) {
            LocalBroadcastManager.getInstance(context!!).sendBroadcastSync(intent)
        } else {
            LocalBroadcastManager.getInstance(context!!).sendBroadcast(intent)
        }

        if (bound) {
            context!!.unbindService(serviceConnection)
            bound = false
        }

        return 0
    }

    private fun setAndroidNotification(
        channelID: String?,
        title: String?,
        message: String?,
        icon: String?,
        actionText: String?,
        callback: Long,
    ): Int {
        if (channelID != null) LocationUpdatesService.NOTIFICATION_CHANNEL_ID = channelID
        if (title != null) LocationUpdatesService.NOTIFICATION_TITLE = title
        if (message != null) LocationUpdatesService.NOTIFICATION_MESSAGE = message
        if (icon != null) LocationUpdatesService.NOTIFICATION_ICON = icon
        if (actionText != null) {
            LocationUpdatesService.NOTIFICATION_ACTION = actionText
            LocationUpdatesService.NOTIFICATION_ACTION_CALLBACK = callback
        }

        if (service != null) {
            service?.updateNotification()
        }

        return 0
    }

    private fun setConfiguration(timeInterval: Long?): Int {
        if (timeInterval != null) {
            LocationUpdatesService.UPDATE_INTERVAL_IN_MILLISECONDS = timeInterval
            LocationUpdatesService.FASTEST_UPDATE_INTERVAL_IN_MILLISECONDS = timeInterval / 2
        }

        return 0
    }

    /** Step 1 contract: reuse existing GPS startup; no native proximity/audio yet. */
    private fun handleTripMethod(call: MethodCall, result: MethodChannel.Result) {
        val appContext = context ?: run {
            result.error("NO_CONTEXT", "Plugin is detached from the Flutter engine", null)
            return
        }
        val store = TripStateStore(appContext)
        fun snapshot(state: Map<String, Any?>?): Map<String, Any?> = mapOf(
            "trip" to state,
            // TRACKING is persisted intent, not proof of a live GPS subscription.
            "serviceRunning" to isLocationServiceRunning(),
            "locationHealth" to LocationUpdatesService.locationHealthSnapshot(state?.get("tripId") as? String),
        )
        try {
            when (call.method) {
                "getTripState" -> result.success(snapshot(store.get()))
                "startTrip" -> {
                    // Validate before permissions or any service/state mutation.
                    val config = TripStateStore.configuration(call.arguments)
                    if (!checkPermissions()) {
                        throw TripStateException("permissionError", "Location permissions must be granted before startTrip")
                    }
                    val state = store.start(config)
                    // Repeated calls must not queue duplicate service starts.
                    if (!isLocationServiceRunning() && (pendingNativeTrip != config["tripId"] ||
                            android.os.SystemClock.elapsedRealtime() - pendingNativeAt > 10_000)) {
                        pendingNativeTrip = config["tripId"] as String
                        pendingNativeAt = android.os.SystemClock.elapsedRealtime()
                        try {
                            startLocationService(false, 1000, 500, 0, 10.0, false, 0L, 0L, config["tripId"] as String)
                        } catch (error: RuntimeException) {
                            store.stop(config["tripId"] as String, "START_FAILED")
                            throw TripStateException("TRIP_START_FAILED", error.message ?: "Service start failed")
                        }
                    }
                    result.success(snapshot(state))
                }
                "stopTrip" -> {
                    val tripId = (call.arguments as? Map<*, *>)?.get("tripId") as? String
                    if (tripId.isNullOrBlank()) {
                        throw TripStateException("INVALID_ARGUMENT", "tripId must be nonblank")
                    }
                    // A stale UI must never stop a newer trip.
                    val state = store.stop(tripId, "APP_STOP")
                    pendingNativeTrip = null
                    if (state != null) stopLocationService(synchronous = true)
                    result.success(snapshot(state))
                }
            }
        } catch (error: TripStateException) {
            if (call.method == "stopTrip" && error.code == "TRIP_PERSIST_FAILED") {
                // Still honor STOP if durable storage fails.
                try { stopLocationService(synchronous = true) } catch (cleanupError: RuntimeException) {
                    Log.e(TAG, "Trip stop cleanup failed", cleanupError)
                }
            }
            result.error(error.code, error.message, null)
        } catch (error: RuntimeException) {
            result.error("TRIP_OPERATION_FAILED", error.message, null)
        }
    }

    override fun onMethodCall(@NonNull call: MethodCall, @NonNull result: MethodChannel.Result) {
        when (call.method) {
            "getAlarmPresentationStatus" -> {
                val appContext = context
                if (appContext == null) result.error("NO_CONTEXT", "Plugin detached", null)
                else result.success(AlarmPresentationAccess.status(appContext))
            }
            "openAlarmPresentationSettings" -> {
                val currentActivity = activity
                if (currentActivity == null || currentActivity.isFinishing) {
                    result.error("NO_ACTIVITY", "Open settings from the visible app", null)
                } else {
                    val status = AlarmPresentationAccess.status(currentActivity)
                    val settings = when {
                        status["notificationsAllowed"] != true && Build.VERSION.SDK_INT >= 26 ->
                            Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, currentActivity.packageName)
                        status["channelHighImportance"] != true && Build.VERSION.SDK_INT >= 26 ->
                            Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, currentActivity.packageName)
                                .putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, "map_alarm_native_ringing")
                        status["fullScreenAllowed"] != true && Build.VERSION.SDK_INT >= 34 ->
                            Intent(android.provider.Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                                android.net.Uri.parse("package:${currentActivity.packageName}"))
                        else -> Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            android.net.Uri.parse("package:${currentActivity.packageName}"))
                    }
                    try { currentActivity.startActivity(settings); result.success(true) }
                    catch (error: RuntimeException) { result.error("SETTINGS_UNAVAILABLE", error.message, null) }
                }
            }
            "startTrip", "getTripState", "stopTrip" -> handleTripMethod(call, result)
            "stop_location_service" -> result.success(stopLocationService())
            "start_location_service" -> {
                if (!checkPermissions()) {
                    requestPermissions()
                    result.error("permissionError", "Permissions missing for location service to start fully", null)
                    return
                }

                var locationCallback: Long? = 0L
                try {
                    locationCallback = call.argument("locationCallback")
                } catch (ex: Throwable) {
                }

                var callbackHandle: Long? = 0L
                try {
                    callbackHandle = call.argument("callbackHandle")
                } catch (ex: Throwable) {
                }

                val startOnBoot: Boolean = call.argument("startOnBoot") ?: false
                val interval: Int? = call.argument("interval")
                val fastestInterval: Int? = call.argument("fastest_interval")
                val priority: Int? = call.argument("priority")
                val distanceFilter: Double? = call.argument("distance_filter")
                val forceLocationManager: Boolean? = call.argument("force_location_manager")

                result.success(
                    startLocationService(
                        startOnBoot,
                        interval,
                        fastestInterval,
                        priority,
                        distanceFilter,
                        forceLocationManager,
                        callbackHandle,
                        locationCallback,
                    )
                )
            }

            "is_service_running" -> result.success(isLocationServiceRunning())
            "set_android_notification" -> {
                val channelID: String? = call.argument("channelID")
                val notificationTitle: String? = call.argument("title")
                val notificationMessage: String? = call.argument("message")
                val notificationIcon: String? = call.argument("icon")
                val actionText: String? = call.argument("actionText")
                var callback: Long = 0L
                try {
                    callback = call.argument("actionCallback") ?: 0L
                } catch (ex: Throwable) {
                }

                result.success(
                    setAndroidNotification(
                        channelID,
                        notificationTitle,
                        notificationMessage,
                        notificationIcon,
                        actionText,
                        callback,
                    )
                )
            }

            "set_configuration" -> result.success(
                setConfiguration(
                    call.argument<String>("interval")?.toLongOrNull()
                )
            )

            else -> result.notImplemented()
        }
    }

    /**
     * Requests a location updated.
     * If permission is denied, it requests the needed permission
     */
    private fun requestLocation() {
        if (!checkPermissions()) {
            requestPermissions()
        } else {
            service?.requestLocationUpdates()
        }
    }

    /**
     * Checks the current permission for `ACCESS_FINE_LOCATION`
     */
    private fun checkPermissions(): Boolean {
        Log.i(TAG, "Check permission")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Log.i(TAG, "Check permission > Q")
            var allowed = PackageManager.PERMISSION_GRANTED == ActivityCompat.checkSelfPermission(
                context!!, Manifest.permission.ACCESS_FINE_LOCATION
            ) && PackageManager.PERMISSION_GRANTED == ActivityCompat.checkSelfPermission(
                context!!, Manifest.permission.ACCESS_BACKGROUND_LOCATION
            )

            if (allowed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                Log.i(TAG, "Check permission > Upside down cake")
                allowed = PackageManager.PERMISSION_GRANTED == ActivityCompat.checkSelfPermission(
                    context!!, Manifest.permission.FOREGROUND_SERVICE_LOCATION
                )
            }

            return allowed
        } else {
            return PackageManager.PERMISSION_GRANTED == ActivityCompat.checkSelfPermission(
                context!!, Manifest.permission.ACCESS_FINE_LOCATION
            )
        }
    }


    /**
     * Requests permission for location.
     * Depending on the current activity, displays a rationale for the request.
     */
    private fun requestPermissions() {
        if (activity == null) {
            return
        }

        val shouldProvideRationale = ActivityCompat.shouldShowRequestPermissionRationale(
            activity!!,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        if (shouldProvideRationale) {
            Log.i(
                TAG,
                "Displaying permission rationale to provide additional context."
            )
            Toast.makeText(context, R.string.permission_rationale, Toast.LENGTH_LONG).show()

        } else {
            Log.i(TAG, "Requesting permission")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ActivityCompat.requestPermissions(
                    activity!!,
                    arrayOf(
                        Manifest.permission.FOREGROUND_SERVICE_LOCATION,
                        Manifest.permission.ACCESS_BACKGROUND_LOCATION,
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.POST_NOTIFICATIONS
                    ),
                    REQUEST_PERMISSIONS_REQUEST_CODE
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ActivityCompat.requestPermissions(
                    activity!!,
                    arrayOf(
                        Manifest.permission.ACCESS_BACKGROUND_LOCATION,
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.POST_NOTIFICATIONS
                    ),
                    REQUEST_PERMISSIONS_REQUEST_CODE
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ActivityCompat.requestPermissions(
                    activity!!,
                    arrayOf(
                        Manifest.permission.ACCESS_BACKGROUND_LOCATION,
                        Manifest.permission.ACCESS_FINE_LOCATION
                    ),
                    REQUEST_PERMISSIONS_REQUEST_CODE
                )
            } else {
                ActivityCompat.requestPermissions(
                    activity!!,
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION
                    ),
                    REQUEST_PERMISSIONS_REQUEST_CODE
                )
            }
        }
    }

    private inner class MyReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val actionType = intent.getStringExtra(LocationUpdatesService.ACTION_BROADCAST_TYPE)

            val location =
                intent.getParcelableExtra<Location>(LocationUpdatesService.EXTRA_LOCATION)
            val locationMap = HashMap<String, Any>()
            if (location != null) {
                locationMap["latitude"] = location.latitude
                locationMap["longitude"] = location.longitude
                locationMap["altitude"] = location.altitude
                locationMap["accuracy"] = location.accuracy.toDouble()
                locationMap["bearing"] = location.bearing.toDouble()
                locationMap["speed"] = location.speed.toDouble()
                locationMap["time"] = location.time.toDouble()
                locationMap["is_mock"] = location.isFromMockProvider
            }

            when (actionType) {
                LocationUpdatesService.ACTION_BROADCAST_LOCATION -> channel.invokeMethod(
                    "location",
                    locationMap,
                    null
                )

                LocationUpdatesService.ACTION_NOTIFICATION_ACTIONED -> {
                    val result = HashMap<String, Any>()
                    result["ARG_LOCATION"] = locationMap
                    result["ARG_CALLBACK"] =
                        intent.getLongExtra(LocationUpdatesService.EXTRA_ACTION_CALLBACK, 0L)
                    channel.invokeMethod("notificationAction", result, null)
                }
            }
        }
    }

    /**
     * Handle the response from a permission request
     * @return true if the result has been handled.
     */
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ): Boolean {
        Log.i(TAG, "onRequestPermissionResult")
        if (requestCode == REQUEST_PERMISSIONS_REQUEST_CODE) {
            when {
                grantResults.isEmpty() -> Log.i(
                    TAG,
                    "User interaction was cancelled."
                )

                grantResults[0] == PackageManager.PERMISSION_GRANTED -> service?.requestLocationUpdates()
                else -> Toast.makeText(
                    context,
                    R.string.permission_denied_explanation,
                    Toast.LENGTH_LONG
                ).show()
            }
        }
        return true
    }
}
