package com.shanetalkstech.timesink.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The panel for choosing the days of the Custom period: two typed dates, like the
 * terminal app's `c` prompt.
 *
 * Dates are written `YYYY-MM-DD`. The last day may be left empty, which means
 * "until today". The checking itself is done in Rust (`customRange`), so the
 * phone and the terminal app accept exactly the same input.
 *
 * @param from the text of the first-day field.
 * @param to the text of the last-day field.
 * @param error a message about the last attempt, or `null` when there is none.
 * @param onFromChange called with the new text whenever the first-day field changes.
 * @param onToChange called with the new text whenever the last-day field changes.
 * @param onShow called when the user taps "show".
 * @param onCancel called when the user taps "cancel".
 */
@Composable
fun DatePrompt(
    from: String,
    to: String,
    error: String?,
    onFromChange: (String) -> Unit,
    onToChange: (String) -> Unit,
    onShow: () -> Unit,
    onCancel: () -> Unit,
) {
    val colors = LocalTimesinkColors.current
    TuiPanel(title = " Choose the days ", modifier = Modifier.fillMaxWidth()) {
        MonoText(
            text = "Type dates as YYYY-MM-DD. Leave the last day empty for \"until today\".",
            color = colors.muted,
            size = 12.sp,
        )
        Spacer(Modifier.height(10.dp))
        DateField(label = "from", value = from, onChange = onFromChange)
        Spacer(Modifier.height(6.dp))
        DateField(label = "to  ", value = to, onChange = onToChange)
        if (error != null) {
            Spacer(Modifier.height(8.dp))
            MonoText(text = error, color = colors.danger, size = 12.sp)
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton(label = "show", onClick = onShow)
            ActionButton(label = "cancel", onClick = onCancel)
        }
    }
}

/**
 * One labelled text box of the [DatePrompt]: `from [ 2026-10-01 ]`.
 *
 * `BasicTextField` is Compose's plainest text input (no Material look), so it can
 * be styled like the rest of the app: monospace text in a thin box.
 */
@Composable
private fun DateField(label: String, value: String, onChange: (String) -> Unit) {
    val colors = LocalTimesinkColors.current
    Row {
        MonoText(text = label, color = colors.muted, size = 14.sp)
        Spacer(Modifier.width(10.dp))
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            // A plain text keyboard: the number pad on some phones has no "-".
            keyboardOptions = KeyboardOptions.Default,
            textStyle = TextStyle(
                color = colors.text,
                fontFamily = MonoFont,
                fontSize = 14.sp,
                fontFeatureSettings = NO_LIGATURES,
            ),
            // The colour of the blinking text cursor.
            cursorBrush = SolidColor(colors.accent),
            modifier = Modifier
                .border(width = 1.dp, color = colors.border)
                .padding(horizontal = 8.dp, vertical = 6.dp)
                .width(150.dp),
        )
    }
}
