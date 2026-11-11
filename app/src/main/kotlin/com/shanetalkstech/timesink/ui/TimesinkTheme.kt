package com.shanetalkstech.timesink.ui

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.shanetalkstech.timesink.R
import uniffi.timesink_ffi.FfiException
import uniffi.timesink_ffi.RgbColor
import uniffi.timesink_ffi.ThemePalette

/**
 * The app's monospace font: JetBrains Mono, bundled in `res/font` (SIL Open
 * Font License; see `third_party/fonts/README.md`).
 *
 * Every character is the same width, which is what makes box borders line up
 * like they do in a terminal. Two weights are registered: regular and bold.
 */
val MonoFont: FontFamily = FontFamily(
    Font(R.font.jetbrainsmono_regular, FontWeight.Normal),
    Font(R.font.jetbrainsmono_bold, FontWeight.Bold),
)

/**
 * Converts a colour from the Rust side into a Compose [Color].
 *
 * Rust sends each channel as an unsigned byte (`UByte`, 0..255). Compose's
 * `Color(red, green, blue)` constructor wants plain `Int`s in the same range,
 * so each channel is widened with `toInt()`.
 */
fun RgbColor.toColor(): Color = Color(r.toInt(), g.toInt(), b.toInt())

/**
 * The same colour "slots" as the terminal app's `Theme`, but as Compose colours.
 * UI code asks for a slot by name (never a hard-coded colour), so switching
 * theme repaints everything.
 */
data class TimesinkColors(
    /** Fill behind everything. */
    val background: Color,
    /** Normal text. */
    val text: Color,
    /** Quiet text: hints, axis labels, empty-state messages. */
    val muted: Color,
    /** Lines of the boxes drawn around panels, and empty bar tracks. */
    val border: Color,
    /** Highlights: the selected tab and the title. */
    val accent: Color,
    /** The far end of gradients. */
    val bannerEnd: Color,
    /** A faint fill colour (empty heat-map cells). */
    val selection: Color,
    /** Good / busy. */
    val ok: Color,
    /** Attention. */
    val warn: Color,
    /** Problems. */
    val danger: Color,
    /** Colours for the category and application bars, in order. */
    val accents: List<Color>,
)

/** Builds the Compose colour set from a palette received from Rust. */
fun ThemePalette.toColors(): TimesinkColors = TimesinkColors(
    background = background.toColor(),
    text = text.toColor(),
    muted = muted.toColor(),
    border = border.toColor(),
    accent = accent.toColor(),
    bannerEnd = bannerEnd.toColor(),
    selection = selection.toColor(),
    ok = ok.toColor(),
    warn = warn.toColor(),
    danger = danger.toColor(),
    accents = accents.map { it.toColor() },
)

/**
 * A "CompositionLocal": a value any composable can read without it being passed
 * down through every function parameter. The root screen provides the current
 * theme here; panels and text read it with `LocalTimesinkColors.current`.
 *
 * The default (used only if nothing provides a value) is plain grey-on-black.
 */
val LocalTimesinkColors = compositionLocalOf {
    TimesinkColors(
        background = Color.Black,
        text = Color.White,
        muted = Color.LightGray,
        border = Color.Gray,
        accent = Color.Cyan,
        bannerEnd = Color.Yellow,
        selection = Color.DarkGray,
        ok = Color.Green,
        warn = Color.Yellow,
        danger = Color.Red,
        accents = listOf(Color.Cyan, Color.Yellow, Color.Green, Color.Magenta),
    )
}

/**
 * Turns any error into a short message for the screen.
 *
 * Errors from Rust arrive as [FfiException.Failed] with the readable text in
 * `detail`. Anything else (for example the native library failing to load)
 * falls back to its class name and message so the problem is visible instead
 * of crashing the app.
 */
fun Throwable.explain(): String =
    (this as? FfiException.Failed)?.detail ?: "${this::class.simpleName}: ${this.message}"
