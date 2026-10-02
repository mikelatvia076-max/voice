package com.example.gameassistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

class GameService : Service() {

    private val h = Handler(Looper.getMainLooper())
    private var overlay: TextView? = null
    private var lastPkg: String? = null
    private var lastAlert = 0L

    private val tick = object : Runnable {
        override fun run() {
            try { update() } catch (_: Exception) { }
            h.postDelayed(this, 3000)
        }
    }

    override fun onBind(i: Intent?): IBinder? = null
    override fun onStartCommand(i: Intent?, f: Int, id: Int) = START_STICKY

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("run", "Assistant running", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(
            NotificationChannel("alert", "Heat alerts", NotificationManager.IMPORTANCE_HIGH))
        val n = Notification.Builder(this, "run")
            .setContentTitle("Game Assistant is on")
            .setContentText("Watching for your games")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
        h.post(tick)
    }

    override fun onDestroy() {
        h.removeCallbacks(tick)
        hideOverlay()
        GameMode.disable(this)
        super.onDestroy()
    }

    private fun update() {
        foregroundPkg()?.let { lastPkg = it }
        val inGame = lastPkg != null && lastPkg in Prefs.games(this)

        if (inGame && !GameMode.isOn(this)) GameMode.enable(this, manual = false, keep = lastPkg)
        if (!inGame && GameMode.isOn(this) && !GameMode.isManual(this)) GameMode.disable(this)

        if (!GameMode.isOn(this)) { hideOverlay(); return }

        val temp = Sys.batteryTemp(this)
        val limit = Prefs.limit(this)
        val hot = temp >= limit
        if (hot) {
            GameMode.setCooling(this, true)
            alert(temp)
        } else if (temp <= limit - 3) {
            GameMode.setCooling(this, false)
        }
        val (free, _) = Sys.ram(this)
        showOverlay(
            "%.1f°C  %d%%\nRAM free %d MB".format(temp, Sys.batteryLevel(this), free), hot)
    }

    /** Latest app brought to the foreground (needs Usage access). */
    private fun foregroundPkg(): String? {
        if (!Sys.hasUsage(this)) return null
        val usm = getSystemService(UsageStatsManager::class.java)
        val now = System.currentTimeMillis()
        val ev = usm.queryEvents(now - 10_000, now)
        val e = UsageEvents.Event()
        var p: String? = null
        while (ev.hasNextEvent()) {
            ev.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED) p = e.packageName
        }
        return p
    }

    private fun alert(t: Float) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastAlert < 60_000) return
        lastAlert = now
        val n = Notification.Builder(this, "alert")
            .setContentTitle("Phone is hot: %.1f°C".format(t))
            .setContentText("Cooling mode on. Lower the game's graphics/FPS or take a short break.")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .build()
        getSystemService(NotificationManager::class.java).notify(2, n)
        getSystemService(Vibrator::class.java)
            .vibrate(VibrationEffect.createOneShot(400, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun showOverlay(text: String, hot: Boolean) {
        if (!Settings.canDrawOverlays(this)) return
        if (overlay == null) {
            val tv = TextView(this).apply {
                textSize = 11f
                setPadding(16, 8, 16, 8)
                setBackgroundColor(Color.argb(150, 0, 0, 0))
            }
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.TOP or Gravity.END; x = 8; y = 80 }
            getSystemService(WindowManager::class.java).addView(tv, lp)
            overlay = tv
        }
        overlay?.apply {
            this.text = text
            setTextColor(if (hot) Color.RED else Color.WHITE)
        }
    }

    private fun hideOverlay() {
        overlay?.let {
            try { getSystemService(WindowManager::class.java).removeView(it) } catch (_: Exception) { }
            overlay = null
        }
    }
}
