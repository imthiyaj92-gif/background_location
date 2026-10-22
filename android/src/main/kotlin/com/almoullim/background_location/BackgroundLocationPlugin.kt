package com.almoullim.background_location

import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding

class BackgroundLocationPlugin : FlutterPlugin, ActivityAware {
    private var activityBinding: ActivityPluginBinding? = null
    private var alarmWindow: AlarmWindowController? = null
    private val alarmIntentListener = io.flutter.plugin.common.PluginRegistry.NewIntentListener { intent ->
        alarmWindow?.onIntent(intent)
        false // Flutter must also receive the deep link.
    }
    // Remove companion object with v1 embedding code
    // (Delete the entire "companion object" block below)

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        BackgroundLocationService.getInstance().onAttachedToEngine(binding.applicationContext, binding.binaryMessenger)
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        BackgroundLocationService.getInstance().onDetachedFromEngine()
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activityBinding = binding
        alarmWindow?.dispose()
        alarmWindow = AlarmWindowController(binding.activity)
        binding.addOnNewIntentListener(alarmIntentListener)
        val service = BackgroundLocationService.getInstance()
        service.setActivity(binding)
        binding.addRequestPermissionsResultListener(service)
    }

    override fun onDetachedFromActivityForConfigChanges() {
        this.onDetachedFromActivity()
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        this.onAttachedToActivity(binding)
    }

    override fun onDetachedFromActivity() {
        activityBinding?.removeOnNewIntentListener(alarmIntentListener)
        activityBinding?.removeRequestPermissionsResultListener(BackgroundLocationService.getInstance())
        activityBinding = null
        alarmWindow?.dispose()
        alarmWindow = null
        BackgroundLocationService.getInstance().setActivity(null)
    }

}
