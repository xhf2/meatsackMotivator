package com.meatsack.motivator.mobile.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.meatsack.shared.constants.GenerationLimits.RETIRE_DOWNVOTES

/** Display labels shared by both themes; Vitals upper-cases them. */
private fun LibraryFilter.label(): String = when (this) {
    LibraryFilter.ACTIVE -> "Active"
    LibraryFilter.ARCHIVED -> "Archived"
    LibraryFilter.RETIRED -> "Retired"
}

/**
 * Three selectable chips with counts. `selected` semantics + Role.Tab so TalkBack
 * reads "Active, 74, selected, tab".
 */
@Composable
internal fun LibraryFilterChips(
    selected: LibraryFilter,
    counts: Map<LibraryFilter, Int>,
    bubblegum: Boolean,
    onSelect: (LibraryFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LibraryFilter.entries.forEach { f ->
            val isSelected = f == selected
            val count = counts[f] ?: 0
            val text = if (bubblegum) "${f.label()} $count" else "[ ${f.label().uppercase()} $count ]"
            val shape = if (bubblegum) RoundedCornerShape(percent = 50) else RoundedCornerShape(6.dp)
            val fg = when {
                isSelected && bubblegum -> MaterialTheme.colorScheme.onPrimary
                isSelected -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            val bg = if (isSelected && bubblegum) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface
            val outline = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 40.dp)
                    .background(bg, shape)
                    .border(1.dp, outline, shape)
                    .semantics { this.selected = isSelected }
                    .clickable(onClick = { onSelect(f) }, role = Role.Tab, onClickLabel = "Show ${f.label().lowercase()}")
                    .padding(horizontal = 8.dp, vertical = 8.dp),
            ) {
                Text(text = text, style = MaterialTheme.typography.labelMedium, color = fg, maxLines = 1)
            }
        }
    }
}

/**
 * "Delete all retired (N)" bar. Callers show it only on the Retired chip when N > 0
 * (see LibraryScreen). Bulk and permanent, so it confirms via AlertDialog before
 * calling [onConfirmed].
 */
@Composable
internal fun DeleteAllRetiredBar(
    count: Int,
    bubblegum: Boolean,
    onConfirmed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirming by remember { mutableStateOf(false) }
    val shape = if (bubblegum) RoundedCornerShape(percent = 50) else RoundedCornerShape(8.dp)
    val color = MaterialTheme.colorScheme.error
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .border(1.dp, color, shape)
            .clickable(onClick = { confirming = true }, role = Role.Button, onClickLabel = "Delete all retired")
            .padding(horizontal = 14.dp, vertical = 13.dp),
    ) {
        Text(
            text = if (bubblegum) "Delete all $count retired 🗑" else "> DELETE ALL RETIRED ($count)",
            style = MaterialTheme.typography.labelLarge,
            color = color,
        )
    }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Delete $count retired insults?") },
            text = { Text("They can never fire again ($RETIRE_DOWNVOTES+ downvotes). This removes them from the phone permanently. Archive any you want to keep first.") },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    onConfirmed()
                }) { Text("Delete", color = color) }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("Cancel") } },
        )
    }
}
