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
import android.widget.Toast
import androidx.annotation.NonNull
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding

import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.PluginRegistry


class BackgroundLocationService: MethodChannel.MethodCallHandler, PluginRegistry.RequestPermissionsResultListener {
    companion object {
        const val METHOD_CHANNEL_NAME = "${BackgroundLocationPlugin.PLUGIN_ID}/methods"
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

    private fun flushBufferedLocations() {
        val locations = service?.getBufferedLocations() ?: return
        if (locations.isNotEmpty()) {
            channel.invokeMethod("buffered_locations", locations)
            service?.clearBufferedLocations()
        }
    }

    fun onAttachedToEngine(@NonNull context: Context, @NonNull messenger: BinaryMessenger) {
        this.context = context
        isAttached = true
        channel = MethodChannel(messenger, METHOD_CHANNEL_NAME)
        channel.setMethodCallHandler(this)

        receiver = MyReceiver()

        LocalBroadcastManager.getInstance(context).registerReceiver(receiver!!,
            IntentFilter(LocationUpdatesService.ACTION_BROADCAST))

        // Always try to rebind
        if (!bound) {
            val intent = Intent(context, LocationUpdatesService::class.java)
            val result = context.bindService(intent, serviceConnection, 0)
        }
    }

    // NEW METHOD: Rebind to an already-running service
    private fun rebindToExistingService() {
        val intent = Intent(context, LocationUpdatesService::class.java)
        context!!.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    fun onDetachedFromEngine() {

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
        this.activity = binding?.activity

        if(this.activity != null){
            // Rebind if service is running
            if (isLocationServiceRunning() && !bound) {
                rebindToExistingService()
            } else if (context != null && Utils.requestingLocationUpdates(context!!)) {
                if (!checkPermissions()) {
                    requestPermissions()
                }
            }
        } else {
            // Activity is null - this is FINE for a foreground service.
            // DO NOT call stopLocationService() here.
        }
    }

    private var distanceFilter: Double = 0.0
    private fun startLocationService(distanceFilter: Double?, forceLocationManager : Boolean?): Int {

        if (distanceFilter != null) {
            this.distanceFilter = distanceFilter
        }

        // NEW: If already running, just rebind instead of starting new
        if (isLocationServiceRunning()) {
            if (!bound) {
                rebindToExistingService()
            }
            return 0
        }

        if (!checkPermissions()) {
            requestPermissions()
        } else {
            reallyStartLocationService()
        }
        return 0
    }

    private fun reallyStartLocationService() {
        val ctx = context ?: return

        /*receiver?.let {
            LocalBroadcastManager.getInstance(ctx).registerReceiver(it,
                IntentFilter(LocationUpdatesService.ACTION_BROADCAST))
        } */

        if (!bound) {
            val intent = Intent(ctx, LocationUpdatesService::class.java)
            intent.putExtra("distance_filter", this.distanceFilter)
            intent.putExtra("force_location_manager", false)

            ContextCompat.startForegroundService(ctx, intent)

            ctx.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
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

        service?.removeLocationUpdates()

        if (bound) {
            context!!.unbindService(serviceConnection)
            bound = false
        }

        return 0
    }

    private fun setAndroidNotification(title: String?, message: String?, icon: String?):Int{

        if (title != null) LocationUpdatesService.NOTIFICATION_TITLE = title
        if (message != null) LocationUpdatesService.NOTIFICATION_MESSAGE = message
        if (icon != null) LocationUpdatesService.NOTIFICATION_ICON = icon

        if (service != null) {
            service?.updateNotification()
        } else {
        }

        return 0
    }

    private fun setConfiguration(timeInterval: Long?):Int {

        if (timeInterval != null) {
            LocationUpdatesService.UPDATE_INTERVAL_IN_MILLISECONDS = timeInterval
            LocationUpdatesService.FASTEST_UPDATE_INTERVAL_IN_MILLISECONDS = timeInterval/2
        }

        service?.createLocationRequest(0.0);
        service?.requestLocationUpdates();

        return 0
    }

    override fun onMethodCall(@NonNull call: MethodCall, @NonNull result: MethodChannel.Result) {

        when (call.method) {
            "stop_location_service" -> result.success(stopLocationService())
            "start_location_service" -> result.success(startLocationService(call.argument("distance_filter"), call.argument("force_location_manager")))
            "is_service_running" -> {
                val running = isLocationServiceRunning()
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
                result.success(0)
            }
            "get_buffered_locations" -> {
                val locations = service?.getBufferedLocations() ?: run {
                    // Service might not be bound, read directly from DB
                    val buffer = LocationBuffer(context!!)
                    buffer.getAll()
                }
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
                result.success(0)
            }
            "get_buffered_location_count" -> {
                val count = service?.getBufferedLocationCount() ?: run {
                    val buffer = LocationBuffer(context!!)
                    buffer.count()
                }
                result.success(count)
            }
            
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
        val ctx = context ?: return false
        return PackageManager.PERMISSION_GRANTED == ActivityCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
    }


    /**
     * Requests permission for location.
     * Depending on the current activity, displays a rationale for the request.
     */
    private fun requestPermissions() {

        if(activity == null) {
            return
        }

        val shouldProvideRationale = ActivityCompat.shouldShowRequestPermissionRationale(activity!!, Manifest.permission.ACCESS_FINE_LOCATION)
        if (shouldProvideRationale) {
            Toast.makeText(context, R.string.permission_rationale, Toast.LENGTH_LONG).show()

        } else {
            ActivityCompat.requestPermissions(activity!!,
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ),
                REQUEST_PERMISSIONS_REQUEST_CODE)
        }
    }

    private inner class MyReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val location = intent.getParcelableExtra<Location>(LocationUpdatesService.EXTRA_LOCATION)
            if (location != null) {

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
            }
        }
    }

    /**
     * Handle the response from a permission request
     * @return true if the result has been handled.
     */
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray): Boolean{

        if (requestCode == REQUEST_PERMISSIONS_REQUEST_CODE) {
            when {
                grantResults!!.isEmpty() -> {
                    // User cancelled the permission dialog — no-op.
                }
                grantResults[0] == PackageManager.PERMISSION_GRANTED -> {
                    reallyStartLocationService()
                }
                else -> {
                    Toast.makeText(context, R.string.permission_denied_explanation, Toast.LENGTH_LONG).show()
                }
            }
        }
        return true
    }
}