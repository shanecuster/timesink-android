package com.shanetalkstech.timesink.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import uniffi.timesink_ffi.DaySummary

/** Screens at least this wide show two columns (an unfolded foldable, a tablet). */
private val TWO_COLUMNS_DP = 600.dp

/**
 * The Today screen: where today's time went.
 *
 * On a narrow screen everything stacks in one scrolling column. On a wide one
 * (an unfolded foldable, a tablet) it splits in two like the terminal app:
 * categories and applications on the left, the day's timeline and listening on
 * the right.
 *
 * @param summary today's numbers from Rust.
 */
@Composable
fun TodayScreen(summary: DaySummary) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        if (maxWidth >= TWO_COLUMNS_DP) {
            Row(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                Column(modifier = Modifier.weight(0.5f).padding(end = 6.dp)) {
                    CategoriesPanel(summary)
                    Spacer(Modifier.height(8.dp))
                    ApplicationsPanel(summary)
                }
                Column(modifier = Modifier.weight(0.5f).padding(start = 6.dp)) {
                    TimelinePanel(summary)
                    Spacer(Modifier.height(8.dp))
                    ListeningPanel(summary)
                }
            }
        } else {
            Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                CategoriesPanel(summary)
                Spacer(Modifier.height(8.dp))
                ApplicationsPanel(summary)
                Spacer(Modifier.height(8.dp))
                TimelinePanel(summary)
                Spacer(Modifier.height(8.dp))
                ListeningPanel(summary)
            }
        }
    }
}

/** The "Categories" panel: one bar per category, biggest first, with today's total in the title. */
@Composable
private fun CategoriesPanel(summary: DaySummary) {
    val colors = LocalTimesinkColors.current
    TuiPanel(
        title = " Categories · ${formatDuration(summary.totalSecs)} ",
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (summary.categories.isEmpty()) {
            MonoText(text = "nothing recorded yet", color = colors.muted)
        }
        // Bars are scaled against the biggest category, wherever it is in the list.
        val max = summary.categories.maxOfOrNull { it.secs } ?: 1L
        for (row in summary.categories) {
            BarRow(row.name, row.secs, max, categoryColorFor(row.colorIndex))
        }
    }
}

/** The "Applications" panel: one bar per app, coloured by the app's category. */
@Composable
private fun ApplicationsPanel(summary: DaySummary) {
    val colors = LocalTimesinkColors.current
    TuiPanel(title = " Applications ", modifier = Modifier.fillMaxWidth()) {
        if (summary.apps.isEmpty()) {
            MonoText(text = "nothing recorded yet", color = colors.muted)
        }
        val max = summary.apps.maxOfOrNull { it.secs } ?: 1L
        for (row in summary.apps) {
            BarRow(row.name, row.secs, max, categoryColorFor(row.colorIndex))
        }
    }
}

/** The "Timeline" panel: the whole day on one strip, coloured by category. */
@Composable
private fun TimelinePanel(summary: DaySummary) {
    val colors = LocalTimesinkColors.current
    TuiPanel(title = " Timeline ", modifier = Modifier.fillMaxWidth()) {
        TimelineStrip(spans = summary.timeline, nowSecs = summary.nowSecs)
        Spacer(Modifier.height(4.dp))
        HourAxis()
        if (summary.timeline.isEmpty()) {
            MonoText(text = "nothing recorded yet", color = colors.muted)
        }
    }
}

/** The "Listening" panel: media played in the background, counted separately from focus time. */
@Composable
private fun ListeningPanel(summary: DaySummary) {
    val colors = LocalTimesinkColors.current
    TuiPanel(
        title = " Listening · ${formatDuration(summary.listeningSecs)} ",
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (summary.listening.isEmpty()) {
            MonoText(text = "no media today", color = colors.muted)
        }
        val max = summary.listening.maxOfOrNull { it.secs } ?: 1L
        for (row in summary.listening) {
            // Players have no category colour, so they use the banner-end colour.
            BarRow(row.name, row.secs, max, colors.bannerEnd)
        }
    }
}
