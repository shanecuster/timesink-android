package com.shanetalkstech.timesink

import java.util.TimeZone
import uniffi.timesink_ffi.ZoneOffsets

/**
 * Tells the Rust core how far local time is from UTC, at any moment.
 *
 * The screens group time into local days, and the offset changes with daylight
 * saving, so Rust asks this object instead of guessing. It is a thin wrapper
 * around Android's own time-zone data, read fresh on every call so a change of
 * zone (travel) is picked up.
 */
class DeviceZone : ZoneOffsets {

    /**
     * The offset in seconds at [epochSecs] (seconds since 1970, UTC).
     *
     * `TimeZone.getOffset` wants milliseconds and answers in milliseconds, so the
     * value is converted both ways.
     */
    override fun offsetAt(epochSecs: Long): Long =
        TimeZone.getDefault().getOffset(epochSecs * 1000L) / 1000L
}
