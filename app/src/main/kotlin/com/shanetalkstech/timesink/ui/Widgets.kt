package com.shanetalkstech.timesink.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The OpenType font settings that switch off JetBrains Mono's "ligatures"
 * (joined-up letter pairs such as `->` becoming an arrow). Names and numbers in
 * the app should always show exactly the characters they contain. `liga` and
 * `calt` are the two features that do the joining; `0` turns each off.
 */
const val NO_LIGATURES: String = "liga 0, calt 0"

/**
 * Text in the app's monospace font. Every piece of text in the app goes
 * through this, so the font and the no-ligature rule are set in one place.
 *
 * @param text the words to show.
 * @param color the colour, taken from a theme slot by the caller.
 * @param modifier optional layout tweaks from the caller.
 * @param bold whether to use the bold weight.
 * @param size the font size.
 * @param centered when true the text is centred within the width it is given
 *   (use together with a modifier that fills the width).
 */
@Composable
fun MonoText(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    bold: Boolean = false,
    size: TextUnit = 13.sp,
    centered: Boolean = false,
) {
    BasicText(
        text = text,
        style = TextStyle(
            color = color,
            fontFamily = MonoFont,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            fontSize = size,
            textAlign = if (centered) TextAlign.Center else TextAlign.Start,
            fontFeatureSettings = NO_LIGATURES,
        ),
        modifier = modifier,
    )
}

/**
 * A tappable `[ label ]`, the terminal way of drawing a button.
 *
 * @param label the words inside the brackets.
 * @param onClick called when it is tapped.
 * @param modifier optional layout tweaks from the caller.
 */
@Composable
fun ActionButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalTimesinkColors.current
    MonoText(
        text = "[ $label ]",
        color = colors.accent,
        bold = true,
        size = 14.sp,
        // The padding makes the tap target bigger than the text itself.
        modifier = modifier.clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 10.dp),
    )
}

/**
 * A tappable on/off row drawn like a terminal checkbox: `[x] label` or `[ ] label`.
 *
 * @param label the words next to the box.
 * @param checked whether it is on.
 * @param onChange called with the new value when tapped.
 * @param modifier optional layout tweaks from the caller.
 */
@Composable
fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalTimesinkColors.current
    MonoText(
        text = (if (checked) "[x] " else "[ ] ") + label,
        color = if (checked) colors.accent else colors.text,
        bold = checked,
        size = 14.sp,
        modifier = modifier.clickable { onChange(!checked) }.padding(vertical = 10.dp),
    )
}
