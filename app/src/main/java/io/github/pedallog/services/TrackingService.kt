package io.github.pedallog.services

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.NotificationManager.IMPORTANCE_LOW
import android.app.PendingIntent
import android.app.PendingIntent.FLAG_IMMUTABLE
import android.app.PendingIntent.FLAG_UPDATE_CURRENT
import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import androidx.preference.PreferenceManager
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.Priority
import com.google.android.gms.location.LocationResult
import org.maplibre.android.geometry.LatLng
import io.github.pedallog.R
import io.github.pedallog.other.Constants.ACTION_PAUSE_SERVICE
import io.github.pedallog.other.Constants.ACTION_START_OR_RESUME_SERVICE
import io.github.pedallog.other.Constants.ACTION_STOP_SERVICE
import io.github.pedallog.other.Constants.FASTEST_LOCATION_INTERVAL
import io.github.pedallog.other.Constants.LOCATION_UPDATE_INTERVAL
import io.github.pedallog.other.Constants.NOTIFICATION_CHANNEL_ID
import io.github.pedallog.other.Constants.NOTIFICATION_CHANNEL_NAME
import io.github.pedallog.other.Constants.NOTIFICATION_ID
import io.github.pedallog.other.TrackingUtility
import android.content.pm.ServiceInfo
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

typealias Polyline = MutableList<LatLng>
typealias Polylines = MutableList<Polyline>

@AndroidEntryPoint
class TrackingService : LifecycleService() {

    // This variable will help us know when to start and when to pause / resume our service
    var isFirstJourney = true

    // Timer variables

    // Timer enabled or not
    private var isTimerEnabled = false

    // When our setTimer() function is called, we will  store the current time in this variable
    private var timeStarted = 0L // Time when our service was started

    // This is the time of one single lap that happens when setTimer() is called and paused
    private var lapTime = 0L

    // This is the total time our journey has been running
    private var timeRun = 0L

    private var timerJob: Job? = null

    // This variable will tell whether our service was killed or not
    private var serviceKilled = false

    @Inject   // This will provide us the current location of user
    lateinit var fusedLocationProviderClient: FusedLocationProviderClient

    @Inject // This is the base notification that will contain title, time and icon
    lateinit var baseNotificationBuilder: NotificationCompat.Builder

    // This is the current notification that will contain the action text and action to be performed (pause or resume)
    lateinit var curNotificationBuilder: NotificationCompat.Builder


    companion object {
        val isTracking = MutableLiveData<Boolean>() // Whether we want to track our user or not
        val pathPoints =
            MutableLiveData<Polylines>() // This is the list of paths or lines where user has travelled
        val timeRunInMillis =
            MutableLiveData<Long>() // Total time elapsed since our service was started or resumed
        val currentSpeed = MutableLiveData<Float>() // Current speed in m/s
        val distanceMeters = MutableLiveData<Float>() // Total distance in meters
    }

    private var totalDistanceMeters: Float = 0f

    // This function is called whenever our service is created
    override fun onCreate() {
        super.onCreate()

        // Restore persisted distance if available
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        totalDistanceMeters = prefs.getFloat("tracking_distance_meters", 0f)

        postInitialValues() // Function to post empty values to our live data. (We created this function at bottom).

        // Restore distance to LiveData if we had a saved value
        if (totalDistanceMeters > 0f) {
            distanceMeters.postValue(totalDistanceMeters)
        }

        // Initially we set curNotificationBuilder to baseNotificationBuilder to avoid lateinit not initialized exception
        curNotificationBuilder = baseNotificationBuilder

        isTracking.observe(this, Observer {
            // Function to get location of user when tracking is set to true and save it to pathPoints variable. (We created this function at bottom).
            updateLocationTracking(it)

            // Function to update the notification whenever we are tracking. (We created this function at bottom).
            updateNotificationTrackingState(it)

            // Show floating bar only while tracking (if enabled in settings).
            updateFloatingBarState(it)
        })
    }

    // This function is called whenever a command is received
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

        intent?.let {
            when (it.action) {
                ACTION_START_OR_RESUME_SERVICE -> {
                    if (isFirstJourney) {
                        // Function to start this service. (We created this function at bottom).
                        startForegroundService()
                        isFirstJourney = false

                        //Toast.makeText(this,"Service Started",Toast.LENGTH_SHORT).show()
                    } else {
                        // When we resume our service, we only want to continue the timer instead of restarting entire service.
                        startTimer()

                        //Toast.makeText(this,"Service Resumed",Toast.LENGTH_SHORT).show()
                    }
                }
                ACTION_PAUSE_SERVICE -> {
                    pauseService() // Function to pause our service. (We created this function at bottom).
                }
                ACTION_STOP_SERVICE -> {
                    killService() // Function to stop or end our service. (We created this function at bottom).
                }
            }
        }

        return super.onStartCommand(intent, flags, startId)
    }

    // Function to kill our service
    private fun killService() {
        serviceKilled = true
        isFirstJourney = true

        pauseService()
        postInitialValues()
        
        // Clear persisted distance when journey ends
        PreferenceManager.getDefaultSharedPreferences(this)
            .edit()
            .remove("tracking_distance_meters")
            .apply()

        stopFloatingBarServiceIfRunning()
        
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(NOTIFICATION_ID)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun updateFloatingBarState(isTracking: Boolean) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val enabled = prefs.getBoolean("floating_bar_enabled", false)

        if (!enabled) {
            stopFloatingBarServiceIfRunning()
            return
        }

        val canDraw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }

        if (!canDraw) {
            // Settings screen handles permission request; avoid starting overlay without permission.
            stopFloatingBarServiceIfRunning()
            return
        }

        if (isTracking) {
            startService(Intent(applicationContext, FloatingBarService::class.java))
        } else {
            stopFloatingBarServiceIfRunning()
        }
    }

    private fun stopFloatingBarServiceIfRunning() {
        stopService(Intent(applicationContext, FloatingBarService::class.java))
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // Keep tracking running in the background even if the task is removed.
        // This is intentional: users often swipe away the app during a ride.
        // The foreground notification remains visible with controls to pause/stop tracking.
        // If battery drain is a concern, users can stop tracking via the notification.
    }

    // Function to pause our service
    private fun pauseService() {
        if (isTimerEnabled) {
            val now = SystemClock.elapsedRealtime()
            lapTime = now - timeStarted
            timeRun += lapTime
            lapTime = 0L
            timeStarted = 0L
        }

        isTimerEnabled = false
        timerJob?.cancel()
        timerJob = null

        timeRunInMillis.postValue(timeRun)
        isTracking.postValue(false)
    }

    // Function to create notification channel to provide metadata to notification
    @RequiresApi(Build.VERSION_CODES.O)
    private fun createNotificationChannel(notificationManager: NotificationManager) {
        val channel =
            NotificationChannel(NOTIFICATION_CHANNEL_ID, getString(R.string.notification_channel_name), IMPORTANCE_LOW)
        notificationManager.createNotificationChannel(channel)
    }

    // Function to start our foreground service
    private fun startForegroundService() {

        // Get notification manager service and create notification channel to store notification metadata if android version >= Oreo
        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createNotificationChannel(notificationManager) // Notification channel created using the function we created above
        }

        // As our service is starting, we set the isTracking value to true and also start the stopwatch timer
        isTracking.postValue(true)

        // Function to start the stopwatch. (We created this function).
        startTimer()

        // Start our service as a foreground service
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, baseNotificationBuilder.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, baseNotificationBuilder.build())
        }

        // As time changes and our service is running, we update our notification's content
        timeRunInMillis.observe(this, Observer {
            if(!serviceKilled) {
                val notification = curNotificationBuilder.setContentText(TrackingUtility.getFormattedStopwatchTime(it))
                notificationManager.notify(NOTIFICATION_ID,notification.build())
            }
        })

    }

    // Function to update our notification
    private fun updateNotificationTrackingState(isTracking: Boolean) {

        // Set notification action text
        val notificationActionText = if(isTracking) getString(R.string.pause) else getString(R.string.resume)

        // Set intent with action according to isTracking variable
        val pendingIntent = if(isTracking) {
            val pauseIntent = Intent(this,TrackingService::class.java).apply {
                action = ACTION_PAUSE_SERVICE
            }
            PendingIntent.getService(this,1,pauseIntent, FLAG_UPDATE_CURRENT or FLAG_IMMUTABLE)
        } else {
            val resumeIntent = Intent(this,TrackingService::class.java).apply {
                action = ACTION_START_OR_RESUME_SERVICE
            }
            PendingIntent.getService(this,2,resumeIntent, FLAG_UPDATE_CURRENT or FLAG_IMMUTABLE)
        }

        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // This piece of code helps in clearing the previous actions
        curNotificationBuilder.javaClass.getDeclaredField("mActions").apply {
            isAccessible = true
            set(curNotificationBuilder,ArrayList<NotificationCompat.Action>())
        }

        // When our service is running, we notify the notification with the required data
        if(!serviceKilled) {
            curNotificationBuilder = baseNotificationBuilder.addAction(R.drawable.ic_bike,notificationActionText,pendingIntent)
            notificationManager.notify(NOTIFICATION_ID,curNotificationBuilder.build())
        }

    }


    // Function to initialize / post empty valus to our live data members
    private fun postInitialValues() {
        isTimerEnabled = false
        timerJob?.cancel()
        timerJob = null
        timeStarted = 0L
        lapTime = 0L
        timeRun = 0L

        totalDistanceMeters = 0f

        isTracking.postValue(false)
        pathPoints.postValue(mutableListOf())
        timeRunInMillis.postValue(0L)
        currentSpeed.postValue(0f)
        distanceMeters.postValue(0f)
    }

    // Function to add an empty polyline to our data members when there is a pause and resume distance gap between two locations
    private fun addEmptyPolyline() = pathPoints.value?.apply {
        add(mutableListOf())
        pathPoints.postValue(this)
    } ?: pathPoints.postValue(mutableListOf(mutableListOf()))

    // Function to add the location points of user
    private fun addPathPoint(location: Location?) {
        location?.let {
            // Get latitudes and longitudes of user's current location
            val pos = LatLng(location.latitude, location.longitude)
            pathPoints.value?.apply {
                // Increment distance using the previous point (if any)
                val lastPoint = lastOrNull()?.lastOrNull()
                if (lastPoint != null) {
                    val result = FloatArray(1)
                    Location.distanceBetween(
                        lastPoint.latitude,
                        lastPoint.longitude,
                        pos.latitude,
                        pos.longitude,
                        result
                    )
                    totalDistanceMeters += result[0]
                    distanceMeters.postValue(totalDistanceMeters)
                    
                    // Persist distance to SharedPreferences
                    PreferenceManager.getDefaultSharedPreferences(this@TrackingService)
                        .edit()
                        .putFloat("tracking_distance_meters", totalDistanceMeters)
                        .apply()
                }

                // Add the position to end of our pathPoints variable
                last().add(pos)
                pathPoints.postValue(this)
            }
        }
    }

    // This callback will be called whenever location of user changes
    private val locationCallback = object : LocationCallback() {

        // When a new location is received
        override fun onLocationResult(result: LocationResult) {
            super.onLocationResult(result)
            if (isTracking.value!!) {
                for (location in result.locations) {
                    addPathPoint(location)
                    // Update current speed - use actual GPS speed
                    val speed = if (location.hasSpeed()) location.speed else 0f
                    currentSpeed.postValue(speed)
                }
            }
        }
    }

    // The Actual Function to get the current location of user
    @SuppressLint("MissingPermission") // Since we used easy permissions library, we can use @SupressLint to hide this warning
    private fun updateLocationTracking(isTracking: Boolean) {
        if (isTracking && TrackingUtility.hasLocationPermissions(this)) {
            val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, LOCATION_UPDATE_INTERVAL)
                .setMinUpdateIntervalMillis(FASTEST_LOCATION_INTERVAL)
                .build()
            fusedLocationProviderClient.requestLocationUpdates(
                request,
                locationCallback,
                Looper.getMainLooper()
            )
        } else {
            fusedLocationProviderClient.removeLocationUpdates(locationCallback)
        }
    }

    // Function to start the stopwatch / timer
    private fun startTimer() {
        addEmptyPolyline()
        isTracking.postValue(true)
        if (timerJob?.isActive == true) return

        timeStarted = SystemClock.elapsedRealtime()
        isTimerEnabled = true
        timerJob = CoroutineScope(Dispatchers.Main.immediate).launch {
            var lastPostedSecond = (timeRunInMillis.value ?: 0L) / 1000L
            while (isTimerEnabled) {
                lapTime = SystemClock.elapsedRealtime() - timeStarted
                val totalTime = timeRun + lapTime
                val currentSecond = totalTime / 1000L

                if (currentSecond != lastPostedSecond) {
                    lastPostedSecond = currentSecond
                    timeRunInMillis.postValue(totalTime)
                }

                val delayMs = 1000L - (totalTime % 1000L)
                delay(delayMs.coerceIn(50L, 1000L))
            }
        }
    }



}
