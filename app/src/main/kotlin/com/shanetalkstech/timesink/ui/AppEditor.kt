package com.shanetalkstech.timesink.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import uniffi.timesink_ffi.AppSecs

/** The category every unlisted app gets; always offered in the editor. */
private const val OTHER = "Other"

/**
 * The editor for one application's name and category, opened by tapping an app
 * row. It is the phone version of the terminal app's `a` flow: change the short
 * name, then pick a category from the list or type a new one.
 *
 * Nothing recorded is changed; only the names shown. The change is saved to the
 * phone's own list and applies to all history.
 *
 * @param app the application being edited.
 * @param categories the categories already in use, in colour order.
 * @param error a message about the last save attempt, or `null`.
 * @param onSave called with the new name and category when "save" is tapped.
 * @param onCancel called when "cancel" is tapped.
 */
@Composable
fun AppEditor(
    app: AppSecs,
    categories: List<String>,
    error: String?,
    onSave: (name: String, category: String) -> Unit,
    onCancel: () -> Unit,
) {
    val colors = LocalTimesinkColors.current
    // The edits so far. They start as the app's current name and category.
    var name by remember(app) { mutableStateOf(app.name) }
    var chosen by remember(app) { mutableStateOf(app.category) }
    var typed by remember(app) { mutableStateOf("") }

    TuiPanel(title = " Edit application ", modifier = Modifier.fillMaxWidth()) {
        MonoText(text = app.raws.joinToString(", "), color = colors.muted, size = 11.sp)
        Spacer(Modifier.height(8.dp))
        LabeledField(label = "name    ", value = name, onChange = { name = it })
        Spacer(Modifier.height(8.dp))
        MonoText(text = "category", color = colors.muted, size = 12.sp)
        // The known categories, then "Other"; the chosen one is marked with an arrow.
        for (option in (categories + OTHER).distinct()) {
            val on = typed.isBlank() && option == chosen
            MonoText(
                text = (if (on) "› " else "  ") + option,
                color = if (on) colors.accent else colors.text,
                bold = on,
                size = 14.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        chosen = option
                        // Picking from the list drops any half-typed new category.
                        typed = ""
                    }
                    .padding(vertical = 7.dp),
            )
        }
        Spacer(Modifier.height(4.dp))
        LabeledField(label = "new     ", value = typed, onChange = { typed = it })
        if (error != null) {
            Spacer(Modifier.height(8.dp))
            MonoText(text = error, color = colors.danger, size = 12.sp)
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // A typed category wins over the list, like the terminal app's "new category".
            ActionButton(label = "save", onClick = { onSave(name, typed.ifBlank { chosen }) })
            ActionButton(label = "cancel", onClick = onCancel)
        }
    }
}
