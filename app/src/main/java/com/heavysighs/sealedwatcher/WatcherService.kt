package com.heavysighs.sealedwatcher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityManager

/**
 * Sealed Watcher: keeps the guarded app's accessibility service switched on.
 *
 * It does not (and cannot) block the Settings toggle. It watches the system
 * list of enabled accessibility services (Settings.Secure
 * "enabled_accessibility_services") and, the moment the guarded app's
 * service drops off that list, writes it back on. The off-state lasts
 * seconds instead of forever.
 *
 * Writing that list requires WRITE_SECURE_SETTINGS, granted once on setup
 * day via adb:  adb shell pm grant com.heavysighs.sealedwatcher
 * android.permission.WRITE_SECURE_SETTINGS
 * This is the same mechanism as Tasker/MacroDroid's "Keep Accessibility
 * Services Running" feature.
 */
class WatcherService : Service() {

    companion object {
        const val TARGET_PACKAGE = "neth.iecal.curbox" // Curbox
        const val PREFS = "watcher"
        const val KEY_LAST_ID = "last_service_id"
        const val KEY_PERMISSION_OK = "permission_ok"
        const val KEY_TARGET_ON = "target_on"
        const val KEY_LAST_HEAL = "last_heal_ms"
        private const val CHANNEL_ID = "watcher"
        private const val CHECK_INTERVAL_MS = 20_000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var observer: ContentObserver? = null

    private val periodicCheck = object : Runnable {
        override fun run() {
            heal()
            handler.postDelayed(this, CHECK_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        startForeground(1, buildNotification())
        registerObserver()
        handler.post(periodicCheck)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        heal()
        return START_STICKY
    }

    override fun onDestroy() {
        observer?.let { contentResolver.unregisterContentObserver(it) }
        handler.removeCallbacks(periodicCheck)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Watcher", NotificationManager.IMPORTANCE_MIN)
            )
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Sealed Watcher")
            .setContentText("Guarding Curbox's accessibility service.")
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setOngoing(true)
            .build()
    }

    private fun registerObserver() {
        val obs = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                heal()
            }
        }
        contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
            false, obs
        )
        contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(Settings.Secure.ACCESSIBILITY_ENABLED),
            false, obs
        )
        observer = obs
    }

    /** The guarded app's accessibility-service component id ("pkg/class"),
     *  discovered from the installed-services list so no class name is
     *  hardcoded. Falls back to the last id seen. */
    private fun targetServiceId(): String? {
        val am = getSystemService(AccessibilityManager::class.java)
        val found = am.installedAccessibilityServiceList
            .firstOrNull { it.resolveInfo.serviceInfo.packageName == TARGET_PACKAGE }
            ?.id
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (found != null) {
            prefs.edit().putString(KEY_LAST_ID, found).apply()
            return found
        }
        return prefs.getString(KEY_LAST_ID, null)
    }

    private fun heal() {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cr = contentResolver
        val id = targetServiceId()

        val current = Settings.Secure.getString(
            cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: ""
        val tokens = current.split(':').filter { it.isNotBlank() }
        val targetOn = tokens.any { token ->
            token.substringBefore('/') == TARGET_PACKAGE
        }
        prefs.edit().putBoolean(KEY_TARGET_ON, targetOn).apply()
        if (id == null) return // guarded app not installed (yet); nothing to do

        val masterOn = Settings.Secure.getInt(
            cr, Settings.Secure.ACCESSIBILITY_ENABLED, 0
        ) == 1

        if (targetOn && masterOn) {
            prefs.edit().putBoolean(KEY_PERMISSION_OK, true).apply()
            return
        }

        try {
            if (!targetOn) {
                val newValue = (tokens + id).joinToString(":")
                Settings.Secure.putString(
                    cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, newValue
                )
            }
            if (!masterOn) {
                Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            }
            prefs.edit()
                .putBoolean(KEY_PERMISSION_OK, true)
                .putBoolean(KEY_TARGET_ON, true)
                .putLong(KEY_LAST_HEAL, System.currentTimeMillis())
                .apply()
        } catch (e: SecurityException) {
            // WRITE_SECURE_SETTINGS not granted (setup-day adb step missing).
            prefs.edit().putBoolean(KEY_PERMISSION_OK, false).apply()
        }
    }
}
