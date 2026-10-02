package com.example.gameassistant

import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Environment
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import java.io.File

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("ga", Context.MODE_PRIVATE)
    fun games(c: Context): Set<String> = sp(c).getStringSet("games", emptySet())!!.toSet()
    fun setGames(c: Context, s: Set<String>) = sp(c).edit().putStringSet("games", s).apply()
    fun limit(c: Context): Int = sp(c).getInt("limit", 41)
    fun setLimit(c: Context, v: Int) = sp(c).edit().putInt("limit", v).apply()
}

object Sys {
    private fun battery(c: Context) =
        c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

    fun batteryTemp(c: Context): Float =
        (battery(c)?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f

    fun batteryLevel(c: Context): Int =
        battery(c)?.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) ?: 0

    fun thermal(c: Context): String =
        when (c.getSystemService(PowerManager::class.java).currentThermalStatus) {
            PowerManager.THERMAL_STATUS_NONE -> "Normal"
            PowerManager.THERMAL_STATUS_LIGHT -> "Light"
            PowerManager.THERMAL_STATUS_MODERATE -> "Moderate"
            PowerManager.THERMAL_STATUS_SEVERE -> "Severe"
            PowerManager.THERMAL_STATUS_CRITICAL -> "Critical"
            PowerManager.THERMAL_STATUS_EMERGENCY -> "Emergency"
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "Shutdown"
            else -> "Unknown"
        }

    /** returns (free MB, total MB) */
    fun ram(c: Context): Pair<Long, Long> {
        val mi = ActivityManager.MemoryInfo()
        c.getSystemService(ActivityManager::class.java).getMemoryInfo(mi)
        return (mi.availMem / 1_000_000) to (mi.totalMem / 1_000_000)
    }

    fun storageFreeGb(): Float = Environment.getDataDirectory().usableSpace / 1e9f

    fun hasSecure(c: Context) =
        c.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    fun hasUsage(c: Context): Boolean {
        val ops = c.getSystemService(AppOpsManager::class.java)
        return ops.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.packageName
        ) == AppOpsManager.MODE_ALLOWED
    }
}

object GameMode {
    private const val GAME_BRIGHTNESS = 110   // 0..255, lower = cooler
    private const val COOL_BRIGHTNESS = 70
    private val animKeys = listOf(
        Settings.Global.ANIMATOR_DURATION_SCALE,
        Settings.Global.TRANSITION_ANIMATION_SCALE,
        Settings.Global.WINDOW_ANIMATION_SCALE
    )

    private fun st(c: Context) = c.getSharedPreferences("gm_state", Context.MODE_PRIVATE)
    fun isOn(c: Context) = st(c).getBoolean("on", false)
    fun isManual(c: Context) = st(c).getBoolean("manual", false)

    fun enable(c: Context, manual: Boolean = false, keep: String? = null) {
        if (isOn(c)) {
            if (manual) st(c).edit().putBoolean("manual", true).apply()
            return
        }
        val cr = c.contentResolver
        val e = st(c).edit()

        // 1) Brightness: lower screen = less heat
        if (Settings.System.canWrite(c)) {
            e.putInt("bright", Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 128))
            e.putInt("bmode", Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, 1))
            Settings.System.putInt(
                cr, Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            )
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, GAME_BRIGHTNESS)
        }

        // 2) Do Not Disturb: only alarms may interrupt
        val nm = c.getSystemService(NotificationManager::class.java)
        if (nm.isNotificationPolicyAccessGranted) {
            e.putInt("dnd", nm.currentInterruptionFilter)
            nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALARMS)
        }

        // 3) Lighter animations (needs the one-time ADB grant)
        if (Sys.hasSecure(c)) {
            for (k in animKeys) {
                e.putString("a_$k", Settings.Global.getString(cr, k) ?: "1.0")
                Settings.Global.putString(cr, k, "0.5")
            }
        }

        e.putBoolean("on", true).putBoolean("manual", manual).apply()

        // 4) Ask background apps to stop
        Cleaner.freeRam(c, setOfNotNull(keep))
    }

    fun disable(c: Context) {
        if (!isOn(c)) return
        setCooling(c, false)
        val s = st(c)
        val cr = c.contentResolver
        if (Settings.System.canWrite(c) && s.contains("bright")) {
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, s.getInt("bright", 128))
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, s.getInt("bmode", 1))
        }
        val nm = c.getSystemService(NotificationManager::class.java)
        if (nm.isNotificationPolicyAccessGranted && s.contains("dnd")) {
            nm.setInterruptionFilter(s.getInt("dnd", NotificationManager.INTERRUPTION_FILTER_ALL))
        }
        if (Sys.hasSecure(c)) {
            for (k in animKeys) {
                if (s.contains("a_$k")) Settings.Global.putString(cr, k, s.getString("a_$k", "1.0"))
            }
        }
        s.edit().clear().apply()
    }

    /** Cool-down: battery saver + dimmer screen while the phone is too hot. */
    fun setCooling(c: Context, on: Boolean) {
        val s = st(c)
        val cr = c.contentResolver
        val cooling = s.getBoolean("cool", false)
        if (on && !cooling) {
            s.edit().putBoolean("cool", true).apply()
            if (Settings.System.canWrite(c)) {
                Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, COOL_BRIGHTNESS)
            }
            if (Sys.hasSecure(c)) {
                s.edit().putInt("lp", Settings.Global.getInt(cr, "low_power", 0)).apply()
                Settings.Global.putInt(cr, "low_power", 1)
            }
        } else if (!on && cooling) {
            if (Settings.System.canWrite(c)) {
                Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, GAME_BRIGHTNESS)
            }
            if (Sys.hasSecure(c) && s.contains("lp")) {
                Settings.Global.putInt(cr, "low_power", s.getInt("lp", 0))
            }
            s.edit().putBoolean("cool", false).apply()
        }
    }
}

data class Junk(val file: File, val size: Long)

object Cleaner {
    private val junkExt = setOf("apk", "tmp", "temp", "log", "bak", "old")

    /** Big files (>= minMb) and leftover installers/temp files. Nothing is deleted here. */
    fun scan(minMb: Int = 50): List<Junk> {
        val root = Environment.getExternalStorageDirectory()
        return root.walkTopDown()
            .onEnter { !(it.name == "Android" && it.parentFile == root) }
            .filter {
                it.isFile && (it.length() >= minMb * 1_000_000L ||
                    it.extension.lowercase() in junkExt)
            }
            .map { Junk(it, it.length()) }
            .sortedByDescending { it.size }
            .take(80)
            .toList()
    }

    /** Asks Android to stop background processes of user-installed apps. */
    fun freeRam(c: Context, keep: Set<String> = emptySet()): Int {
        val am = c.getSystemService(ActivityManager::class.java)
        var n = 0
        for (a in c.packageManager.getInstalledApplications(0)) {
            if ((a.flags and ApplicationInfo.FLAG_SYSTEM) != 0) continue
            if (a.packageName == c.packageName || a.packageName in keep) continue
            am.killBackgroundProcesses(a.packageName)
            n++
        }
        return n
    }
}
