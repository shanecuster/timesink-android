package com.shanetalkstech.timesink.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import uniffi.timesink_ffi.SyncStatus

/** The widest a settings text box is, so a web address has room on a phone. */
private const val BOX_DP = 210

/**
 * The server details and options being edited on the settings screen, before
 * they are saved.
 */
data class SyncDraft(
    /** The CouchDB address. */
    val url: String,
    /** The database name on the server. */
    val database: String,
    /** The CouchDB user. */
    val user: String,
    /** The CouchDB password. */
    val password: String,
    /** This phone's name in the synced data. */
    val deviceName: String,
    /** Sync once each time the app is opened. */
    val syncOnOpen: Boolean,
)

/**
 * The settings screen: the master switch and the sync server.
 *
 * Sync is opt-in. With the master switch off nothing runs in the background
 * and nothing is sent, and "sync now" is unavailable.
 *
 * @param enabled the master switch ("Track and sync this phone").
 * @param onEnabledChange called when the master switch is tapped.
 * @param initial the saved server details to start editing from.
 * @param status how syncing has gone so far, or `null` before it is loaded.
 * @param nowSecs the current time, for "synced 5m ago".
 * @param message the result of the last sync attempt, or `null`.
 * @param messageIsError whether [message] is a problem (drawn in the warning colour).
 * @param busy true while a sync is running.
 * @param onSave called with the edited details when "save" is tapped.
 * @param onSyncNow called with the edited details when "sync now" is tapped; the
 *   caller saves them first.
 * @param usageAllowed whether Usage access has been granted to the app.
 * @param usageLastSecs when the phone's usage was last recorded (Unix seconds), or `null`.
 * @param usageMessage the result of the last recording, or `null`.
 * @param onAllowUsage called when "allow usage access" is tapped (opens Android's settings).
 * @param onRecordNow called when "record now" is tapped.
 * @param debug true in debug builds, which add a button to delete all local data.
 * @param onWipe called after the delete button is tapped twice (debug builds only).
 * @param onBack called when "back" is tapped.
 */
@Composable
fun SettingsScreen(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    initial: SyncDraft,
    status: SyncStatus?,
    nowSecs: Long,
    message: String?,
    messageIsError: Boolean,
    busy: Boolean,
    onSave: (SyncDraft) -> Unit,
    onSyncNow: (SyncDraft) -> Unit,
    usageAllowed: Boolean,
    usageLastSecs: Long?,
    usageMessage: String?,
    onAllowUsage: () -> Unit,
    onRecordNow: () -> Unit,
    debug: Boolean,
    onWipe: () -> Unit,
    onBack: () -> Unit,
) {
    val colors = LocalTimesinkColors.current
    // The edits so far; they start as the saved details.
    var draft by remember { mutableStateOf(initial) }
    // The delete button needs two taps; this remembers the first.
    var confirmWipe by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        TuiPanel(title = " This phone ", modifier = Modifier.fillMaxWidth()) {
            ToggleRow(
                label = "Track and sync this phone",
                checked = enabled,
                onChange = onEnabledChange,
            )
            MonoText(
                text = if (enabled) {
                    "On. Nothing runs constantly; only short jobs while this stays on."
                } else {
                    "Off. Nothing runs in the background and nothing is sent."
                },
                color = colors.muted,
                size = 12.sp,
            )
        }
        Spacer(Modifier.height(8.dp))

        TuiPanel(title = " Phone usage ", modifier = Modifier.fillMaxWidth()) {
            MonoText(
                text = "Records which apps you use on this phone, and for how long. " +
                    "Only app names and times; nothing inside an app is read.",
                color = colors.muted,
                size = 12.sp,
            )
            Spacer(Modifier.height(6.dp))
            if (!usageAllowed) {
                MonoText(text = "Usage access: not allowed yet", color = colors.warn, size = 13.sp)
                ActionButton(label = "allow usage access", onClick = onAllowUsage)
            } else {
                MonoText(text = "Usage access: allowed", color = colors.ok, size = 13.sp)
                MonoText(
                    text = if (usageLastSecs != null) "recorded  ${agoLabel(nowSecs, usageLastSecs)}" else "not recorded yet",
                    color = colors.text,
                    size = 13.sp,
                )
                when {
                    !enabled -> MonoText(text = "Turn the switch above on to record.", color = colors.muted, size = 12.sp)
                    else -> ActionButton(label = "record now", onClick = onRecordNow)
                }
            }
            if (usageMessage != null) {
                MonoText(text = usageMessage, color = colors.ok, size = 12.sp)
            }
        }
        Spacer(Modifier.height(8.dp))

        TuiPanel(title = " Sync server (CouchDB) ", modifier = Modifier.fillMaxWidth()) {
            LabeledField("server  ", draft.url, { draft = draft.copy(url = it) }, BOX_DP, keyboardType = KeyboardType.Uri)
            Spacer(Modifier.height(6.dp))
            LabeledField("database", draft.database, { draft = draft.copy(database = it) }, BOX_DP)
            Spacer(Modifier.height(6.dp))
            LabeledField("user    ", draft.user, { draft = draft.copy(user = it) }, BOX_DP)
            Spacer(Modifier.height(6.dp))
            LabeledField("password", draft.password, { draft = draft.copy(password = it) }, BOX_DP, secret = true)
            Spacer(Modifier.height(6.dp))
            LabeledField("this is ", draft.deviceName, { draft = draft.copy(deviceName = it) }, BOX_DP)
            MonoText(
                text = "Pick the device name before the first sync. Changing it later starts a new device.",
                color = colors.muted,
                size = 11.sp,
            )
            ToggleRow(
                label = "Sync when I open the app",
                checked = draft.syncOnOpen,
                onChange = { draft = draft.copy(syncOnOpen = it) },
            )
        }
        Spacer(Modifier.height(8.dp))

        TuiPanel(title = " Status ", modifier = Modifier.fillMaxWidth()) {
            val lastOk = status?.lastOk
            MonoText(
                text = if (lastOk != null) "last synced  ${agoLabel(nowSecs, lastOk)}" else "never synced",
                color = colors.text,
                size = 13.sp,
            )
            val failed = status?.lastFailed
            if (failed != null) {
                MonoText(text = "last attempt failed  ${agoLabel(nowSecs, failed)}", color = colors.warn, size = 13.sp)
            }
            val pending = status?.pendingDays ?: 0u
            MonoText(text = "waiting to send  $pending day(s)", color = colors.muted, size = 13.sp)
            if (message != null) {
                Spacer(Modifier.height(6.dp))
                MonoText(text = message, color = if (messageIsError) colors.danger else colors.ok, size = 12.sp)
            }
        }
        Spacer(Modifier.height(6.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton(label = "save", onClick = { onSave(draft) })
            when {
                busy -> MonoText(text = "syncing...", color = colors.muted, size = 14.sp)
                !enabled -> MonoText(text = "[ sync now ] needs the switch on", color = colors.muted, size = 12.sp)
                else -> ActionButton(label = "sync now", onClick = { onSyncNow(draft) })
            }
            ActionButton(label = "back", onClick = onBack)
        }
        if (debug) {
            Spacer(Modifier.height(10.dp))
            // Debug builds only: start again from nothing, e.g. to drop the demo data.
            ActionButton(
                label = if (confirmWipe) "really delete everything on this phone?" else "delete all local data (debug)",
                onClick = {
                    if (confirmWipe) {
                        confirmWipe = false
                        onWipe()
                    } else {
                        confirmWipe = true
                    }
                },
            )
        }
    }
}
