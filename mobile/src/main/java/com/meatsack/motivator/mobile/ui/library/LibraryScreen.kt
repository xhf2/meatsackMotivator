package com.meatsack.motivator.mobile.ui.library

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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meatsack.motivator.mobile.sync.PhoneSyncSender
import com.meatsack.shared.model.Message
import kotlinx.coroutines.launch

@Composable
fun LibraryScreen(viewModel: LibraryViewModel = viewModel()) {
    val messages by viewModel.messages.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        Text("Insult Library", style = MaterialTheme.typography.headlineMedium)
        Text("${messages.size} messages", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(12.dp))

        Button(
            onClick = {
                scope.launch {
                    val synced = PhoneSyncSender(context).syncMessagesToWatch()
                    val msg = if (synced > 0) "Synced $synced messages" else "Sync failed or empty"
                    snackbarHostState.showSnackbar(msg)
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Sync to Watch")
        }
        Spacer(Modifier.height(12.dp))

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f),
        ) {
            items(messages) { message ->
                MessageCard(message)
            }
        }

        SnackbarHost(hostState = snackbarHostState)
    }
}

@Composable
private fun MessageCard(message: Message) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = message.text,
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "L${message.level.value} | ${message.triggerType.name} | ${message.source.name}",
                    style = MaterialTheme.typography.labelSmall,
                )
                Text(
                    "\uD83D\uDC4D ${message.votesUp}  \uD83D\uDC4E ${message.votesDown}",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}
