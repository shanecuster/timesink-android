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
val TWO_COLUMNS_DP = 600.dp

/**
 * The Today screen: where one day's time went.
 *
 * On a narrow screen everything stacks in one scrolling column. On a wide one
 * (an unfolded foldable, a tablet) it splits in two like the terminal app:
 * categories and applications on the left, the day's timeline and listening on
 * the right.
 *
 * @param summary the day's numbers from Rust.
 */
@Composable
fun TodayScreen(summary: DaySummary) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        if (maxWidth >= TWO_COLUMNS_DP) {
            Row(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                Column(modifier = Modifier.weight(0.5f).padding(end = 6.dp)) {
                    CategoriesPanel(summary.totalSecs, summary.categories, WIDE_NAME_COLUMN_CHARS)
                    Spacer(Modifier.height(8.dp))
                    ApplicationsPanel(summary.apps, WIDE_NAME_COLUMN_CHARS)
                    if (summary.sites.isNotEmpty()) Spacer(Modifier.height(8.dp))
                    SitesPanel(summary.sites, WIDE_NAME_COLUMN_CHARS)
                }
                Column(modifier = Modifier.weight(0.5f).padding(start = 6.dp)) {
                    TimelinePanel(summary)
                    Spacer(Modifier.height(8.dp))
                    ListeningPanel(summary.listeningSecs, summary.listening, WIDE_NAME_COLUMN_CHARS)
                }
            }
        } else {
            Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                CategoriesPanel(summary.totalSecs, summary.categories)
                Spacer(Modifier.height(8.dp))
                ApplicationsPanel(summary.apps)
                if (summary.sites.isNotEmpty()) Spacer(Modifier.height(8.dp))
                SitesPanel(summary.sites)
                Spacer(Modifier.height(8.dp))
                TimelinePanel(summary)
                Spacer(Modifier.height(8.dp))
                ListeningPanel(summary.listeningSecs, summary.listening)
            }
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
