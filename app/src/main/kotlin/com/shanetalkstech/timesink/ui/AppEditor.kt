package com.shanetalkstech.timesink.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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

/** The category every unlisted app gets; always offered in the editor. */
private const val OTHER = "Other"

/**
 * What the editor is changing: one row of the Applications or Sites panel.
 *
 * @param name the short name shown now.
 * @param category the category it is in now.
 * @param ids what the saved lines are keyed by: Android package names for an app,
 *   the registered site (such as `github.com`) for a website.
 * @param isSite true for a website row, so it is saved to the site list instead.
 */
data class EditTarget(
    val name: String,
    val category: String,
    val ids: List<String>,
    val isSite: Boolean,
)

/**
 * The editor for one application's or website's name and category, opened by tapping an app
 * row. It is the phone version of the terminal app's `a` flow: change the short
 * name, then pick a category from the wrapping chips or type a new one.
 *
 * Nothing recorded is changed; only the names shown. The change is saved to the
 * phone's own list and applies to all history.
 *
 * @param target the application or website being edited.
 * @param categories the categories already in use, in colour order.
 * @param error a message about the last save attempt, or `null`.
 * @param onSave called with the new name and category when "save" is tapped.
 * @param onCancel called when "cancel" is tapped.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppEditor(
    target: EditTarget,
    categories: List<String>,
    error: String?,
    onSave: (name: String, category: String) -> Unit,
    onCancel: () -> Unit,
) {
    val colors = LocalTimesinkColors.current
    // The edits so far. They start as the app's current name and category.
    var name by remember(target) { mutableStateOf(target.name) }
    var chosen by remember(target) { mutableStateOf(target.category) }
    var typed by remember(target) { mutableStateOf("") }

    TuiPanel(
        title = if (target.isSite) " Edit website " else " Edit application ",
        modifier = Modifier.fillMaxWidth(),
    ) {
        // The editor sits above a screen that does not scroll for it, so it
        // scrolls itself when it is taller than this, however many categories
        // there come to be.
        Column(modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
            MonoText(text = target.ids.joinToString(", "), color = colors.muted, size = 11.sp)
            Spacer(Modifier.height(8.dp))
            LabeledField(label = "name    ", value = name, onChange = { name = it })
            Spacer(Modifier.height(8.dp))
            MonoText(text = "category", color = colors.muted, size = 12.sp)
            // The known categories, then "Other", as chips that wrap onto as many
            // lines as needed; the chosen one is highlighted in brackets.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (option in (categories + OTHER).distinct()) {
                    val on = typed.isBlank() && option == chosen
                    MonoText(
                        text = if (on) "[$option]" else " $option ",
                        color = if (on) colors.accent else colors.text,
                        bold = on,
                        size = 14.sp,
                        modifier = Modifier
                            .clickable {
                                chosen = option
                                // Picking from the list drops any half-typed new category.
                                typed = ""
                            }
                            .padding(horizontal = 4.dp, vertical = 9.dp),
                    )
                }
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
}
