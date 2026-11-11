package com.shanetalkstech.timesink.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import uniffi.timesink_ffi.AppSecs
import uniffi.timesink_ffi.NameSecs

/**
 * The "Categories" panel: one bar per category, biggest first, with the period's
 * total in the title. Used by every period's screen.
 *
 * @param totalSecs the period's total active time, shown in the title.
 * @param categories the rows, already sorted biggest first.
 * @param nameChars how many characters of a name fit (more on a wide screen).
 */
@Composable
fun CategoriesPanel(
    totalSecs: Long,
    categories: List<NameSecs>,
    nameChars: Int = NAME_COLUMN_CHARS,
) {
    val colors = LocalTimesinkColors.current
    TuiPanel(
        title = " Categories · ${formatDuration(totalSecs)} ",
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (categories.isEmpty()) {
            MonoText(text = "nothing recorded yet", color = colors.muted)
        }
        // Bars are scaled against the biggest category, wherever it is in the list.
        val max = categories.maxOfOrNull { it.secs } ?: 1L
        for (row in categories) {
            BarRow(row.name, row.secs, max, categoryColorFor(row.colorIndex), nameChars)
        }
    }
}

/**
 * The "Applications" panel: one bar per app, coloured by the app's category.
 *
 * @param apps the rows, already sorted biggest first.
 * @param nameChars how many characters of a name fit (more on a wide screen).
 */
@Composable
fun ApplicationsPanel(apps: List<AppSecs>, nameChars: Int = NAME_COLUMN_CHARS) {
    val colors = LocalTimesinkColors.current
    TuiPanel(title = " Applications ", modifier = Modifier.fillMaxWidth()) {
        if (apps.isEmpty()) {
            MonoText(text = "nothing recorded yet", color = colors.muted)
        }
        val max = apps.maxOfOrNull { it.secs } ?: 1L
        for (row in apps) {
            BarRow(row.name, row.secs, max, categoryColorFor(row.colorIndex), nameChars)
        }
    }
}

/**
 * The "Listening" panel: media played in the background, counted separately from
 * focus time.
 *
 * @param totalSecs total listening time, shown in the title.
 * @param players the rows, already sorted biggest first.
 * @param nameChars how many characters of a name fit (more on a wide screen).
 */
@Composable
fun ListeningPanel(
    totalSecs: Long,
    players: List<NameSecs>,
    nameChars: Int = NAME_COLUMN_CHARS,
) {
    val colors = LocalTimesinkColors.current
    TuiPanel(
        title = " Listening · ${formatDuration(totalSecs)} ",
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (players.isEmpty()) {
            MonoText(text = "no media in this period", color = colors.muted)
        }
        val max = players.maxOfOrNull { it.secs } ?: 1L
        for (row in players) {
            // Players have no category colour, so they use the banner-end colour.
            BarRow(row.name, row.secs, max, colors.bannerEnd, nameChars)
        }
    }
}
