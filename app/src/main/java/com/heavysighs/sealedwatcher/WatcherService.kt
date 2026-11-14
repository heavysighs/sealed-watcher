package com.heavysighs.sealedwatcher

import android.accessibilityservice.AccessibilityServiceInfo
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
 * "enabled_accessibility_services") and guards it two ways:
 *
 *  1. Restore — the moment the guarded app's service drops off the list,
 *     it is written back on (and the master accessibility switch with it).
 *  2. Revive — if the service stays ON the list but is not actually live
 *     (some phones' battery managers kill the bound service while leaving
 *     the switch on), the watcher toggles it off and back on — but only
 *     after the dead state has persisted past a grace period, and at most
 *     once per cooldown, so a slow bind is never churned.
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
        const val KEY_DEAD_SINCE = "dead_since_ms"
        const val KEY_LAST_REVIVE = "last_revive_ms"
        const val KEY_REVIVE_UNTIL = "revive_until_ms"
        private const val CHANNEL_ID = "watcher"
        private const val CHECK_INTERVAL_MS = 20_000L
        private const val DEAD_GRACE_MS = 60_000L
        private const val REVIVE_COOLDOWN_MS = 300_000L
        private const val REVIVE_READD_DELAY_MS = 3_000L
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

    /**
     * The guarded app's accessibility-service id, discovered live from the
     * installed-services list so no class name is hardcoded and a guarded-app
     * update that renames its service is followed automatically. Returns
     * null when the guarded app is not installed (or is disabled): then
     * there is nothing to guard and nothing is written. The last seen id is
     * cached for the status screen only — never used as a write target,
     * because a stale id could name a service that no longer exists.
     */
    private fun discoverTargetId(): String? {
        val am = getSystemService(AccessibilityManager::class.java)
        val found = am.installedAccessibilityServiceList
            .firstOrNull { it.resolveInfo.serviceInfo.packageName == TARGET_PACKAGE }
            ?.id
        if (found != null) {
            getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_LAST_ID, found).apply()
        }
        return found
    }

    /** Ids of accessibility services the system reports as enabled AND bound. */
    private fun liveServiceIds(): List<String> {
        val am = getSystemService(AccessibilityManager::class.java)
        return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .map { it.id }
    }

    private fun heal() {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cr = contentResolver
        val now = System.currentTimeMillis()

        val id = discoverTargetId()
        if (id == null) {
            prefs.edit().putBoolean(KEY_TARGET_ON, false).apply()
            return
        }

        val tokens = parseTokens(
            Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        )
        val masterOn = Settings.Secure.getInt(
            cr, Settings.Secure.ACCESSIBILITY_ENABLED, 0
        ) == 1
        val targetOn = containsService(tokens, id)
        prefs.edit().putBoolean(KEY_TARGET_ON, targetOn).apply()

        // A revive (off/on toggle) is in its off beat: do not re-add yet —
        // the removal must register with the system before the re-add, or
        // the toggle is a no-op. The delayed task below performs the re-add.
        val reviving = now < prefs.getLong(KEY_REVIVE_UNTIL, 0L)

        if ((!targetOn && !reviving) || !masterOn) {
            try {
                if (!targetOn && !reviving) {
                    Settings.Secure.putString(
                        cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                        joinTokens(withService(tokens, id))
                    )
                }
                if (!masterOn) {
                    Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
                }
                prefs.edit()
                    .putBoolean(KEY_PERMISSION_OK, true)
                    .putBoolean(KEY_TARGET_ON, true)
                    .putLong(KEY_LAST_HEAL, now)
                    .putLong(KEY_DEAD_SINCE, 0L)
                    .apply()
            } catch (e: SecurityException) {
                // WRITE_SECURE_SETTINGS not granted (setup-day adb step missing).
                prefs.edit().putBoolean(KEY_PERMISSION_OK, false).apply()
            }
            return
        }

        if (!targetOn) return // mid-revive; the delayed re-add handles it

        // On the list, master switch on. Is the service actually live?
        val live = liveServiceIds().any {
            normalizeComponent(it) == normalizeComponent(id)
        }
        if (live) {
            prefs.edit()
                .putBoolean(KEY_PERMISSION_OK, true)
                .putLong(KEY_DEAD_SINCE, 0L)
                .apply()
            return
        }

        // Listed but not live. Binding is not instant, so the dead state
        // must persist past the grace period; and revives are rate-limited
        // so a genuinely broken service is never toggled in a loop.
        val deadSince = prefs.getLong(KEY_DEAD_SINCE, 0L)
        if (deadSince == 0L) {
            prefs.edit().putLong(KEY_DEAD_SINCE, now).apply()
            return
        }
        val lastRevive = prefs.getLong(KEY_LAST_REVIVE, 0L)
        if (now - deadSince < DEAD_GRACE_MS || now - lastRevive < REVIVE_COOLDOWN_MS) return

        try {
            Settings.Secure.putString(
                cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                joinTokens(withoutService(tokens, id))
            )
            prefs.edit()
                .putLong(KEY_LAST_REVIVE, now)
                .putLong(KEY_LAST_HEAL, now)
                .putLong(KEY_DEAD_SINCE, 0L)
                .putLong(KEY_REVIVE_UNTIL, now + REVIVE_READD_DELAY_MS + CHECK_INTERVAL_MS)
                .apply()
            handler.postDelayed({
                try {
                    // Re-discover: the guarded app may have updated mid-beat.
                    val freshId = discoverTargetId() ?: id
                    val current = parseTokens(
                        Settings.Secure.getString(
                            cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                        )
                    )
                    if (!containsService(current, freshId)) {
                        Settings.Secure.putString(
                            cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                            joinTokens(withService(current, freshId))
                        )
                    }
                    Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
                } catch (e: SecurityException) {
                    prefs.edit().putBoolean(KEY_PERMISSION_OK, false).apply()
                } finally {
                    prefs.edit().putLong(KEY_REVIVE_UNTIL, 0L).apply()
                }
            }, REVIVE_READD_DELAY_MS)
        } catch (e: SecurityException) {
            prefs.edit().putBoolean(KEY_PERMISSION_OK, false).apply()
        }
    }
}
