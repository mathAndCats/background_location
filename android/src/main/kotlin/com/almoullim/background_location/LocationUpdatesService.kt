package com.almoullim.background_location

import android.annotation.SuppressLint
import android.app.*
import android.location.*
import android.location.LocationListener
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.gms.location.*
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.common.*

class LocationUpdatesService : Service() {

    private var forceLocationManager: Boolean = false
    private var locationBuffer: LocationBuffer? = null 

    override fun onBind(intent: Intent?): IBinder {
        val distanceFilter = intent?.getDoubleExtra("distance_filter", 0.0) ?: 0.0
        if (intent != null) {
            forceLocationManager = intent.getBooleanExtra("force_location_manager", false)
        }

        // Build ONLY if nothing has built it yet — same guard as onStartCommand().
        //
        // Two things are true at once here. It has to stay possible to build the request from
        // onBind, because requestLocationUpdates() dereferences mLocationRequest with `!!` and
        // a service created by bindService(BIND_AUTO_CREATE) alone never sees onStartCommand:
        // onServiceConnected -> requestLocation() -> requestLocationUpdates() would then NPE.
        // But it must NOT rebuild unconditionally, because only one of the three bind sites
        // (reallyStartLocationService) puts the extras on its Intent — the two rebind paths
        // pass a bare one, and extras don't participate in Intent.filterEquals(), so onBind
        // was silently resetting a good request to distanceFilter=0.0 on every re-attach.
        // Guarding keeps the null-safety net without letting an empty Intent clobber config.
        if (mLocationRequest == null) {
            createLocationRequest(distanceFilter)
        }
        return mBinder
    }

    private val mBinder = LocalBinder()
    private var mNotificationManager: NotificationManager? = null
    private var mLocationRequest: LocationRequest? = null
    private var mFusedLocationClient: FusedLocationProviderClient? = null
    private var mLocationManager: LocationManager? = null
    private var mFusedLocationCallback: LocationCallback? = null
    private var mLocationManagerCallback: LocationListener? = null
    private var mLocation: Location? = null
    private var isGoogleApiAvailable: Boolean = false
    private var isStarted: Boolean = false

    companion object {
        var NOTIFICATION_TITLE = "Background service is running"
        var NOTIFICATION_MESSAGE = "Background service is running"
        // A bare drawable name, NOT "@mipmap/..." reference syntax — this is fed to
        // Resources.getIdentifier(), which wants a name. It must also point at a white
        // silhouette on transparency: the framework alpha-masks a notification small icon
        // (API 21+), discarding the colour channels and re-tinting the shape, so a
        // full-colour launcher icon renders as a solid blob. The previous default,
        // "@mipmap/ic_launcher", was wrong on both counts and resolved to 0.
        var NOTIFICATION_ICON = "ic_notification"

        private const val PACKAGE_NAME = "com.google.android.gms.location.sample.locationupdatesforegroundservice"
        private val TAG = LocationUpdatesService::class.java.simpleName
        private const val CHANNEL_ID = "BackgroundLocation"
        internal const val ACTION_BROADCAST = "$PACKAGE_NAME.broadcast"
        internal const val EXTRA_LOCATION = "$PACKAGE_NAME.location"
        private const val EXTRA_STARTED_FROM_NOTIFICATION = "$PACKAGE_NAME.started_from_notification"
        var UPDATE_INTERVAL_IN_MILLISECONDS: Long = 1000
        var FASTEST_UPDATE_INTERVAL_IN_MILLISECONDS = UPDATE_INTERVAL_IN_MILLISECONDS / 2
        private const val NOTIFICATION_ID = 12345678
        private lateinit var broadcastReceiver: BroadcastReceiver
        private const val STOP_SERVICE = "stop_service"

        // NEW: Add these for persisted recording state
        private const val PREFS_NAME = "location_service_prefs"
        private const val KEY_IS_RECORDING = "is_recording"

        // Everything Dart configures — the notification copy via setAndroidNotification(),
        // the update interval via setAndroidConfiguration() — arrives by method channel and
        // lives ONLY in this process's statics above. The system re-creates a killed service
        // WITHOUT a Flutter engine, so nothing re-applies any of it and each static silently
        // reverts to its default: the user's notification becomes the placeholder, and the
        // interval snaps from (say) 30s back to 1s, pinning the GPS at PRIORITY_HIGH_ACCURACY
        // with no app running to correct it. START_REDELIVER_INTENT does not help — none of
        // this travels in the intent. So persist it next to the recording flag, and restore
        // it in onCreate() before the notification is built or the location request is made.
        private const val KEY_NOTIFICATION_TITLE = "notification_title"
        private const val KEY_NOTIFICATION_MESSAGE = "notification_message"
        private const val KEY_NOTIFICATION_ICON = "notification_icon"
        private const val KEY_UPDATE_INTERVAL = "update_interval_ms"

        fun setRecordingState(context: Context, isRecording: Boolean) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_IS_RECORDING, isRecording)
                .apply()
        }
        
        fun getRecordingState(context: Context): Boolean {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_IS_RECORDING, false)
        }

        /**
         * Snapshot every Dart-configured static. Safe to call from any of the setters —
         * they all write the same statics, so writing all of them each time keeps the
         * persisted set consistent no matter which entry point fired.
         */
        fun saveServiceConfig(context: Context) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_NOTIFICATION_TITLE, NOTIFICATION_TITLE)
                .putString(KEY_NOTIFICATION_MESSAGE, NOTIFICATION_MESSAGE)
                .putString(KEY_NOTIFICATION_ICON, NOTIFICATION_ICON)
                .putLong(KEY_UPDATE_INTERVAL, UPDATE_INTERVAL_IN_MILLISECONDS)
                .apply()
        }

        /**
         * Restore the persisted config into the statics above.
         *
         * Each default is the *current* static value, so a first run with nothing persisted
         * yet is a no-op rather than clobbering config Dart has already set in this process —
         * the setters and service start have no guaranteed ordering.
         */
        fun loadServiceConfig(context: Context) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            NOTIFICATION_TITLE =
                prefs.getString(KEY_NOTIFICATION_TITLE, NOTIFICATION_TITLE) ?: NOTIFICATION_TITLE
            NOTIFICATION_MESSAGE =
                prefs.getString(KEY_NOTIFICATION_MESSAGE, NOTIFICATION_MESSAGE) ?: NOTIFICATION_MESSAGE
            NOTIFICATION_ICON =
                prefs.getString(KEY_NOTIFICATION_ICON, NOTIFICATION_ICON) ?: NOTIFICATION_ICON
            // Derived, not stored: setConfiguration() always sets fastest to interval/2, and
            // so does the static initialiser. Recomputing keeps the two in step by
            // construction rather than by a second key that could drift out of sync.
            UPDATE_INTERVAL_IN_MILLISECONDS =
                prefs.getLong(KEY_UPDATE_INTERVAL, UPDATE_INTERVAL_IN_MILLISECONDS)
            FASTEST_UPDATE_INTERVAL_IN_MILLISECONDS = UPDATE_INTERVAL_IN_MILLISECONDS / 2
        }
    }

    fun setRecording(isRecording: Boolean) {
        setRecordingState(this, isRecording)
        if (isRecording) {
            lastBufferedLocation = null
            lastBufferedTime = 0
        }
    }

    private fun isRecording(): Boolean {
        return getRecordingState(this)
    }

    /**
     * Resolve [NOTIFICATION_ICON] to a drawable id, never returning 0.
     *
     * getIdentifier() yields 0 for a name it can't find, and startForeground() with an
     * invalid small icon can fail to post — which, inside Android's ~5s foreground-service
     * window, surfaces as the *uncatchable* ForegroundServiceDidNotStartInTimeException.
     * So a bad icon name must degrade to a visible-but-wrong icon, never to a crash.
     * mipmap is tried second, and a "@mipmap/foo"-style value tolerated, because callers
     * following the old plugin docs will have passed exactly that.
     */
    private fun resolveSmallIconId(): Int {
        val name = NOTIFICATION_ICON.substringAfterLast('/')
        resources.getIdentifier(name, "drawable", packageName).let { if (it != 0) return it }
        resources.getIdentifier(name, "mipmap", packageName).let { if (it != 0) return it }
        LifecycleLogger.log(
            this,
            "resolveSmallIconId: '$NOTIFICATION_ICON' did not resolve; using the app icon"
        )
        return applicationInfo.icon
    }

    private val notification: NotificationCompat.Builder
        @SuppressLint("UnspecifiedImmutableFlag")
        get() {

            LifecycleLogger.log(this, "notification getter: NOTIFICATION_TITLE=$NOTIFICATION_TITLE")
            LifecycleLogger.log(this, "notification getter: NOTIFICATION_MESSAGE=$NOTIFICATION_MESSAGE")
            LifecycleLogger.log(this, "notification getter: CHANNEL_ID=$CHANNEL_ID")

            val intent = Intent(this, getMainActivityClass(this))
            intent.putExtra(EXTRA_STARTED_FROM_NOTIFICATION, true)
            intent.action = "Localisation"
            //intent.setClass(this, getMainActivityClass(this))
            val pendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.getActivity(this, 1, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            } else {
                PendingIntent.getActivity(this, 1, intent, PendingIntent.FLAG_UPDATE_CURRENT)
            }


            val builder = NotificationCompat.Builder(this, "BackgroundLocation")
                    .setContentTitle(NOTIFICATION_TITLE)
                    .setOngoing(true)
                    .setSound(null)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setSmallIcon(resolveSmallIconId())
                    .setWhen(System.currentTimeMillis())
                    .setStyle(NotificationCompat.BigTextStyle().bigText(NOTIFICATION_MESSAGE))
                    .setContentIntent(pendingIntent)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                builder.setChannelId(CHANNEL_ID)
            }

            return builder
        }

    private var mServiceHandler: Handler? = null

    override fun onCreate() {
        // Promote to the foreground FIRST — before any location / Play-services / DB setup.
        // startForegroundService() (BackgroundLocationService.reallyStartLocationService) has
        // already started Android's ~5s deadline to call startForeground(); doing it here as
        // the very first thing keeps us well inside that window even when the main thread is
        // busy (cold start, heavy UI). The channel must exist before the notification posts.
        mNotificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Application Name"
            val mChannel = NotificationChannel(CHANNEL_ID, name, NotificationManager.IMPORTANCE_DEFAULT)
            mChannel.setSound(null, null)
            mNotificationManager!!.createNotificationChannel(mChannel)
        }
        // Restore the persisted config FIRST — ahead of both the notification below and the
        // createLocationRequest() that onStartCommand() will do. When the system re-creates
        // a killed service there is no Flutter engine to re-call the setters, so without
        // this the statics still hold their defaults.
        loadServiceConfig(this)
        updateNotification() // startForeground() — satisfy the FGS obligation immediately

        locationBuffer = LocationBuffer(this)

        val googleAPIAvailability = GoogleApiAvailability.getInstance()
            .isGooglePlayServicesAvailable(applicationContext)
        
        isGoogleApiAvailable = googleAPIAvailability == ConnectionResult.SUCCESS
        

        if (isGoogleApiAvailable && !this.forceLocationManager) {
            mFusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
            
            mFusedLocationCallback = object : LocationCallback() {
                override fun onLocationResult(locationResult: LocationResult) {
                    // Smart cast to 'Location' is impossible, because 'locationResult.lastLocation'
                    // is a property that has open or custom getter
                    val newLastLocation = locationResult.lastLocation
                    if (newLastLocation is Location) {
                        super.onLocationResult(locationResult)
                        onNewLocation(newLastLocation)
                    }
                }
            }
        } else {
            mLocationManager = getSystemService(LOCATION_SERVICE) as LocationManager?

            mLocationManagerCallback = LocationListener { location ->
                onNewLocation(location)
            }
        }

        getLastLocation()

        val handlerThread = HandlerThread(TAG)
        handlerThread.start()
        mServiceHandler = Handler(handlerThread.looper)

        broadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == "stop_service") {
                    removeLocationUpdates()
                }
            }
        }

        val filter = IntentFilter()
        filter.addAction(STOP_SERVICE)
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(broadcastReceiver, filter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(broadcastReceiver, filter)
        }
    }

    private var lastBufferedLocation: Location? = null
    private var lastBufferedTime: Long = 0

    private fun onNewLocation(location: Location) {
        try {
            LifecycleLogger.log(this, "LocationUpdatesService - received location $location")
            mLocation = location

            val recording = isRecording()
            LifecycleLogger.log(this, "LocationUpdatesService - isRecording=$recording")

            if (recording) {
                val shouldBuf = shouldBuffer(location)
                LifecycleLogger.log(this, "LocationUpdatesService - shouldBuffer=$shouldBuf")

                if (shouldBuf) {
                    locationBuffer?.insert(location)
                    lastBufferedLocation = location
                    lastBufferedTime = location.time
                    LifecycleLogger.log(this, "LocationUpdatesService - buffered location, count=${locationBuffer?.count()}")
                } else {
                    LifecycleLogger.log(this, "LocationUpdatesService - skipped buffering")
                }
            }

            val intent = Intent(ACTION_BROADCAST)
            intent.putExtra(EXTRA_LOCATION, location)
            LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(intent)
        } catch (e: Exception) {
            LifecycleLogger.log(this, "LocationUpdatesService - ERROR in onNewLocation: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun shouldBuffer(location: Location): Boolean {
        val last = lastBufferedLocation
        if (last == null) {
            LifecycleLogger.log(this, "shouldBuffer - no last location, returning true")
            return true
        }

        val timeDelta = location.time - lastBufferedTime
        val distance = last.distanceTo(location)
        LifecycleLogger.log(this, "shouldBuffer - timeDelta=${timeDelta}ms, distance=${distance}m")

        if (distance < 3f || timeDelta < 10000) {
            LifecycleLogger.log(this, "shouldBuffer - returning false (too close)")
            return false
        }

        return true
    }
    
    fun getBufferedLocations(): List<Map<String, Any>> {
        return locationBuffer?.getAll() ?: emptyList()
    }
    
    fun clearBufferedLocations() {
        locationBuffer?.clear()
    }
    
    fun getBufferedLocationCount(): Int {
        return locationBuffer?.count() ?: 0
    }

    fun requestLocationUpdates() {
        Utils.setRequestingLocationUpdates(this, true)
        try {
            if (isGoogleApiAvailable && !forceLocationManager) {
                mFusedLocationClient?.removeLocationUpdates(mFusedLocationCallback!!)
                mFusedLocationClient?.requestLocationUpdates(mLocationRequest!!,
                    mFusedLocationCallback!!, Looper.myLooper())
            } else {
                mLocationManager?.removeUpdates(mLocationManagerCallback!!)
                mLocationManager?.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER, 0L, 0f, mLocationManagerCallback!!)
            }
        } catch (unlikely: SecurityException) {
            Utils.setRequestingLocationUpdates(this, false)
        }
    }

    fun updateNotification() {

        LifecycleLogger.log(this, "updateNotification: isStarted=$isStarted")

        if (!isStarted) {
            isStarted = true
            LifecycleLogger.log(this, "updateNotification: calling startForeground with NOTIFICATION_ID=$NOTIFICATION_ID")

            // ALWAYS promote — never gate this on isAppInForeground(). Once
            // startForegroundService() has been called, Android requires startForeground()
            // within ~5s or it kills us with ForegroundServiceDidNotStartInTimeException (an
            // async RemoteServiceException that no try/catch can intercept). The
            // background-start case is guarded at the START site (reallyStartLocationService),
            // so reaching here means the promote is permitted. The old isAppInForeground()
            // gate both CAUSED this crash and was unreliable — it false-negatives for
            // genuinely-foreground apps because runningAppProcesses importance is racy and the
            // check demanded an exact == IMPORTANCE_FOREGROUND.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                startForeground(NOTIFICATION_ID, notification.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else {
                startForeground(NOTIFICATION_ID, notification.build())
            }

            LifecycleLogger.log(this, "updateNotification: startForeground called")

        } else {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(NOTIFICATION_ID, notification.build())
        }
    }

    fun isAppInForeground(context: Context): Boolean {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val appProcesses = activityManager.runningAppProcesses ?: return false
        val packageName = context.packageName
        for (appProcess in appProcesses) {
            if (appProcess.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND && appProcess.processName == packageName) {
                return true
            }
        }
        return false
    }

    fun removeLocationUpdates() {
        if (isGoogleApiAvailable && !forceLocationManager) {
            mFusedLocationClient?.removeLocationUpdates(mFusedLocationCallback!!)
        } else {
            mLocationManager?.removeUpdates(mLocationManagerCallback!!)
        }
        stopForeground(true)
        stopSelf()
    }


    private fun getLastLocation() {
        try {
            if(isGoogleApiAvailable && !this.forceLocationManager) {
                mFusedLocationClient!!.lastLocation
                        .addOnCompleteListener { task ->
                            if (task.isSuccessful && task.result != null) {
                                mLocation = task.result
                            }
                        }
            } else {
                mLocation = mLocationManager!!.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            }
        } catch (unlikely: SecurityException) {
        }
    }

    fun createLocationRequest(distanceFilter: Double) {
        mLocationRequest = LocationRequest()
        mLocationRequest!!.interval = UPDATE_INTERVAL_IN_MILLISECONDS
        mLocationRequest!!.fastestInterval = FASTEST_UPDATE_INTERVAL_IN_MILLISECONDS
        mLocationRequest!!.priority = LocationRequest.PRIORITY_HIGH_ACCURACY
        mLocationRequest!!.smallestDisplacement = distanceFilter.toFloat()
    }


    inner class LocalBinder : Binder() {
        internal val service: LocationUpdatesService
            get() = this@LocationUpdatesService
    }


    override fun onDestroy() {
        super.onDestroy()
        isStarted = false
        unregisterReceiver(broadcastReceiver)
        try {
            if (isGoogleApiAvailable && !this.forceLocationManager) {
                mFusedLocationClient!!.removeLocationUpdates(mFusedLocationCallback!!)
            } else {
                mLocationManager!!.removeUpdates(mLocationManagerCallback!!)
            }

            Utils.setRequestingLocationUpdates(this, false)
            mNotificationManager?.cancel(NOTIFICATION_ID)
        } catch (unlikely: SecurityException) {
            Utils.setRequestingLocationUpdates(this, true)
        }
        locationBuffer?.close()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        LifecycleLogger.log(this, "onStartCommand called, isRecording=${isRecording()}")

        // START_REDELIVER_INTENT (below) hands this intent back after a kill, so the config
        // survives a restart. The null-safe reads still matter: redelivery only covers a
        // start that was never acknowledged with stopSelf(), and a bind-only creation
        // delivers no intent at all.
        val distanceFilter = intent?.getDoubleExtra("distance_filter", 0.0) ?: 0.0
        forceLocationManager = intent?.getBooleanExtra("force_location_manager", false) ?: false

        // Don't come back as a zombie. A redelivered start means Android re-created us after
        // a kill; if nothing is being recorded then nobody is waiting on this service. The
        // only reason it was alive was to keep the map warm for an app that is no longer
        // running, and the resume below is gated on isRecording() — so we would sit in the
        // foreground streaming nothing, showing a notification that claims we're keeping a
        // location ready, with no app left alive to ever clean it up.
        //
        // The START_FLAG_REDELIVERY gate is load-bearing: on a FRESH start isRecording() is
        // also false, because Dart calls set_recording_state only after starting the service.
        // Without the flag check this would kill every normal start.
        if ((flags and START_FLAG_REDELIVERY) != 0 && !isRecording()) {
            LifecycleLogger.log(this, "onStartCommand: restarted while not recording — stopping")
            stopForeground(true)
            stopSelf()
            return START_NOT_STICKY
        }

        // Make sure location request exists
        if (mLocationRequest == null) {
            createLocationRequest(distanceFilter)
        }

        // Resume location updates if we were recording
        if (isRecording()) {
            LifecycleLogger.log(this, "onStartCommand: Resuming location updates")
            requestLocationUpdates()
        }

        // START_REDELIVER_INTENT rather than START_STICKY: the location config travels in
        // the intent, and a sticky restart delivers a null one — silently resetting the
        // distance filter to 0.0 and burning battery at full rate. Safe to use here because
        // removeLocationUpdates() calls the argless stopSelf(), which clears every pending
        // start, so a deliberate "stop tracking" drops the redelivery; only a kill
        // mid-tracking comes back.
        return START_REDELIVER_INTENT
    }

    private fun getMainActivityClass(context: Context): Class<*>? {
        val packageName = context.packageName
        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
        val className = launchIntent?.component?.className ?: return null

        return try {
            Class.forName(className)
        } catch (e: ClassNotFoundException) {
            e.printStackTrace()
            null
        }
    }
}
