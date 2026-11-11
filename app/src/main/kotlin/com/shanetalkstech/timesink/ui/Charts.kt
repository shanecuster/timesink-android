package com.shanetalkstech.timesink.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import uniffi.timesink_ffi.TimelineSpan

/** Seconds in a day, the width of the timeline strip. */
private const val DAY_SECS = 86_400f

/**
 * One list row: `name  ██████░░░░  1h 05m`. The bar fills the space between the
 * name and the time, and its length is [secs] compared with [max] (the biggest
 * value in the list), so the biggest row always fills the whole bar.
 *
 * @param name the label on the left. A name longer than [nameChars] is cut short
 *   and ends in an ellipsis (`…`) so it is clear something is missing.
 * @param secs the time this row stands for.
 * @param max the biggest time in the list, for scaling.
 * @param color the bar colour, usually the category's colour.
 * @param nameChars how many characters the name column holds. Wide screens pass
 *   a larger number so fewer names are cut.
 */
@Composable
fun BarRow(
    name: String,
    secs: Long,
    max: Long,
    color: Color,
    nameChars: Int = NAME_COLUMN_CHARS,
) {
    val colors = LocalTimesinkColors.current
    // The share of the bar to fill, from 0.0 to 1.0. `coerceIn` keeps it valid
    // even if the numbers are odd, and a tiny share still shows a sliver.
    val share = if (max > 0) (secs.toFloat() / max).coerceIn(0f, 1f) else 0f
    Row(
        modifier = Modifier.fillMaxWidth().height(22.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The name column has a fixed width so all the bars start at the same x.
        // Each character of the monospace font is about 8dp wide at this size.
        MonoText(
            text = ellipsize(name, nameChars),
            color = colors.text,
            modifier = Modifier.width((nameChars * CHAR_WIDTH_DP + NAME_GAP_DP).dp),
        )
        // The bar: an empty track with the filled part drawn over it.
        Box(modifier = Modifier.weight(1f).height(10.dp).background(colors.border)) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(share.coerceAtLeast(if (secs > 0) 0.02f else 0f))
                    .background(color),
            )
        }
        // Right-aligned time: padded to a fixed number of characters, which lines
        // up because the font is monospace.
        MonoText(
            text = formatDuration(secs).padStart(TIME_COLUMN_CHARS),
            color = colors.muted,
            modifier = Modifier.width(TIME_COLUMN_WIDTH_DP.dp),
        )
    }
}

/** How many characters of a name the name column shows by default (a phone). */
const val NAME_COLUMN_CHARS = 12

/** How many characters of a name the column shows on a wide screen. */
const val WIDE_NAME_COLUMN_CHARS = 16

/** Width of one character of the 13sp monospace text, in dp (rounded up). */
private const val CHAR_WIDTH_DP = 8

/** Extra space after the name column, so the bar does not touch the text. */
private const val NAME_GAP_DP = 6

/** How many characters wide the time column is (`10h 05m` is the longest). */
private const val TIME_COLUMN_CHARS = 7

/** Width of the time column, with a small gap on its left. */
private const val TIME_COLUMN_WIDTH_DP = 62

/**
 * Cuts [text] to at most [chars] characters, ending it with `…` when something
 * was removed, so a long name never looks like a complete one.
 */
fun ellipsize(text: String, chars: Int): String =
    if (text.length <= chars) text else text.take((chars - 1).coerceAtLeast(1)) + "…"

/**
 * The hour-by-hour bar chart: 24 bars, one per hour of the day, as tall as that
 * hour was busy compared with the busiest hour. Busy hours shade from the theme's
 * accent colour toward its banner-end colour, like the terminal app's sparkline.
 *
 * @param hours exactly 24 values: active seconds in each hour.
 * @param height how tall the tallest bar is.
 */
@Composable
fun HourlyChart(hours: List<Long>, height: androidx.compose.ui.unit.Dp) {
    val colors = LocalTimesinkColors.current
    val max = hours.maxOrNull()?.coerceAtLeast(1L) ?: 1L
    Row(
        modifier = Modifier.fillMaxWidth().height(height),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        for (secs in hours) {
            // How full this hour is, 0.0 to 1.0.
            val level = (secs.toFloat() / max).coerceIn(0f, 1f)
            Box(
                modifier = Modifier
                    .weight(1f)
                    // An empty hour is a stub; any activity is at least a little taller.
                    .fillMaxHeight(if (secs <= 0) 0.06f else 0.12f + 0.88f * level)
                    .background(
                        if (secs <= 0) colors.border else lerp(colors.accent, colors.bannerEnd, level),
                    ),
            )
        }
    }
}

/**
 * The hour labels under the hourly chart: `00 06 12 18 24`, spread evenly so
 * each sits under its hour.
 */
@Composable
fun HourAxis() {
    val colors = LocalTimesinkColors.current
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        for (label in listOf("00", "06", "12", "18", "24")) {
            MonoText(text = label, color = colors.muted, size = 11.sp)
        }
    }
}

/**
 * One strip showing the whole day left to right (midnight to midnight), with
 * each stretch of activity drawn in its category's colour and a thin marker at
 * the current time.
 *
 * @param spans the stretches, in seconds after local midnight.
 * @param nowSecs seconds since local midnight right now.
 */
@Composable
fun TimelineStrip(spans: List<TimelineSpan>, nowSecs: Long) {
    val colors = LocalTimesinkColors.current
    // Work out each span's colour up front; `categoryColorFor` can only be called
    // from composable code, not from inside the drawing callback below.
    val spanColors = spans.map { categoryColorFor(it.colorIndex) }
    Canvas(modifier = Modifier.fillMaxWidth().height(22.dp)) {
        // The empty track behind everything.
        drawRect(color = colors.border, size = size)
        for ((i, span) in spans.withIndex()) {
            val left = size.width * (span.start / DAY_SECS)
            // At least one pixel wide so a very short stretch is still visible.
            val width = (size.width * ((span.end - span.start) / DAY_SECS)).coerceAtLeast(1f)
            drawRect(color = spanColors[i], topLeft = Offset(left, 0f), size = Size(width, size.height))
        }
        // The "now" marker.
        val nowX = size.width * (nowSecs / DAY_SECS)
        drawRect(color = colors.text, topLeft = Offset(nowX, 0f), size = Size(2f, size.height))
    }
}
