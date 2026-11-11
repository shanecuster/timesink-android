package com.shanetalkstech.timesink.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The bar under the tabs that says which stretch of time is on screen and steps
 * back and forward through them: `‹  Nov 3-9, 2026  ›`. It is the touch version
 * of the terminal app's `[` and `]` keys.
 *
 * An arrow that cannot be used (nothing earlier was recorded, or this is already
 * the latest period) is drawn faint and ignores taps.
 *
 * @param label which days are shown, such as `Nov 3-9, 2026`.
 * @param canBack whether an earlier period exists.
 * @param canForward whether a later period exists.
 * @param onBack called when the left arrow is tapped (only if [canBack]).
 * @param onForward called when the right arrow is tapped (only if [canForward]).
 */
@Composable
fun StepBar(
    label: String,
    canBack: Boolean,
    canForward: Boolean,
    onBack: () -> Unit,
    onForward: () -> Unit,
) {
    val colors = LocalTimesinkColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Arrow(text = "‹", enabled = canBack, onClick = onBack)
        MonoText(text = label, color = colors.text, bold = true, size = 14.sp)
        Arrow(text = "›", enabled = canForward, onClick = onForward)
    }
}

/**
 * One arrow button of the [StepBar]. It is a big, finger-sized tap target even
 * though the arrow itself is small.
 */
@Composable
private fun Arrow(text: String, enabled: Boolean, onClick: () -> Unit) {
    val colors = LocalTimesinkColors.current
    Box(
        modifier = Modifier
            // `enabled = false` makes the box ignore taps.
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        MonoText(
            text = text,
            color = if (enabled) colors.accent else colors.border,
            bold = true,
            size = 24.sp,
        )
    }
}
