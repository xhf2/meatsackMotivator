package com.meatsack.motivator.mobile.ui.debug

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * On-device diagnostics Debug screen (retained dev tool). Shows the watch's diagnostic log
 * newest-first in a monospace list, with Refresh / Clear / Share. Reached from the bottom of
 * Settings. First built for docs/debug/triggering-investigation.md.
 */
@Composable
fun DebugScreen(
    onBack: () -> Unit,
    viewModel: DebugViewModel = viewModel(),
) {
    val lines by viewModel.lines.collectAsState()
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        Text(
            "TRIGGER DEBUG LOG",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "${lines.size} lines · newest first · pushed from the watch each poll",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { viewModel.refresh() }) { Text("Refresh") }
            OutlinedButton(
                onClick = {
                    val share = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        // Share oldest-first so the exported log reads top-to-bottom in time.
                        putExtra(Intent.EXTRA_TEXT, lines.asReversed().joinToString("\n"))
                    }
                    context.startActivity(Intent.createChooser(share, "Share diagnostics"))
                },
                enabled = lines.isNotEmpty(),
            ) { Text("Share") }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { viewModel.clear() }) { Text("Clear") }
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onBack) { Text("← Back") }
        Spacer(Modifier.height(8.dp))

        if (lines.isEmpty()) {
            Text(
                "No diagnostics received yet. Make sure the watch app is running and the " +
                    "phone is paired, then tap Refresh.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(lines) { line ->
                    Text(
                        line,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 1.dp),
                    )
                }
            }
        }
    }
}
