package com.shanetalkstech.timesink.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import uniffi.timesink_ffi.DaySummary

/** The spaced-out program name, as in the terminal app's banner. */
private const val NAME = "t i m e s i n k"

/** Screens at least this wide put the hour chart beside the name; narrower ones stack it below. */
private val WIDE_BANNER_DP = 520.dp

/**
 * The title banner: the spaced-out name, today's date and week number, and a small
 * chart of how busy each hour of today was. It follows the terminal app's banner
 * and is laid out to fit a small phone too:
 *
 * ```
 *  wide screen:  t i m e s i n k                 ▁▂▅▇█▆▃▂ ...
 *                Wednesday November 11 2026 ·     00  06  12  18  24
 *                Week 46
 *
 *  narrow:       t i m e s i n k
 *                Wednesday November 11 2026 · Week 46
 *                ▁▂▅▇█▆▃▂ ...
 *                00   06   12   18   24
 * ```
 *
 * @param summary today's numbers (the day and the hours come from it).
 */
@Composable
fun Banner(summary: DaySummary) {
    val colors = LocalTimesinkColors.current
    BoxWithConstraints(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        // `maxWidth` is how much room the banner has; it decides the layout.
        if (maxWidth >= WIDE_BANNER_DP) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                Column {
                    MonoText(text = NAME, color = colors.accent, bold = true, size = 28.sp)
                    MonoText(text = dateLine(summary.day), color = colors.muted, size = 13.sp)
                }
                Column(modifier = Modifier.width(260.dp)) {
                    HourlyChart(hours = summary.hours, height = 40.dp)
                    HourAxis()
                }
            }
        } else {
            Column(modifier = Modifier.fillMaxWidth()) {
                MonoText(text = NAME, color = colors.accent, bold = true, size = 26.sp)
                MonoText(text = dateLine(summary.day), color = colors.muted, size = 12.sp)
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    HourlyChart(hours = summary.hours, height = 34.dp)
                    HourAxis()
                }
            }
        }
    }
}
