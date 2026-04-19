package com.meatsack.motivator.mobile.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun SettingsScreen(viewModel: SettingsViewModel = viewModel()) {
    val stepGoal by viewModel.dailyStepGoal.collectAsState()
    val inactivityThreshold by viewModel.inactivityThreshold.collectAsState()
    val activeStart by viewModel.activeHoursStart.collectAsState()
    val activeEnd by viewModel.activeHoursEnd.collectAsState()
    val quietStart by viewModel.quietHoursStart.collectAsState()
    val quietEnd by viewModel.quietHoursEnd.collectAsState()
    val contextAware by viewModel.contextAwareEnabled.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))

        Text("Daily Step Goal: $stepGoal", style = MaterialTheme.typography.titleMedium)
        Slider(
            value = stepGoal.toFloat(),
            onValueChange = { viewModel.updateStepGoal(it.toInt()) },
            valueRange = 2000f..30000f,
            steps = 27,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))

        Text(
            "Inactivity Threshold: $inactivityThreshold min",
            style = MaterialTheme.typography.titleMedium,
        )
        Slider(
            value = inactivityThreshold.toFloat(),
            onValueChange = { viewModel.updateInactivityThreshold(it.toInt()) },
            valueRange = 10f..120f,
            steps = 10,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))

        Text(
            "Active Hours: ${activeStart}:00 - ${activeEnd}:00",
            style = MaterialTheme.typography.titleMedium,
        )
        Text("Start", style = MaterialTheme.typography.bodySmall)
        Slider(
            value = activeStart.toFloat(),
            onValueChange = { viewModel.updateActiveHours(it.toInt(), activeEnd) },
            valueRange = 0f..23f,
            steps = 22,
            modifier = Modifier.fillMaxWidth(),
        )
        Text("End", style = MaterialTheme.typography.bodySmall)
        Slider(
            value = activeEnd.toFloat(),
            onValueChange = { viewModel.updateActiveHours(activeStart, it.toInt()) },
            valueRange = 0f..23f,
            steps = 22,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))

        Text(
            "Quiet Hours: ${quietStart}:00 - ${quietEnd}:00",
            style = MaterialTheme.typography.titleMedium,
        )
        Text("Start", style = MaterialTheme.typography.bodySmall)
        Slider(
            value = quietStart.toFloat(),
            onValueChange = { viewModel.updateQuietHours(it.toInt(), quietEnd) },
            valueRange = 0f..23f,
            steps = 22,
            modifier = Modifier.fillMaxWidth(),
        )
        Text("End", style = MaterialTheme.typography.bodySmall)
        Slider(
            value = quietEnd.toFloat(),
            onValueChange = { viewModel.updateQuietHours(quietStart, it.toInt()) },
            valueRange = 0f..23f,
            steps = 22,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                "Context-aware language",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = contextAware,
                onCheckedChange = { viewModel.toggleContextAware(it) },
            )
        }
        Text(
            "When on, uses cleaner language during work hours. When off, full send all day.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
