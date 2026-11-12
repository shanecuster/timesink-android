package com.shanetalkstech.timesink.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The device filter: a row like `[ all ] laptop deck phone`, with the chosen one
 * highlighted. It is drawn only when more than one device has data (after sync),
 * because with a single device there is nothing to choose.
 *
 * @param devices every device name in the database.
 * @param selected the chosen device, or `null` for all devices together.
 * @param onSelect called with the new choice (`null` means all).
 */
@Composable
fun DeviceChips(devices: List<String>, selected: String?, onSelect: (String?) -> Unit) {
    if (devices.size < 2) return
    val colors = LocalTimesinkColors.current
    // The row scrolls sideways if there are more devices than fit.
    Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        // `null` stands for "all"; the real devices follow in database order.
        for (choice in listOf<String?>(null) + devices) {
            val on = choice == selected
            MonoText(
                text = if (on) "[ ${choice ?: "all"} ]" else "  ${choice ?: "all"}  ",
                color = if (on) colors.accent else colors.muted,
                bold = on,
                size = 14.sp,
                modifier = Modifier.clickable { onSelect(choice) }.padding(horizontal = 4.dp, vertical = 10.dp),
            )
        }
    }
}
