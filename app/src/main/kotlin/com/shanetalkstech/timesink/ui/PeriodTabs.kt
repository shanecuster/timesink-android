package com.shanetalkstech.timesink.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The periods the dashboard can show, in the same order as the terminal app's
 * tabs. Only [TODAY] has a screen so far; the others arrive in the next step.
 */
enum class Period(val label: String) {
    /** Today so far. */
    TODAY("Today"),

    /** The last 7 days. */
    WEEK("7 days"),

    /** The last 30 days. */
    MONTH("30 days"),

    /** The last year, summed by month. */
    YEAR("Year"),

    /** Everything recorded. */
    ALL("All time"),

    /** The current calendar week (Monday to Sunday). */
    CAL_WEEK("Cal. week"),

    /** The current calendar quarter. */
    QUARTER("Quarter"),

    /** Days chosen by the user. */
    CUSTOM("Custom"),
}

/**
 * The row of period tabs. It scrolls sideways, because eight tabs do not fit on
 * a phone's width; on a wide screen they all show. The [selected] tab is bold in
 * the accent colour with a line under it, the others muted.
 *
 * @param selected the tab to highlight.
 * @param onSelect called with the tab that was tapped.
 */
@Composable
fun PeriodTabs(selected: Period, onSelect: (Period) -> Unit) {
    val colors = LocalTimesinkColors.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            for (period in Period.entries) {
                val isSelected = period == selected
                Column(
                    modifier = Modifier
                        .clickable { onSelect(period) }
                        // A generous tap target, as a fingertip is bigger than a cursor.
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    MonoText(
                        text = period.label,
                        color = if (isSelected) colors.accent else colors.muted,
                        bold = isSelected,
                        size = 14.sp,
                    )
                    // The underline: the accent colour when selected, otherwise nothing.
                    Box(
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .fillMaxWidth()
                            .height(2.dp)
                            .background(if (isSelected) colors.accent else colors.background),
                    )
                }
            }
        }
        // The thin line under the whole bar.
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(colors.border))
    }
}
