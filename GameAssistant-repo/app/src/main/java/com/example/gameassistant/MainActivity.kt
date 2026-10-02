package com.example.gameassistant

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var status: TextView
    private val h = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() { updateStatus(); h.postDelayed(this, 2000) }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
        }
        setContentView(ScrollView(this).apply { addView(root) })

        status = TextView(this).apply { textSize = 15f }
        root.addView(status)

        fun title(t: String) = root.addView(TextView(this).apply {
            text = t; textSize = 17f; setPadding(0, 32, 0, 8)
        })
        fun btn(t: String, f: () -> Unit) = root.addView(Button(this).apply {
            text = t; setOnClickListener { f() }
        })

        title("Assistant")
        btn("START assistant (auto game mode)") {
            startForegroundService(Intent(this, GameService::class.java))
            toast("Assistant started")
        }
        btn("STOP assistant") {
            stopService(Intent(this, GameService::class.java))
            toast("Assistant stopped")
        }
        btn("Choose my games") { pickGames() }

        val limitLabel = TextView(this).apply {
            text = "Heat alert at ${Prefs.limit(this@MainActivity)}°C"
        }
        root.addView(limitLabel)
        root.addView(SeekBar(this).apply {
            max = 12
            progress = Prefs.limit(this@MainActivity) - 36
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) {
                    Prefs.setLimit(this@MainActivity, 36 + p)
                    limitLabel.text = "Heat alert at ${36 + p}°C"
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        })

        title("Manual tools")
        btn("Game mode ON now") { GameMode.enable(this, manual = true); toast("Game mode on") }
        btn("Game mode OFF (restore settings)") { GameMode.disable(this); toast("Settings restored") }
        btn("Free RAM now") {
            val n = Cleaner.freeRam(this)
            toast("Asked $n apps to stop")
        }
        btn("Scan storage for big/junk files") { scanStorage() }

        title("Permissions (tap each, then come back)")
        btn("1. Draw over other apps (overlay)") {
            open(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, true)
        }
        btn("2. Modify system settings") { open(Settings.ACTION_MANAGE_WRITE_SETTINGS, true) }
        btn("3. Do Not Disturb access") { open(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS) }
        btn("4. Usage access (detect games)") { open(Settings.ACTION_USAGE_ACCESS_SETTINGS) }
        btn("5. All files access (cleaner)") {
            open(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, true)
        }
    }

    override fun onResume() { super.onResume(); h.post(refresh) }
    override fun onPause() { super.onPause(); h.removeCallbacks(refresh) }

    private fun open(action: String, withPkg: Boolean = false) {
        val i = Intent(action)
        if (withPkg) i.data = Uri.parse("package:$packageName")
        startActivity(i)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun ok(b: Boolean) = if (b) "✓" else "✗"

    private fun updateStatus() {
        val (free, total) = Sys.ram(this)
        val nm = getSystemService(NotificationManager::class.java)
        status.text = """
            Battery: %.1f°C    Level: %d%%
            Thermal state: %s
            RAM free: %d / %d MB
            Storage free: %.1f GB
            Game mode: %s    Games chosen: %d

            Overlay ${ok(Settings.canDrawOverlays(this))}   Settings ${ok(Settings.System.canWrite(this))}   DND ${ok(nm.isNotificationPolicyAccessGranted)}
            Usage ${ok(Sys.hasUsage(this))}   Files ${ok(Environment.isExternalStorageManager())}   Secure ${ok(Sys.hasSecure(this))}
        """.trimIndent().format(
            Sys.batteryTemp(this), Sys.batteryLevel(this), Sys.thermal(this),
            free, total, Sys.storageFreeGb(),
            if (GameMode.isOn(this)) "ON" else "off", Prefs.games(this).size
        )
    }

    private fun pickGames() {
        val pm = packageManager
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(i, 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .filter { it.first != packageName }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
        val sel = Prefs.games(this).toMutableSet()
        val checked = BooleanArray(apps.size) { apps[it].first in sel }
        AlertDialog.Builder(this)
            .setTitle("Select your games")
            .setMultiChoiceItems(apps.map { it.second }.toTypedArray(), checked) { _, w, on ->
                if (on) sel.add(apps[w].first) else sel.remove(apps[w].first)
            }
            .setPositiveButton("Save") { _, _ -> Prefs.setGames(this, sel) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun scanStorage() {
        if (!Environment.isExternalStorageManager()) {
            toast("Give 'All files access' first"); return
        }
        toast("Scanning...")
        Thread {
            val list = Cleaner.scan()
            runOnUiThread {
                if (list.isEmpty()) { toast("Nothing big found"); return@runOnUiThread }
                val sel = BooleanArray(list.size)
                val names = list.map {
                    it.file.absolutePath.removePrefix("/storage/emulated/0/") +
                        "  (" + it.size / 1_000_000 + " MB)"
                }.toTypedArray()
                AlertDialog.Builder(this)
                    .setTitle("Tick files to delete")
                    .setMultiChoiceItems(names, sel) { _, w, on -> sel[w] = on }
                    .setPositiveButton("Delete") { _, _ ->
                        var freed = 0L
                        list.forEachIndexed { idx, j ->
                            if (sel[idx] && j.file.delete()) freed += j.size
                        }
                        toast("Freed ${freed / 1_000_000} MB")
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }.start()
    }
}
