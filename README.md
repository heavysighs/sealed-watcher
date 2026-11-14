# Sealed Watcher

A tiny guardian app in the Sealed family. It keeps **Curbox's accessibility
service switched on**.

## Why

Curbox (the framework's lower-tier social-media limiter) enforces through an
Android accessibility service. Anyone holding the phone can switch that
service off in Settings > Accessibility — no policy in Android Enterprise,
KSP, or ManageEngine can lock that one toggle (Hexnode states this flatly;
the full KSP catalogue has zero accessibility policies). The watcher does
not block the tap. It **undoes it, within seconds**: Android keeps the list
of enabled accessibility services in a protected system setting
(`enabled_accessibility_services`); the watcher watches that list and, the
moment Curbox's service drops off it, writes it back on.

This is the same mechanism as Tasker / MacroDroid's built-in "Keep
Accessibility Services Running" feature, in a dedicated no-surface app
instead of a general automation tool.

## How it works

- A foreground service registers a content observer on
  `enabled_accessibility_services` and `accessibility_enabled`, plus a
  20-second periodic re-check.
- On any change, if Curbox's service is missing, it appends it back and
  re-sets `accessibility_enabled` to 1. Curbox's service component is
  discovered at runtime from the installed-accessibility-services list
  (no class name hardcoded); the last seen id is cached in case Curbox is
  temporarily absent.
- Writing the setting needs `WRITE_SECURE_SETTINGS` — not grantable from
  the phone UI. It is granted once on setup day via adb (below), survives
  reboots, and does not appear in the Settings permission pages.
- Starts itself on boot (BOOT_COMPLETED receiver).

## Setup day (operator steps)

1. Install the APK (via ManageEngine distribution on the Tier 3 build, or
   manually on a lower-tier build).
2. From a computer, with USB debugging temporarily on:
   ```
   adb shell pm grant com.heavysighs.sealedwatcher android.permission.WRITE_SECURE_SETTINGS
   adb shell dumpsys deviceidle whitelist +com.heavysighs.sealedwatcher
   ```
3. Open Sealed Watcher once, tap **Start watcher**, confirm the status
   screen shows permission granted and Curbox's service on.
4. Turn debugging back off (on the sealed build it is already off by policy).
5. Protect the watcher itself, or it can be killed first:
   - ManageEngine: distribute with "Restrict users from uninstalling".
   - KSP Application management policies: add `com.heavysighs.sealedwatcher`
     to the **Force Stop Blocklist** and **Clear Data Block List**.
   - The ME profile-wide app-control restrictions (Fix 1 family) also cover it.

## Test (after setup)

Settings > Accessibility > Installed apps > Curbox > switch OFF.
Expected: within seconds the service is back on (check the toggle, or the
watcher's status screen — "Last restore" updates). Also test the Quick
Settings Security & Privacy deep-link path to the accessibility page; the
watcher does not care which door was used, because it watches the list,
not the door.

## Known limits (honest)

- **Heals, doesn't prevent.** The off-state lasts seconds, not zero.
- If the watcher is force-stopped, Android keeps it stopped until it is
  relaunched or the phone reboots — hence the blocklist protection above.
  A force-stop that lands is a gap until the next boot or operator action.
- Strength is custody-relative: set up by an operator/partner it is strong;
  self-set-up by the person it guards against, it is friction-grade
  (they can re-enable debugging, revoke the grant via adb, or remove it
  wherever the surrounding locks allow).

## Build

GitHub Actions, same pattern as Sealed Updater: Actions > Build APK >
Run workflow. Produces two artifacts: the APK, and the signing keystore.
**First build only:** the keystore is generated on the runner — download
the `sealed-watcher-keystore` artifact and commit `app/release.keystore`
into the project, or later builds will sign with a different key and
updates will not install over the first build.

Package: `com.heavysighs.sealedwatcher` · min Android 10 (API 29) ·
version 1.0 (code 1).
