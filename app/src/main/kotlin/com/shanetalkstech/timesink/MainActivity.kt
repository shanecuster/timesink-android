package com.shanetalkstech.timesink

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.shanetalkstech.timesink.ui.AppRoot

/**
 * The app's one and only Android "Activity": the screen the launcher opens.
 *
 * In Android, an Activity is the entry point for a screen. Everything you see
 * is drawn by Jetpack Compose, a toolkit where the UI is described by ordinary
 * Kotlin functions marked `@Composable`, so this class has almost nothing to
 * do: it just hands the screen over to Compose.
 */
class MainActivity : ComponentActivity() {

    /**
     * Counts how many times the app has come back to the front. It is Compose
     * state, so the screen reloads its numbers whenever it goes up.
     */
    private var resumes by mutableIntStateOf(0)

    /**
     * Called by Android once, when the screen is created.
     *
     * [savedInstanceState] holds state Android saved if the screen is being
     * recreated (for example after the phone is rotated or a foldable is
     * opened). We do not need it directly, but we must pass it up to the
     * parent class.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Draw behind the status bar and the navigation bar so the theme's
        // background colour fills the whole screen, like a terminal would.
        // (AppRoot adds padding so text never sits under the bars.)
        enableEdgeToEdge()

        // `setContent` gives Compose the root of the screen. Everything from
        // here down is `@Composable` functions.
        setContent { AppRoot(refreshKey = resumes) }
    }

    /**
     * Called by Android each time the screen comes to the front (including the
     * first time). Bumping the counter makes the numbers load again, so the
     * screen is current when you open the app.
     */
    override fun onResume() {
        super.onResume()
        resumes++
    }
}
