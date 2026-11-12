package com.shanetalkstech.timesink

import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * The Quick Settings tile: one tap in the notification shade turns tracking and
 * sync on or off (the same master switch as in the app), without opening the app.
 */
class TrackingTileService : TileService() {
    /** Called when the shade opens: show the current state. */
    override fun onStartListening() {
        refresh()
    }

    /** Called when the tile is tapped: flip the master switch. */
    override fun onClick() {
        val settings = AppSettings(this)
        settings.enabled = !settings.enabled
        // Starting or stopping the scheduled job to match.
        Background.reschedule(this)
        // A fresh start should not remember an old pause notice.
        settings.pauseNotified = false
        refresh()
    }

    /** Draws the tile: lit when on, dim when off, with a word saying which. */
    private fun refresh() {
        val tile = qsTile ?: return
        val settings = AppSettings(this)
        val paused = settings.enabled && BatteryGuard.pauseReason(this, settings) != null
        tile.state = if (settings.enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "timesink"
        tile.icon = Icon.createWithResource(this, R.drawable.ic_stat_timesink)
        // A second line of text under the label exists from Android 10.
        if (Build.VERSION.SDK_INT >= 29) {
            tile.subtitle = when {
                !settings.enabled -> "Off"
                paused -> "Paused"
                else -> "On"
            }
        }
        tile.updateTile()
    }
}
