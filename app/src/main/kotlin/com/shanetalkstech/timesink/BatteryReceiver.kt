package com.shanetalkstech.timesink

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Wakes briefly when Android announces "battery low" or "battery OK again" and
 * lets [BatteryGuard] send the matching notice straight away. (The 15 minute
 * background job catches the user's own level, which may be higher than
 * Android's.) It does nothing else and ends at once.
 */
class BatteryReceiver : BroadcastReceiver() {
    /** Called by Android when one of the two battery broadcasts arrives. */
    override fun onReceive(context: Context, intent: Intent) {
        BatteryGuard.checkAndNotify(context, AppSettings(context))
    }
}
