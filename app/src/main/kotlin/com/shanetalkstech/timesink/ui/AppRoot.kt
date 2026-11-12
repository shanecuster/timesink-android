package com.shanetalkstech.timesink.ui

import android.Manifest
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shanetalkstech.timesink.AppSettings
import com.shanetalkstech.timesink.Background
import com.shanetalkstech.timesink.BatteryGuard
import com.shanetalkstech.timesink.BatteryOptions
import com.shanetalkstech.timesink.DeviceZone
import com.shanetalkstech.timesink.UsageImporter
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.timesink_ffi.CustomSpan
import uniffi.timesink_ffi.AppSecs
import uniffi.timesink_ffi.Database
import uniffi.timesink_ffi.DaySummary
import uniffi.timesink_ffi.PeriodSummary
import uniffi.timesink_ffi.SyncServer
import uniffi.timesink_ffi.SyncStatus
import uniffi.timesink_ffi.customRange
import uniffi.timesink_ffi.defaultThemeName
import uniffi.timesink_ffi.listThemes

/** The shortest wait between two automatic "sync when I open the app" runs, in seconds. */
private const val AUTO_SYNC_GAP_SECS = 300L

/** Days of made-up data a debug build creates when its database is empty. */
private const val DEMO_DAYS = 60L

/** Any fixed number: the same seed always makes the same demo days. */
private const val DEMO_SEED = 7UL

/**
 * The root of the screen: opens the database, loads today's numbers, and shows
 * the banner, the period tabs and the selected period's screen.
 *
 * In a *debug* build (the Run button in Android Studio) an empty database is
 * filled with made-up demo data so the screens can be tried and photographed
 * before sync exists. A release build never does this: it starts empty.
 *
 * @param refreshKey a number that goes up each time the app comes back to the
 *   front; a change makes the numbers load again.
 */
@Composable
fun AppRoot(refreshKey: Int) {
    val context = LocalContext.current

    // The colour theme: the default one by name, falling back to the first.
    val palette = remember {
        val themes = listThemes()
        themes.firstOrNull { it.name == defaultThemeName() } ?: themes.first()
    }
    val colors = remember(palette) { palette.toColors() }

    // What the screen shows: today's numbers, or an error message, or neither yet.
    var summary by remember { mutableStateOf<DaySummary?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var period by remember { mutableStateOf(Period.TODAY) }
    // How many periods back from the current one is on screen (0 = the current one).
    var offset by remember { mutableStateOf(0L) }
    // The day shown on the Today tab. It differs from `summary` (always the real
    // today, used by the banner) when the user has stepped back to an earlier day.
    var shownDay by remember { mutableStateOf<DaySummary?>(null) }
    // The numbers for the other tabs, and which tab they were loaded for, so a
    // slow load for one tab is never drawn under another tab's name.
    var shownRange by remember { mutableStateOf<PeriodSummary?>(null) }
    var shownFor by remember { mutableStateOf(Period.TODAY) }

    // The days chosen for the Custom tab (`null` until the user picks some), and
    // the date prompt that chooses them: open or not, the two typed texts, and
    // the message about the last attempt.
    var custom by remember { mutableStateOf<CustomSpan?>(null) }
    var promptOpen by remember { mutableStateOf(false) }
    var promptFrom by remember { mutableStateOf("") }
    var promptTo by remember { mutableStateOf("") }
    var promptError by remember { mutableStateOf<String?>(null) }

    // The device filter: every device in the database, and the chosen one
    // (`null` = all devices together).
    var devices by remember { mutableStateOf<List<String>>(emptyList()) }
    var device by remember { mutableStateOf<String?>(null) }

    // The app editor: the app being edited (`null` = closed), the categories to
    // offer, the message about the last save, and a counter bumped after a save
    // so the numbers reload with the new names.
    var editing by remember { mutableStateOf<AppSecs?>(null) }
    var editCategories by remember { mutableStateOf<List<String>>(emptyList()) }
    var editError by remember { mutableStateOf<String?>(null) }
    var edits by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()

    // Settings (saved on the phone), and the settings screen's state: shown or
    // not, how syncing has gone, the message about the last attempt, and whether
    // a sync is running right now.
    val settings = remember { AppSettings(context) }
    var showSettings by remember { mutableStateOf(false) }
    var enabled by remember { mutableStateOf(settings.enabled) }
    // The battery rules as saved, and why the app is paused right now (if it is).
    var battery by remember { mutableStateOf(settings.battery) }
    var pauseReason by remember { mutableStateOf<String?>(null) }
    // Asks Android's "allow notifications?" question (Android 13+). Whatever the
    // answer, pausing still works; without it the notice is simply not shown.
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    var syncStatus by remember { mutableStateOf<SyncStatus?>(null) }
    var syncMessage by remember { mutableStateOf<String?>(null) }
    var syncFailed by remember { mutableStateOf(false) }
    var syncBusy by remember { mutableStateOf(false) }
    // Phone usage: whether Android's Usage access is allowed (checked again each
    // time the app is opened, because the user grants it in another screen), and
    // the message about the last recording.
    val usageAllowed = remember(refreshKey) { UsageImporter.hasAccess(context) }
    var usageMessage by remember { mutableStateOf<String?>(null) }
    var usageBusy by remember { mutableStateOf(false) }
    // When usage was last read in this run, to avoid reading twice within a minute.
    var lastUsageImport by remember { mutableStateOf(0L) }
    // Debug builds can show made-up data and delete local data.
    val debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    // When the last automatic sync started, so opening the app twice in a row
    // does not sync twice.
    var lastAutoSync by remember { mutableStateOf(0L) }

    // The database is opened once and kept for the life of this screen.
    var database by remember { mutableStateOf<Database?>(null) }

    // Runs when the screen first appears and again whenever `refreshKey` changes.
    LaunchedEffect(refreshKey, period, offset, custom, device, edits) {
        try {
            // Disk work must not happen on the thread that draws the screen, so
            // it runs on the IO thread pool and this coroutine waits for it.
            // Copy the state first: the loading below runs on another thread, and
            // these values must not change underneath it.
            val loadedFor = period
            val loadedOffset = offset
            val loadedCustom = custom
            val loadedDevice = device
            val data = withContext(Dispatchers.IO) {
                val db = database ?: Database.open(
                    File(context.filesDir, "timesink.db").path,
                    // The device name typed on the settings screen.
                    settings.deviceName,
                    // The phone's own app names and categories are saved here.
                    File(context.filesDir, "apps.conf").path,
                )
                val zone = DeviceZone()
                val now = System.currentTimeMillis() / 1000L
                // Demo data: debug builds only, and only into an empty database.
                // Demo data only while real tracking is off, so it never mixes with real use.
                if (debuggable && !settings.enabled && db.isEmpty()) {
                    db.fillDemoData(now, DEMO_DAYS, DEMO_SEED, zone)
                }
                // The banner always shows the real today.
                val realToday = db.daySummary(0L, loadedDevice, now, zone)
                // The Today tab may show an earlier day; every other tab shows a period.
                val day = if (loadedFor == Period.TODAY && loadedOffset != 0L) {
                    db.daySummary(loadedOffset, loadedDevice, now, zone)
                } else {
                    realToday
                }
                val range = if (loadedFor == Period.TODAY) {
                    null
                } else {
                    db.periodSummary(loadedFor.toBridge(), loadedOffset, loadedCustom, loadedDevice, now, zone)
                }
                Loaded(db, realToday, day, range, db.devices(), db.syncStatus())
            }
            database = data.db
            devices = data.devices
            syncStatus = data.syncStatus
            // A device that disappeared goes back to the combined view.
            if (loadedDevice != null && loadedDevice !in data.devices) device = null
            summary = data.today
            shownDay = data.day
            shownRange = data.range
            shownFor = loadedFor
            error = null
        } catch (e: Exception) {
            // Show the problem on screen instead of crashing.
            error = e.explain()
        }
    }

    // Reads Android's usage log into the database (off the drawing thread) and
    // reloads the numbers if anything new was recorded. Needs the master switch
    // and Usage access. Returns without doing anything otherwise.
    suspend fun importUsage() {
        val db = database
        if (db == null || !settings.enabled || !usageAllowed || usageBusy) return
        usageBusy = true
        try {
            val recorded = withContext(Dispatchers.IO) { UsageImporter.importNow(context, db, settings) }
            lastUsageImport = System.currentTimeMillis()
            usageMessage = "Recorded $recorded stretch(es) of app use."
            if (recorded > 0) edits += 1
        } catch (e: Exception) {
            usageMessage = e.explain()
        }
        usageBusy = false
    }

    // Runs one sync with the saved server details, off the drawing thread, and
    // shows how it went. Does nothing if the master switch is off or a sync is
    // already running.
    fun runSync() {
        val db = database
        if (db == null || !settings.enabled || syncBusy) return
        syncBusy = true
        syncMessage = null
        scope.launch {
            try {
                val outcome = withContext(Dispatchers.IO) {
                    db.syncNow(
                        SyncServer(settings.serverUrl, settings.database, settings.user, settings.password),
                    )
                }
                syncFailed = false
                syncMessage = "Sent ${outcome.uploaded} day(s), received ${outcome.downloaded} day(s)." +
                    outcome.warnings.joinToString("") { "\n$it" }
            } catch (e: Exception) {
                syncFailed = true
                syncMessage = e.explain()
            }
            syncBusy = false
            // Reload so new data from other devices shows up.
            edits += 1
        }
    }

    // "Sync when I open the app": once the database is ready, and at most every
    // few minutes. Needs the master switch, the option, and filled-in details.
    LaunchedEffect(refreshKey, database) {
        // The master switch may have been flipped from the Quick Settings tile.
        enabled = settings.enabled
        // Check the battery first: when paused, say so (once) and do nothing automatic.
        pauseReason = BatteryGuard.checkAndNotify(context, settings)
        // Keep the background schedule in step with the settings.
        Background.reschedule(context)
        if (pauseReason != null) return@LaunchedEffect
        // Record the phone's own use, so the sync below sends it too.
        if (System.currentTimeMillis() - lastUsageImport > 60_000L) importUsage()
        val now = System.currentTimeMillis() / 1000L
        if (database != null && settings.enabled && settings.syncOnOpen && settings.syncConfigured &&
            now - lastAutoSync >= AUTO_SYNC_GAP_SECS
        ) {
            lastAutoSync = now
            runSync()
        }
    }

    // Make the colours and theme name available to every composable below.
    CompositionLocalProvider(
        LocalTimesinkColors provides colors,
        LocalThemeName provides palette.name,
        // Tapping an application row opens the editor for it.
        LocalAppTapped provides { app: AppSecs ->
            editCategories = database?.categories() ?: emptyList()
            editError = null
            editing = app
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.background)
                // Keep content clear of the status bar, navigation bar and camera
                // cut-out (the app draws edge to edge).
                .safeDrawingPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            // The settings screen replaces the dashboard while it is open.
            if (showSettings) {
                MonoText(text = "t i m e s i n k · settings", color = colors.accent, bold = true)
                Spacer(Modifier.height(8.dp))
                SettingsScreen(
                    enabled = enabled,
                    onEnabledChange = {
                        enabled = it
                        settings.enabled = it
                        // Starting or stopping the background job to match; a fresh
                        // start should not remember an old pause notice.
                        settings.pauseNotified = false
                        Background.reschedule(context)
                        pauseReason = if (it) BatteryGuard.pauseReason(context, settings) else null
                        // Turning tracking on records what the log already holds.
                        if (it) scope.launch { importUsage() }
                    },
                    initial = SyncDraft(
                        url = settings.serverUrl,
                        database = settings.database,
                        user = settings.user,
                        password = settings.password,
                        deviceName = settings.deviceName,
                        syncOnOpen = settings.syncOnOpen,
                    ),
                    status = syncStatus,
                    nowSecs = System.currentTimeMillis() / 1000L,
                    message = syncMessage,
                    messageIsError = syncFailed,
                    busy = syncBusy,
                    onSave = { draft ->
                        saveDraft(settings, draft)
                        // A new device name means a new database object; drop the old one.
                        database = null
                        edits += 1
                        syncMessage = "Saved."
                        syncFailed = false
                    },
                    onSyncNow = { draft ->
                        val renamed = AppSettings.cleanDeviceName(draft.deviceName) != settings.deviceName
                        saveDraft(settings, draft)
                        if (renamed) {
                            // Reopen under the new name; the sync starts after the reload.
                            database = null
                            edits += 1
                            syncMessage = "Saved. Press sync now again."
                            syncFailed = false
                        } else {
                            runSync()
                        }
                    },
                    pauseReason = pauseReason,
                    battery = battery,
                    onBattery = { options: BatteryOptions ->
                        val turnedOn = options.backgroundUpdates && !battery.backgroundUpdates
                        battery = options
                        settings.battery = options
                        Background.reschedule(context)
                        pauseReason = if (enabled) BatteryGuard.pauseReason(context, settings) else null
                        // Turning on background updates is when notifications matter, so ask now.
                        val needsAsk = Build.VERSION.SDK_INT >= 33 &&
                            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                            PackageManager.PERMISSION_GRANTED
                        if (turnedOn && needsAsk) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    },
                    usageAllowed = usageAllowed,
                    usageLastSecs = settings.usageCursorMs.takeIf { it > 0 }?.div(1000L),
                    usageMessage = usageMessage,
                    onAllowUsage = { context.startActivity(UsageImporter.accessSettingsIntent()) },
                    onRecordNow = { scope.launch { importUsage() } },
                    debug = debuggable,
                    onWipe = {
                        val old = database
                        database = null
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                // Close the open database, then remove its files.
                                old?.close()
                                for (suffix in listOf("", "-wal", "-shm")) {
                                    File(context.filesDir, "timesink.db$suffix").delete()
                                }
                                settings.resetUsage()
                            }
                            usageMessage = null
                            syncMessage = "Local data deleted."
                            syncFailed = false
                            edits += 1
                        }
                    },
                    onBack = { showSettings = false },
                )
                return@Column
            }

            // A small way into the settings, out of the way at the top right.
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                ActionButton(label = "settings", onClick = { showSettings = true })
            }
            // When paused for the battery, say so on the dashboard.
            if (enabled && pauseReason != null) {
                MonoText(text = "paused: $pauseReason", color = colors.warn, size = 12.sp)
                Spacer(Modifier.height(4.dp))
            }
            val today = summary
            if (today != null) {
                Banner(today)
                Spacer(Modifier.height(8.dp))
            } else {
                MonoText(text = "t i m e s i n k", color = colors.accent, bold = true)
            }
            PeriodTabs(
                selected = period,
                // Choosing a tab starts at its current period, not where the last
                // tab was stepped to.
                onSelect = {
                    period = it
                    offset = 0L
                    // The first time Custom is chosen there are no days yet, so ask.
                    if (it == Period.CUSTOM && custom == null) {
                        promptError = null
                        promptOpen = true
                    }
                },
            )
            DeviceChips(devices, device, onSelect = {
                device = it
                offset = 0L
            })
            Spacer(Modifier.height(8.dp))

            error?.let { MonoText(text = it, color = colors.danger) }

            // The app editor replaces the screen's content while it is open.
            editing?.let { app ->
                AppEditor(
                    app = app,
                    categories = editCategories,
                    error = editError,
                    onSave = { name, category ->
                        val db = database
                        if (db != null) {
                            scope.launch {
                                try {
                                    // Saving writes a file, so it runs off the drawing thread.
                                    withContext(Dispatchers.IO) { db.setApp(app.raws, name, category) }
                                    editing = null
                                    editError = null
                                    // Reload so every screen shows the new names.
                                    edits += 1
                                } catch (e: Exception) {
                                    editError = e.explain()
                                }
                            }
                        }
                    },
                    onCancel = {
                        editing = null
                        editError = null
                    },
                )
                Spacer(Modifier.height(8.dp))
            }

            // The date prompt replaces the screen's content while it is open.
            if (promptOpen && period == Period.CUSTOM) {
                DatePrompt(
                    from = promptFrom,
                    to = promptTo,
                    error = promptError,
                    onFromChange = { promptFrom = it },
                    onToChange = { promptTo = it },
                    onShow = {
                        try {
                            // Rust checks the dates with the same rules as the
                            // terminal app and turns them into a span of days.
                            custom = customRange(
                                promptFrom,
                                promptTo,
                                System.currentTimeMillis() / 1000L,
                                DeviceZone(),
                            )
                            offset = 0L
                            promptOpen = false
                            promptError = null
                        } catch (e: Exception) {
                            promptError = e.explain()
                        }
                    },
                    onCancel = {
                        promptOpen = false
                        promptError = null
                    },
                )
                Spacer(Modifier.height(8.dp))
            }

            val day = shownDay
            if (today != null && day != null) {
                if (period == Period.TODAY) {
                    StepBar(
                        label = day.label,
                        canBack = day.canBack,
                        canForward = day.canForward,
                        onBack = { if (day.canBack) offset -= 1 },
                        onForward = { if (day.canForward) offset += 1 },
                    )
                    TodayScreen(day)
                } else {
                    val range = shownRange
                    // Draw only numbers that were loaded for the tab on screen.
                    if (range != null && shownFor == period) {
                        StepBar(
                            label = range.label,
                            canBack = range.canBack,
                            canForward = range.canForward,
                            onBack = { if (range.canBack) offset -= 1 },
                            onForward = { if (range.canForward) offset += 1 },
                        )
                        // Custom also has a way back to the prompt, prefilled with the
                        // days now shown.
                        if (period == Period.CUSTOM && !promptOpen) {
                            ActionButton(
                                label = "choose dates",
                                onClick = {
                                    val shown = custom
                                    promptFrom = shown?.let { LocalDate.ofEpochDay(it.first).toString() } ?: ""
                                    promptTo = shown?.let { LocalDate.ofEpochDay(it.last).toString() } ?: ""
                                    promptError = null
                                    promptOpen = true
                                },
                            )
                        }
                        RangeScreen(range, today = today.day)
                    }
                }
            }
        }
    }
}

/** What one load of the screen produced: the open database and the numbers to show. */
private class Loaded(
    /** The open database, kept for later loads. */
    val db: Database,
    /** The real today, for the banner. */
    val today: DaySummary,
    /** The day for the Today tab (today, or an earlier day stepped back to). */
    val day: DaySummary,
    /** The numbers for any other tab, or `null` on the Today tab. */
    val range: PeriodSummary?,
    /** Every device that has data. */
    val devices: List<String>,
    /** How syncing has gone so far. */
    val syncStatus: SyncStatus,
)

/** Writes the edited sync details to the phone's saved settings. */
private fun saveDraft(settings: AppSettings, draft: SyncDraft) {
    settings.serverUrl = draft.url
    settings.database = draft.database
    settings.user = draft.user
    settings.password = draft.password
    settings.deviceName = draft.deviceName
    settings.syncOnOpen = draft.syncOnOpen
}
