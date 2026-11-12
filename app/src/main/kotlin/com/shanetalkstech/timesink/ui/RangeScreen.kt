package com.shanetalkstech.timesink.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import uniffi.timesink_ffi.DayInfo
import uniffi.timesink_ffi.MonthInfo
import uniffi.timesink_ffi.PeriodSummary

/** Seconds in an hour, the most one hour cell can hold. */
private const val HOUR_SECS = 3_600L

/**
 * The screen for every period longer than a day (7 days, 30 days, year, all time,
 * calendar week, quarter and custom).
 *
 * The main panel is a heat map of days by hour, or, when there are too many days
 * to list, a bar per month. Beside or below it sit the category, application and
 * listening panels. On a narrow screen everything stacks; on a wide one (an
 * unfolded foldable) the map is on the left and the lists on the right, like the
 * terminal app.
 *
 * @param summary the period's numbers from Rust.
 * @param today the local day number of today, so today's heat-map row can be bold.
 */
@Composable
fun RangeScreen(summary: PeriodSummary, today: Long) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        if (maxWidth >= TWO_COLUMNS_DP) {
            Row(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                Column(modifier = Modifier.weight(0.62f).padding(end = 6.dp)) {
                    MainPanel(summary, today)
                    Spacer(Modifier.height(8.dp))
                    ListeningPanel(summary.listeningSecs, summary.listening, WIDE_NAME_COLUMN_CHARS)
                }
                Column(modifier = Modifier.weight(0.38f).padding(start = 6.dp)) {
                    CategoriesPanel(summary.totalSecs, summary.categories, WIDE_NAME_COLUMN_CHARS)
                    Spacer(Modifier.height(8.dp))
                    ApplicationsPanel(summary.apps, WIDE_NAME_COLUMN_CHARS)
                    if (summary.sites.isNotEmpty()) Spacer(Modifier.height(8.dp))
                    SitesPanel(summary.sites, WIDE_NAME_COLUMN_CHARS)
                }
            }
        } else {
            Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                MainPanel(summary, today)
                Spacer(Modifier.height(8.dp))
                CategoriesPanel(summary.totalSecs, summary.categories)
                Spacer(Modifier.height(8.dp))
                ApplicationsPanel(summary.apps)
                if (summary.sites.isNotEmpty()) Spacer(Modifier.height(8.dp))
                SitesPanel(summary.sites)
                Spacer(Modifier.height(8.dp))
                ListeningPanel(summary.listeningSecs, summary.listening)
            }
        }
    }
}

/** The big panel: the month bars for long periods, the day-by-hour heat map otherwise. */
@Composable
private fun MainPanel(summary: PeriodSummary, today: Long) {
    if (summary.byMonth) {
        MonthsPanel(summary)
    } else {
        HeatmapPanel(summary.days, today)
    }
}

/**
 * The heat map: one row per day, 24 coloured cells for the hours, and the day's
 * total on the right. A cell runs from the theme's faint fill (nothing) to its
 * bright "ok" colour (the busiest hour on the screen).
 *
 * @param days the days, oldest first.
 * @param today the local day number of today (drawn bold).
 */
@Composable
private fun HeatmapPanel(days: List<DayInfo>, today: Long) {
    val colors = LocalTimesinkColors.current
    TuiPanel(title = " Active time by day and hour ", modifier = Modifier.fillMaxWidth()) {
        if (days.isEmpty()) {
            MonoText(text = "nothing recorded yet", color = colors.muted)
            return@TuiPanel
        }
        // The busiest single hour anywhere on screen sets the brightest colour.
        val busiest = days.maxOf { day -> day.hours.maxOrNull() ?: 0L }.coerceAtLeast(1L)

        // Hour labels, one every three hours. The cell area is split into eight
        // equal parts so each label sits over its three cells.
        Row(modifier = Modifier.fillMaxWidth()) {
            Spacer(Modifier.width(LABEL_WIDTH_DP.dp))
            Row(modifier = Modifier.weight(1f)) {
                for (hour in 0 until 24 step 3) {
                    MonoText(
                        text = hour.toString(),
                        color = colors.muted,
                        size = 11.sp,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Spacer(Modifier.width(TOTAL_WIDTH_DP.dp))
        }
        Spacer(Modifier.height(2.dp))

        for (day in days) {
            Row(
                modifier = Modifier.fillMaxWidth().height(ROW_HEIGHT_DP.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MonoText(
                    text = dayLabel(day.day),
                    color = if (day.day == today) colors.text else colors.muted,
                    bold = day.day == today,
                    size = 12.sp,
                    modifier = Modifier.width(LABEL_WIDTH_DP.dp),
                )
                // One canvas per row, drawing the 24 cells with a hair of gap.
                Canvas(modifier = Modifier.weight(1f).height((ROW_HEIGHT_DP - 3).dp)) {
                    val cell = size.width / 24f
                    for ((hour, secs) in day.hours.withIndex()) {
                        drawRect(
                            color = heatColor(secs, busiest, colors),
                            topLeft = Offset(hour * cell, 0f),
                            size = Size(cell - 1f, size.height),
                        )
                    }
                }
                MonoText(
                    text = formatDuration(day.total).padStart(7),
                    color = colors.muted,
                    size = 12.sp,
                    modifier = Modifier.width(TOTAL_WIDTH_DP.dp),
                )
            }
        }
    }
}

/** Width of the date label column of the heat map (`Mon 09`). */
private const val LABEL_WIDTH_DP = 52

/** Width of the daily-total column of the heat map. */
private const val TOTAL_WIDTH_DP = 58

/** Height of one heat-map row. */
private const val ROW_HEIGHT_DP = 17

/**
 * The colour of one heat-map cell: the theme's faint fill when nothing happened,
 * otherwise a blend toward the bright "ok" colour in proportion to how busy the
 * hour was. Any activity at all is at least a quarter of the way, so a quiet
 * hour is still visible.
 */
private fun heatColor(secs: Long, busiest: Long, colors: TimesinkColors) =
    if (secs <= 0) {
        colors.selection
    } else {
        val t = (secs.toFloat() / busiest).coerceIn(0f, 1f)
        lerp(colors.selection, colors.ok, 0.25f + 0.75f * t)
    }

/**
 * The month bars for long periods: one bar per calendar month, newest first.
 * The title gives the average over the days that had any activity, so a quiet
 * month does not drag it down.
 */
@Composable
private fun MonthsPanel(summary: PeriodSummary) {
    val colors = LocalTimesinkColors.current
    val active = summary.activeDays
    val title = if (active > 0) {
        " By month · $active active days · avg ${formatDuration(summary.totalSecs / active)}/day "
    } else {
        " By month "
    }
    TuiPanel(title = title, modifier = Modifier.fillMaxWidth()) {
        if (summary.months.isEmpty()) {
            MonoText(text = "nothing recorded yet", color = colors.muted)
        }
        val max = summary.months.maxOfOrNull { it.total } ?: 1L
        // Newest month first.
        for (month: MonthInfo in summary.months.asReversed()) {
            BarRow(
                name = monthLabel(month.year, month.month.toInt()),
                secs = month.total,
                max = max,
                color = colors.accent,
                nameChars = 8,
            )
        }
    }
}
