package com.shanetalkstech.timesink.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import uniffi.timesink_ffi.defaultThemeName
import uniffi.timesink_ffi.listThemes

/**
 * The root of the screen. For now it only proves the whole chain works: the
 * Rust core is loaded, its colour themes are read, and the screen is painted
 * with the default one. The dashboard tabs arrive in the next steps.
 */
@Composable
fun AppRoot() {
    // `remember` keeps the value across redraws so the (cheap) call into Rust
    // is made once, not every time Compose repaints the screen.
    val palette = remember {
        val themes = listThemes()
        // The default theme by name; fall back to the first one if it is missing.
        themes.firstOrNull { it.name == defaultThemeName() } ?: themes.first()
    }
    val colors = remember(palette) { palette.toColors() }

    // Make the colours available to every composable below.
    CompositionLocalProvider(LocalTimesinkColors provides colors) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.background)
                // Keep text clear of the status bar, the navigation bar and a
                // camera cut-out (the app draws edge to edge).
                .safeDrawingPadding()
                .padding(16.dp),
        ) {
            // BasicText is Compose's plainest text widget; the style carries
            // the colour, font and size (no extra library is needed).
            BasicText(
                text = "timesink",
                style = TextStyle(
                    color = colors.accent,
                    fontFamily = MonoFont,
                    fontWeight = FontWeight.Bold,
                    fontSize = 24.sp,
                ),
            )
            BasicText(
                text = "theme: ${palette.name}",
                style = TextStyle(
                    color = colors.muted,
                    fontFamily = MonoFont,
                    fontSize = 14.sp,
                ),
            )
        }
    }
}
