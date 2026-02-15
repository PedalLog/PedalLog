package io.github.pedallog.services

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.content.getSystemService
import androidx.preference.PreferenceManager
import io.github.pedallog.R
import io.github.pedallog.other.TrackingUtility
import io.github.pedallog.ui.TrackingActivity
import androidx.lifecycle.Observer
import android.view.ViewConfiguration

class FloatingBarService : Service() {

    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    private var contentTextView: TextView? = null

    private val prefs by lazy { PreferenceManager.getDefaultSharedPreferences(this) }
    private val prefsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == KEY_CONTENT) {
            updateContentText()
        }
    }

    private val timeObserver = Observer<Long> { updateContentText() }
    private val speedObserver = Observer<Float> { updateContentText() }
    private val distanceObserver = Observer<Float> { updateContentText() }

    private companion object {
        const val KEY_CONTENT = "floating_bar_content"
        const val KEY_POSITION_X = "floating_bar_x"
        const val KEY_POSITION_Y = "floating_bar_y"

        const val CONTENT_TITLE = "title"
        const val CONTENT_TIME = "time"
        const val CONTENT_SPEED = "speed"
        const val CONTENT_DISTANCE = "distance"
        const val CONTENT_AVG_SPEED = "avg_speed"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()

        if (!canDrawOverlays()) {
            stopSelf()
            return
        }

        val themedContext = ContextThemeWrapper(this, R.style.Theme_PedalLog)
        val inflater = LayoutInflater.from(themedContext)
        floatingView = inflater.inflate(R.layout.floating_bar, null)
        contentTextView = floatingView?.findViewById(R.id.tvFloatingBarContent)

        windowManager = getSystemService()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // Use saved position if available, otherwise center the floating bar
            val savedX = prefs.getInt(KEY_POSITION_X, Int.MIN_VALUE)
            val savedY = prefs.getInt(KEY_POSITION_Y, Int.MIN_VALUE)
            if (savedX != Int.MIN_VALUE && savedY != Int.MIN_VALUE) {
                x = savedX
                y = savedY
            } else {
                // Center the floating bar initially
                gravity = Gravity.CENTER
            }
        }

        floatingView?.setOnClickListener {
            val launch = Intent(this, TrackingActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            }
            startActivity(launch)
        }

        floatingView?.setOnTouchListener(DragTouchListener())

        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        TrackingService.timeRunInMillis.observeForever(timeObserver)
        TrackingService.currentSpeed.observeForever(speedObserver)
        TrackingService.distanceMeters.observeForever(distanceObserver)

        updateContentText()

        windowManager?.addView(floatingView, layoutParams)
    }

    override fun onDestroy() {
        super.onDestroy()

        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        TrackingService.timeRunInMillis.removeObserver(timeObserver)
        TrackingService.currentSpeed.removeObserver(speedObserver)
        TrackingService.distanceMeters.removeObserver(distanceObserver)

        if (floatingView != null) {
            windowManager?.removeView(floatingView)
            floatingView = null
        }
        contentTextView = null
        windowManager = null
        layoutParams = null
    }

    override fun onUnbind(intent: Intent?): Boolean {
        // Remove observers on unbind as well to prevent leaks
        TrackingService.timeRunInMillis.removeObserver(timeObserver)
        TrackingService.currentSpeed.removeObserver(speedObserver)
        TrackingService.distanceMeters.removeObserver(distanceObserver)
        return super.onUnbind(intent)
    }

    private fun updateContentText() {
        val tv = contentTextView ?: return
        val mode = prefs.getString(KEY_CONTENT, CONTENT_TITLE) ?: CONTENT_TITLE

        val text = when (mode) {
            CONTENT_TIME -> {
                val ms = TrackingService.timeRunInMillis.value ?: 0L
                TrackingUtility.getFormattedStopwatchTime(ms)
            }
            CONTENT_SPEED -> {
                val ms = TrackingService.currentSpeed.value ?: 0f
                val kmh = (ms * 3.6f)
                getString(R.string.floating_bar_speed_format, kmh)
            }
            CONTENT_DISTANCE -> {
                val meters = TrackingService.distanceMeters.value ?: 0f
                val km = meters / 1000f
                getString(R.string.floating_bar_distance_format, km)
            }
            CONTENT_AVG_SPEED -> {
                val meters = TrackingService.distanceMeters.value ?: 0f
                val msElapsed = TrackingService.timeRunInMillis.value ?: 0L
                val hours = msElapsed / 3600000f
                val km = meters / 1000f
                val avgKmh = if (hours > 0f) (km / hours) else 0f
                getString(R.string.floating_bar_avg_speed_format, avgKmh)
            }
            else -> getString(R.string.floating_bar_title)
        }

        tv.text = text
    }

    private fun canDrawOverlays(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
    }

    private inner class DragTouchListener : View.OnTouchListener {
        private var initialX = 0
        private var initialY = 0
        private var touchX = 0f
        private var touchY = 0f
        private val touchSlop = ViewConfiguration.get(this@FloatingBarService).scaledTouchSlop

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            val params = layoutParams ?: return false
            val wm = windowManager ?: return false

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - touchX).toInt()
                    params.y = initialY + (event.rawY - touchY).toInt()
                    wm.updateViewLayout(floatingView, params)
                    return true
                }

                MotionEvent.ACTION_UP -> {
                    // Save the position for next time
                    prefs.edit().apply {
                        putInt(KEY_POSITION_X, params.x)
                        putInt(KEY_POSITION_Y, params.y)
                        apply()
                    }
                    
                    // Allow click if it was basically a tap (small movement)
                    val dx = (event.rawX - touchX)
                    val dy = (event.rawY - touchY)
                    val distanceSquared = dx * dx + dy * dy
                    val threshold = touchSlop * touchSlop
                    if (distanceSquared < threshold) {
                        v.performClick()
                    }
                    return true
                }
            }

            return false
        }
    }
}
