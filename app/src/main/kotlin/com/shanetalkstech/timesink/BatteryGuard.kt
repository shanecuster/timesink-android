package com.shanetalkstech.timesink

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager

/**
 * The battery rule: when the battery is low (or Battery Saver is on) the app
 * pauses all automatic tracking and syncing, and says so once.
 *
 * Nothing here runs by itself. It is called when the app is opened, when
 * Android announces a low battery, and by the short scheduled job (see
 * [Background]). Pausing loses no data: Android keeps its own usage log for
 * about a week, so after the battery recovers the app simply reads what it
 * missed.
 */
object BatteryGuard {
    /** The notification channel for battery notices (users can mute it in Android's settings). */
    private const val CHANNEL_ID = "battery"

    /** The id of the one notification this app posts for the battery; a new one replaces the old. */
    private const val NOTIFICATION_ID = 1

    /**
     * Why automatic work should pause right now, in a few words, or `null` when it
     * may run. Does not depend on the master switch; callers check that first.
     *
     * - Battery at or below the user's level, and not charging: `Battery low (19%)`.
     * - Battery Saver on (if the user wants that): `Battery Saver is on`.
     */
    fun pauseReason(context: Context, settings: AppSettings): String? {
        val options = settings.battery
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (options.pauseOnBatterySaver && power.isPowerSaveMode) return "Battery Saver is on"

        // The "sticky" battery broadcast always holds the latest reading; passing
        // `null` as the receiver just reads it once without listening.
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return null
        val level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        // A phone on a charger is not draining, so it never counts as "low".
        val charging = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        if (level < 0 || scale <= 0 || charging) return null
        val percent = level * 100 / scale
        return if (percent <= options.thresholdPercent) "Battery low ($percent%)" else null
    }

    /**
     * Works out whether the app is paused, and sends the notices: one
     * notification when a pause begins, and (if the user asked) one quiet one when
     * it ends. Returns the reason, or `null` when running normally.
     *
     * Does nothing, and returns `null`, while the master switch is off.
     */
    fun checkAndNotify(context: Context, settings: AppSettings): String? {
        if (!settings.enabled) return null
        val reason = pauseReason(context, settings)
        if (reason != null) {
            // Only once per pause: remember that the notice was sent.
            if (!settings.pauseNotified) {
                settings.pauseNotified = true
                notify(context, "$reason. timesink has paused tracking and sync.")
            }
        } else if (settings.pauseNotified) {
            settings.pauseNotified = false
            if (settings.battery.notifyOnResume) {
                notify(context, "Battery OK again. timesink has resumed tracking and sync.")
            }
        }
        return reason
    }

    /** Shows a notification with this text. Tapping it opens the app. Silently skipped if notifications are blocked. */
    private fun notify(context: Context, text: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // Without the notification permission (Android 13+) this does nothing.
        if (!manager.areNotificationsEnabled()) return
        // A channel is Android's way of letting the user control one kind of
        // notification. Creating it again each time is harmless.
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Battery", NotificationManager.IMPORTANCE_DEFAULT),
        )
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_timesink)
            .setContentTitle("timesink")
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }
}
