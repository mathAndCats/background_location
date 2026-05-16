package com.almoullim.background_location

import android.Manifest
import io.flutter.plugin.common.MethodChannel

import io.flutter.plugin.common.BinaryMessenger
import android.app.Activity
import android.app.ActivityManager
import android.content.*
import android.content.pm.PackageManager
import android.location.Location
import android.os.IBinder
import android.util.Log
import androidx.annotation.NonNull
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding

import io.flutter.plugin.common.MethodCall


class BackgroundLocationService: MethodChannel.MethodCallHandler {
    companion object {
        const val METHOD_CHANNEL_NAME = "${BackgroundLocationPlugin.PLUGIN_ID}/methods"

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

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            context?.let { LifecycleLogger.log(it, "ServiceConnection: onServiceConnected - bound=$bound") }
            bound = true
            val binder = service as LocationUpdatesService.LocalBinder
            this@BackgroundLocationService.service = binder.service
            context?.let { LifecycleLogger.log(it, "ServiceConnection: Service bound successfully, requesting location") }
            requestLocation()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            context?.let { LifecycleLogger.log(it, "ServiceConnection: onServiceDisconnected") }
            service = null
        }
    }

    private fun flushBufferedLocations() {
        val locations = service?.getBufferedLocations() ?: return
        if (locations.isNotEmpty()) {
            context?.let { LifecycleLogger.log(it, "flushBufferedLocations: Sending ${locations.size} buffered locations to Flutter") }
            channel.invokeMethod("buffered_locations", locations)
            service?.clearBufferedLocations()
        }
    }

    fun onAttachedToEngine(@NonNull context: Context, @NonNull messenger: BinaryMessenger) {
        LifecycleLogger.log(context, "onAttachedToEngine: Starting - isAttached=$isAttached, bound=$bound")
        this.context = context
        isAttached = true
        channel = MethodChannel(messenger, METHOD_CHANNEL_NAME)
        channel.setMethodCallHandler(this)

        receiver = MyReceiver()

        LocalBroadcastManager.getInstance(context).registerReceiver(receiver!!,
            IntentFilter(LocationUpdatesService.ACTION_BROADCAST))

        // Always try to rebind
        if (!bound) {
            LifecycleLogger.log(context, "onAttachedToEngine: Attempting to rebind to service")
            val intent = Intent(context, LocationUpdatesService::class.java)
            val result = context.bindService(intent, serviceConnection, 0)
            LifecycleLogger.log(context, "onAttachedToEngine: bindService returned $result")
        }
    }

    // NEW METHOD: Rebind to an already-running service
    private fun rebindToExistingService() {
        context?.let { LifecycleLogger.log(it, "rebindToExistingService: Attempting to rebind to existing service") }
        val intent = Intent(context, LocationUpdatesService::class.java)
        context!!.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    fun onDetachedFromEngine() {
        context?.let { LifecycleLogger.log(it, "onDetachedFromEngine: Detaching - bound=$bound") }

        // Unregister the receiver
        try {
            receiver?.let {
                context?.let { ctx ->
                    LocalBroadcastManager.getInstance(ctx).unregisterReceiver(it)
                }
            }
        } catch (e: IllegalArgumentException) {
            // Already unregistered
        }

        channel.setMethodCallHandler(null)
        context = null
        isAttached = false
    }

    fun setActivity(binding: ActivityPluginBinding?) {
        context?.let { LifecycleLogger.log(it, "setActivity: binding=${binding != null}, bound=$bound") }
        this.activity = binding?.activity

        if(this.activity != null){
            context?.let { LifecycleLogger.log(it, "setActivity: Activity attached, isServiceRunning=${isLocationServiceRunning()}") }
            // Rebind if service is running
            if (isLocationServiceRunning() && !bound) {
                Log.d(BackgroundLocationPlugin.TAG, "Activity attached, rebinding to existing service")
                context?.let { LifecycleLogger.log(it, "setActivity: Rebinding to existing service") }
                rebindToExistingService()
            }
        } else {
            // Activity is null - this is FINE for a foreground service
            // Just log it, don't stop the service
            context?.let { LifecycleLogger.log(it, "setActivity: Activity detached, service continues running in background") }
            // DO NOT call stopLocationService() here
        }
    }

    private var distanceFilter: Double = 0.0
    private fun startLocationService(distanceFilter: Double?, forceLocationManager : Boolean?): Int {
        context?.let { LifecycleLogger.log(it, "startLocationService: Called with distanceFilter=$distanceFilter, isServiceRunning=${isLocationServiceRunning()}, bound=$bound") }

        if (distanceFilter != null) {
            this.distanceFilter = distanceFilter
        }

        // NEW: If already running, just rebind instead of starting new
        if (isLocationServiceRunning()) {
            Log.d(BackgroundLocationPlugin.TAG, "Service already running, rebinding instead of starting")
            context?.let { LifecycleLogger.log(it, "startLocationService: Service already running, rebinding instead of starting") }
            if (!bound) {
                rebindToExistingService()
            }
            return 0
        }

        if (!checkPermissions()) {
            context?.let { LifecycleLogger.log(it, "startLocationService: Permissions not granted; Dart is responsible for requesting") }
            return 0
        }

        context?.let { LifecycleLogger.log(it, "startLocationService: Permissions granted, starting service") }
        reallyStartLocationService()
        return 0
    }

    private fun reallyStartLocationService() {
        val ctx = context ?: return
        LifecycleLogger.log(ctx, "reallyStartLocationService: Starting service with distanceFilter=$distanceFilter, bound=$bound")

        /*receiver?.let {
            LocalBroadcastManager.getInstance(ctx).registerReceiver(it,
                IntentFilter(LocationUpdatesService.ACTION_BROADCAST))
        } */

        if (!bound) {
            val intent = Intent(ctx, LocationUpdatesService::class.java)
            intent.putExtra("distance_filter", this.distanceFilter)
            intent.putExtra("force_location_manager", false)

            ContextCompat.startForegroundService(ctx, intent)
            LifecycleLogger.log(ctx, "reallyStartLocationService: startForegroundService called")

            ctx.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
            LifecycleLogger.log(ctx, "reallyStartLocationService: bindService called")
        }
    }

    private fun isLocationServiceRunning(): Boolean {
        // If we're bound, service is definitely running
        if (bound && service != null) {
            return true
        }
        
        // Fallback to ActivityManager check
        val ctx = context ?: return false
        val manager = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        @Suppress("DEPRECATION")
        for (serviceInfo in manager.getRunningServices(Integer.MAX_VALUE)) {
            if (LocationUpdatesService::class.java.name == serviceInfo.service.className) {
                return serviceInfo.foreground
            }
        }
        return false
    }

    private fun stopLocationService(): Int {
        context?.let { LifecycleLogger.log(it, "stopLocationService: Stopping service, bound=$bound") }

        service?.removeLocationUpdates()

        if (bound) {
            context!!.unbindService(serviceConnection)
            bound = false
            context?.let { LifecycleLogger.log(it, "stopLocationService: Service unbound") }
        }

        return 0
    }

    private fun setAndroidNotification(title: String?, message: String?, icon: String?):Int{
        context?.let { LifecycleLogger.log(it, "setAndroidNotification: title=$title, message=$message, icon=$icon") }

        if (title != null) LocationUpdatesService.NOTIFICATION_TITLE = title
        if (message != null) LocationUpdatesService.NOTIFICATION_MESSAGE = message
        if (icon != null) LocationUpdatesService.NOTIFICATION_ICON = icon

        if (service != null) {
            service?.updateNotification()
            context?.let { LifecycleLogger.log(it, "setAndroidNotification: Notification updated") }
        } else {
            context?.let { LifecycleLogger.log(it, "setAndroidNotification: Service is null, cannot update notification") }
        }

        return 0
    }

    private fun setConfiguration(timeInterval: Long?):Int {
        context?.let { LifecycleLogger.log(it, "setConfiguration: timeInterval=$timeInterval") }

        if (timeInterval != null) {
            LocationUpdatesService.UPDATE_INTERVAL_IN_MILLISECONDS = timeInterval
            LocationUpdatesService.FASTEST_UPDATE_INTERVAL_IN_MILLISECONDS = timeInterval/2
        }

        service?.createLocationRequest(0.0);
        service?.requestLocationUpdates();

        return 0
    }

    override fun onMethodCall(@NonNull call: MethodCall, @NonNull result: MethodChannel.Result) {
        context?.let { LifecycleLogger.log(it, "onMethodCall: method=${call.method}") }

        when (call.method) {
            "stop_location_service" -> result.success(stopLocationService())
            "start_location_service" -> result.success(startLocationService(call.argument("distance_filter"), call.argument("force_location_manager")))
            "is_service_running" -> {
                val running = isLocationServiceRunning()
                context?.let { LifecycleLogger.log(it, "onMethodCall: is_service_running=$running") }
                result.success(running)
            }
            "set_android_notification" -> result.success(setAndroidNotification(call.argument("title"),call.argument("message"),call.argument("icon")))
            "set_configuration" -> result.success(setConfiguration(call.argument<String>("interval")?.toLongOrNull()))

            "set_recording_state" -> {
                val isRecording = call.argument<Boolean>("is_recording") ?: false
                if (service != null) {
                    service?.setRecording(isRecording)
                } else {
                    // Fallback if not bound yet - can only set persisted state, won't reset buffer tracking
                    LocationUpdatesService.setRecordingState(context!!, isRecording)
                }
                context?.let { LifecycleLogger.log(it, "onMethodCall: set_recording_state=$isRecording") }
                result.success(0)
            }
            "get_buffered_locations" -> {
                val locations = service?.getBufferedLocations() ?: run {
                    // Service might not be bound, read directly from DB
                    val buffer = LocationBuffer(context!!)
                    buffer.getAll()
                }
                context?.let { LifecycleLogger.log(it, "onMethodCall: get_buffered_locations count=${locations.size}") }
                result.success(locations)
            }
            "clear_buffered_locations" -> {
                if (service != null) {
                    service?.clearBufferedLocations()
                } else {
                    // Service might not be bound, clear directly
                    val buffer = LocationBuffer(context!!)
                    buffer.clear()
                }
                context?.let { LifecycleLogger.log(it, "onMethodCall: clear_buffered_locations") }
                result.success(0)
            }
            "get_buffered_location_count" -> {
                val count = service?.getBufferedLocationCount() ?: run {
                    val buffer = LocationBuffer(context!!)
                    buffer.count()
                }
                context?.let { LifecycleLogger.log(it, "onMethodCall: get_buffered_location_count=$count") }
                result.success(count)
            }
            
            else -> result.notImplemented()
        }
    }

    /**
     * Requests a location update. Caller (Dart) is responsible for ensuring
     * permission is granted before this is reached — if it's not, we fail
     * gracefully without trying to prompt the user.
     */
    private fun requestLocation() {
        context?.let { LifecycleLogger.log(it, "requestLocation: checkPermissions=${checkPermissions()}") }

        if (!checkPermissions()) {
            return
        }
        service?.requestLocationUpdates()
    }

    /**
     * Checks the current permission for `ACCESS_FINE_LOCATION`.
     */
    private fun checkPermissions(): Boolean {
        val ctx = context ?: return false
        return PackageManager.PERMISSION_GRANTED == ActivityCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private inner class MyReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val location = intent.getParcelableExtra<Location>(LocationUpdatesService.EXTRA_LOCATION)
            if (location != null) {
                LifecycleLogger.log(context, "MyReceiver: Location received - lat=${location.latitude}, lon=${location.longitude}, accuracy=${location.accuracy}")

                val locationMap = HashMap<String, Any>()
                locationMap["latitude"] = location.latitude
                locationMap["longitude"] = location.longitude
                locationMap["altitude"] = location.altitude
                locationMap["accuracy"] = location.accuracy.toDouble()
                locationMap["bearing"] = location.bearing.toDouble()
                locationMap["speed"] = location.speed.toDouble()
                locationMap["time"] = location.time.toDouble()
                locationMap["is_mock"] = location.isFromMockProvider
                channel.invokeMethod("location", locationMap, null)
            } else {
                LifecycleLogger.log(context, "MyReceiver: Received broadcast but location is null")
            }
        }
    }

}