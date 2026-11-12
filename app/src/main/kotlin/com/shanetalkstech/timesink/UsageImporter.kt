package com.shanetalkstech.timesink

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.provider.Settings
import uniffi.timesink_ffi.AppLabel
import uniffi.timesink_ffi.Database
import uniffi.timesink_ffi.UsageEvent
import uniffi.timesink_ffi.UsageKind

/**
 * Reads Android's own usage log and records it in the timesink database.
 *
 * Android already keeps a log of which app came to the front and left it. This
 * app reads that log when it is opened (or when the user asks); it never runs
 * constantly. The log is only kept for about a week, so opening the app at
 * least weekly loses nothing.
 *
 * Only the app's package name and its own name (the label under its icon) are
 * recorded. Nothing inside an app (no titles, no content) is ever seen.
 */
object UsageImporter {
    /** Android's event numbers for the log entries this app uses. */
    private const val APP_RESUMED = 1
    private const val APP_PAUSED = 2
    private const val SCREEN_OFF = 16
    private const val KEYGUARD_SHOWN = 17
    private const val DEVICE_SHUTDOWN = 26

    /** How far back the very first import goes: about as far as Android keeps the log. */
    private const val FIRST_IMPORT_DAYS = 7L

    /**
     * Has the user allowed "Usage access" for this app? It is a special
     * permission, granted in Android's settings rather than by a pop-up.
     */
    fun hasAccess(context: Context): Boolean {
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** The intent that opens Android's "Usage access" list, where the user allows this app. */
    fun accessSettingsIntent(): Intent =
        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * Reads the log from where it was last read until now, records it, and
     * remembers the new position. Must run off the main thread.
     *
     * @return how many stretches of app use were recorded.
     */
    fun importNow(context: Context, db: Database, settings: AppSettings): Int {
        val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val end = System.currentTimeMillis()
        val begin = settings.usageCursorMs.takeIf { it > 0 } ?: (end - FIRST_IMPORT_DAYS * 86_400_000L)
        val skipped = nonAppPackages(context)

        val events = mutableListOf<UsageEvent>()
        // An app that was in front when the log was last read carries on from `begin`.
        settings.usageOpenPackage?.let { events += UsageEvent(begin, it, UsageKind.RESUMED) }

        val packages = linkedSetOf<String>()
        val log = manager.queryEvents(begin, end)
        val entry = UsageEvents.Event()
        while (log.hasNextEvent()) {
            log.getNextEvent(entry)
            val pkg = entry.packageName ?: ""
            when (entry.eventType) {
                // The home screen and system bar are not "using an app": they stop
                // whatever was in front and count for nothing.
                APP_RESUMED -> if (pkg in skipped) {
                    events += UsageEvent(entry.timeStamp, "", UsageKind.NOTHING)
                } else {
                    events += UsageEvent(entry.timeStamp, pkg, UsageKind.RESUMED)
                    packages += pkg
                }
                APP_PAUSED -> if (pkg !in skipped) events += UsageEvent(entry.timeStamp, pkg, UsageKind.PAUSED)
                SCREEN_OFF, KEYGUARD_SHOWN, DEVICE_SHUTDOWN ->
                    events += UsageEvent(entry.timeStamp, "", UsageKind.SCREEN_OFF)
            }
        }

        val labels = packages.map { AppLabel(it, labelFor(context.packageManager, it)) }
        val result = db.recordUsage(events, labels, end)
        // Only move the position forward once the rows are safely stored.
        settings.usageCursorMs = end
        settings.usageOpenPackage = result.openPackage
        return result.recorded.toInt()
    }

    /** The name shown under the app's icon, or the end of its package name if Android won't say. */
    private fun labelFor(pm: PackageManager, pkg: String): String =
        try {
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            pkg.substringAfterLast('.')
        }

    /** Packages that are part of the phone's own surface rather than an app: the launchers and the system bar. */
    private fun nonAppPackages(context: Context): Set<String> {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val launchers = context.packageManager.queryIntentActivities(home, 0).map { it.activityInfo.packageName }
        return (launchers + "com.android.systemui" + "android").toSet()
    }
}
