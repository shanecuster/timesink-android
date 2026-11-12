package com.shanetalkstech.timesink.ui

import android.content.pm.ApplicationInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
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
import com.shanetalkstech.timesink.DeviceZone
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
import uniffi.timesink_ffi.customRange
import uniffi.timesink_ffi.defaultThemeName
import uniffi.timesink_ffi.listThemes

/** The name this phone is stored under in the data. A setting arrives with sync. */
private const val DEVICE_ID = "phone"

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
                    DEVICE_ID,
                    // The phone's own app names and categories are saved here.
                    File(context.filesDir, "apps.conf").path,
                )
                val zone = DeviceZone()
                val now = System.currentTimeMillis() / 1000L
                // Demo data: debug builds only, and only into an empty database.
                val debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
                if (debuggable && db.isEmpty()) {
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
                Loaded(db, realToday, day, range, db.devices())
            }
            database = data.db
            devices = data.devices
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
)
