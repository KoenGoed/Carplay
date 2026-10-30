package nl.koen.autoscherm

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import kotlin.math.abs

/**
 * Rond knopje dat boven Maps, Flitsmeister en WhatsApp zweeft.
 * Tik = terug naar het autoscherm. Lang indrukken = terug en meteen luisteren.
 */
class BubbleService : Service() {

    companion object {
        @Volatile
        var running = false
    }

    // Blijft de plek bijhouden terwijl je in Maps of Flitsmeister zit, voor de parkeerplek
    private val locationListener = LocationListener { loc: Location -> LocationStore.saveLast(this, loc) }
    private var tracking = false

    private var windowManager: WindowManager? = null
    private var bubble: View? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        running = true
        startAsForeground()
        if (bubble == null && Settings.canDrawOverlays(this)) showBubble()
        startTracking()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        if (tracking) {
            try { getSystemService(LocationManager::class.java).removeUpdates(locationListener) } catch (ignored: Exception) { }
        }
        bubble?.let { windowManager?.removeView(it) }
        bubble = null
        super.onDestroy()
    }

    private fun openDashboard(listen: Boolean) {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                .putExtra(MainActivity.EXTRA_LISTEN, listen)
        )
    }

    private fun startAsForeground() {
        val channelId = "terugknop"
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(channelId, "Terugknop", NotificationManager.IMPORTANCE_MIN)
        )
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_car)
            .setContentTitle("Autoscherm")
            .setContentText("Tik om terug te gaan naar je autoscherm")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        val types = if (hasLocation()) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or types)
        } else if (Build.VERSION.SDK_INT >= 29 && types != 0) {
            startForeground(1, notification, types)
        } else {
            startForeground(1, notification)
        }
    }

    private fun hasLocation() =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun startTracking() {
        if (tracking || !hasLocation()) return
        try {
            getSystemService(LocationManager::class.java)
                .requestLocationUpdates(LocationManager.GPS_PROVIDER, 15_000L, 25f, locationListener, Looper.getMainLooper())
            tracking = true
        } catch (ignored: Exception) { }
    }

    private fun showBubble() {
        val prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE)
        val size = (72 * resources.displayMetrics.density).toInt()
        val pad = size / 4

        val view = ImageView(this).apply {
            setImageResource(R.drawable.ic_car)
            setBackgroundResource(R.drawable.bg_bubble)
            setPadding(pad, pad, pad, pad)
            elevation = 16f
            contentDescription = "Terug naar autoscherm"
        }

        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt("bubble_x", 24)
            y = prefs.getInt("bubble_y", 260)
        }

        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false

        view.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = lp.x; startY = lp.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (abs(dx) > 14 || abs(dy) > 14) moved = true
                    if (moved) {
                        lp.x = startX + dx.toInt()
                        lp.y = startY + dy.toInt()
                        windowManager?.updateViewLayout(v, lp)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        prefs.edit().putInt("bubble_x", lp.x).putInt("bubble_y", lp.y).apply()
                    } else {
                        v.performClick()
                        // Lang indrukken = meteen een spraakopdracht geven
                        openDashboard(listen = e.eventTime - e.downTime > 500)
                    }
                    true
                }
                else -> false
            }
        }

        windowManager = getSystemService(WindowManager::class.java)
        windowManager?.addView(view, lp)
        bubble = view
    }
}
