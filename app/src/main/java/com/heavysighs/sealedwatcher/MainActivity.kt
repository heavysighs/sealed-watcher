package com.heavysighs.sealedwatcher

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.DateFormat
import java.util.Date

/**
 * Minimal status screen, used on setup day (and for later checks).
 * The watcher itself needs no UI and no interaction after setup.
 */
class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // So the watcher's persistent notification is visible (proof it is
        // alive). The service still guards without it; this is transparency.
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }
        val status = TextView(this)
        val startBtn = Button(this).apply {
            text = "Start watcher"
            setOnClickListener {
                startForegroundService(Intent(this@MainActivity, WatcherService::class.java))
                status.text = statusText()
            }
        }
        root.addView(status)
        root.addView(startBtn)
        setContentView(ScrollView(this).apply { addView(root) })
        status.text = statusText()
    }

    private fun statusText(): String {
        val prefs = getSharedPreferences(WatcherService.PREFS, Context.MODE_PRIVATE)
        val granted = checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED
        val current = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: ""
        val targetOn = current.split(':').any {
            it.substringBefore('/') == WatcherService.TARGET_PACKAGE
        }
        val lastHeal = prefs.getLong(WatcherService.KEY_LAST_HEAL, 0L)
        val healText = if (lastHeal == 0L) "never" else
            DateFormat.getDateTimeInstance().format(Date(lastHeal))
        val guardedId = prefs.getString(WatcherService.KEY_LAST_ID, null)
            ?: "not seen yet (is Curbox installed?)"
        return buildString {
            appendLine("Sealed Watcher")
            appendLine()
            appendLine("Secure-settings permission granted: $granted")
            appendLine("Guarded service: $guardedId")
            appendLine("Curbox accessibility service on: $targetOn")
            appendLine("Last restore: $healText")
            appendLine()
            appendLine("Setup-day adb commands (run from a computer, then turn debugging off):")
            appendLine()
            appendLine("adb shell pm grant com.heavysighs.sealedwatcher android.permission.WRITE_SECURE_SETTINGS")
            appendLine("adb shell dumpsys deviceidle whitelist +com.heavysighs.sealedwatcher")
            appendLine()
            appendLine("Then tap Start watcher once.")
        }
    }
}
