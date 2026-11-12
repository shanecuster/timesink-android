package com.shanetalkstech.timesink

import android.content.Context
import android.os.Build

/**
 * The app's saved settings: the master switch and the sync server details.
 *
 * They live in Android's private per-app storage (`SharedPreferences`), which
 * other apps cannot read. The password is kept there as plain text, protected
 * only by that privacy; it is never written to the database, to logs, or into
 * anything that is synced.
 *
 * Everything is off or empty until the user turns it on: a fresh install tracks
 * nothing in the background and sends nothing anywhere.
 */
class AppSettings(context: Context) {
    /** The file all settings are kept in. */
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /**
     * The master switch, "Track and sync this phone". While it is off nothing runs
     * in the background and nothing is sent. Off by default.
     */
    var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(value) = prefs.edit().putBoolean("enabled", value).apply()

    /** The CouchDB address, such as `http://myserver:5984`. */
    var serverUrl: String
        get() = prefs.getString("server_url", "") ?: ""
        set(value) = prefs.edit().putString("server_url", value.trim()).apply()

    /** The database name on the server. */
    var database: String
        get() = prefs.getString("database", "timesink") ?: "timesink"
        set(value) = prefs.edit().putString("database", value.trim()).apply()

    /** The CouchDB user. */
    var user: String
        get() = prefs.getString("user", "") ?: ""
        set(value) = prefs.edit().putString("user", value.trim()).apply()

    /** The CouchDB password. */
    var password: String
        get() = prefs.getString("password", "") ?: ""
        set(value) = prefs.edit().putString("password", value).apply()

    /**
     * This phone's name in the synced data, such as `pixel-fold`. Typed by the
     * user; until then it is a tidied-up version of the phone's model name.
     */
    var deviceName: String
        get() = prefs.getString("device_name", null) ?: defaultDeviceName()
        set(value) {
            val clean = cleanDeviceName(value)
            // An unusable name keeps the old one rather than saving nothing.
            if (clean.isNotEmpty()) prefs.edit().putString("device_name", clean).apply()
        }

    /** Sync once each time the app is opened (the cheapest automatic mode). Off by default. */
    var syncOnOpen: Boolean
        get() = prefs.getBoolean("sync_on_open", false)
        set(value) = prefs.edit().putBoolean("sync_on_open", value).apply()

    /**
     * How far Android's usage log has been read, in milliseconds since 1970
     * (0 = not yet). The next import starts from here, so nothing is counted twice.
     */
    var usageCursorMs: Long
        get() = prefs.getLong("usage_cursor_ms", 0L)
        set(value) = prefs.edit().putLong("usage_cursor_ms", value).apply()

    /** The app that was still in front when the log was last read, or `null`. */
    var usageOpenPackage: String?
        get() = prefs.getString("usage_open_package", null)
        set(value) = prefs.edit().putString("usage_open_package", value).apply()

    /** Forgets how far the usage log was read (used after local data is deleted). */
    fun resetUsage() {
        prefs.edit().remove("usage_cursor_ms").remove("usage_open_package").apply()
    }

    /** Are the server details filled in enough to try a sync? */
    val syncConfigured: Boolean
        get() = serverUrl.isNotEmpty() && database.isNotEmpty() && user.isNotEmpty() && password.isNotEmpty()

    companion object {
        /**
         * Turns typed text into a safe device name: lower case, letters, digits,
         * dots, dashes and underscores only, spaces become dashes. (The name is
         * part of every synced document's id, so a colon or slash would break it.)
         */
        fun cleanDeviceName(text: String): String =
            text.trim().lowercase()
                .replace(Regex("\\s+"), "-")
                .replace(Regex("[^a-z0-9._-]"), "")
                .trim('-')
                .take(40)

        /** The name used until the user types one: the phone's model, tidied. */
        fun defaultDeviceName(): String =
            cleanDeviceName(Build.MODEL).ifEmpty { "phone" }
    }
}
