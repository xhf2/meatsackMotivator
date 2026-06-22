package com.meatsack.motivator.mobile.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meatsack.motivator.mobile.ai.GenerationResult

@Composable
fun SettingsScreen(viewModel: SettingsViewModel = viewModel()) {
    val stepGoal by viewModel.dailyStepGoal.collectAsState()
    val inactivityThreshold by viewModel.inactivityThreshold.collectAsState()
    val activeStart by viewModel.activeHoursStart.collectAsState()
    val activeEnd by viewModel.activeHoursEnd.collectAsState()
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
            "Active hours: $activeStart:00 - $activeEnd:00",
            style = MaterialTheme.typography.titleMedium,
        )
        RangeSlider(
            value = activeStart.toFloat()..activeEnd.toFloat(),
            onValueChange = { range ->
                viewModel.updateActiveHours(range.start.toInt(), range.endInclusive.toInt())
            },
            valueRange = 0f..23f,
            steps = 22,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "Hours the watch can nag you. Outside this window it stays quiet.",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(16.dp))

        val behindPaceHour by viewModel.behindPaceCheckHour.collectAsState()
        Text(
            "Behind-pace check hour: $behindPaceHour:00",
            style = MaterialTheme.typography.titleMedium,
        )
        Slider(
            value = behindPaceHour.toFloat(),
            onValueChange = { viewModel.updateBehindPaceCheckHour(it.toInt().coerceIn(0, 23)) },
            // Guard against a zero-width active window (start == end), which would make an
            // empty valueRange and crash the Slider; coerceIn on the value keeps the picked
            // hour valid for setBehindPaceCheckHour's 0..23 validation.
            valueRange = activeStart.toFloat()..activeEnd.toFloat().coerceAtLeast(activeStart + 1f),
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "Time of day to check whether you're behind your step goal.",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(16.dp))

        val behindPaceEnabled by viewModel.behindPaceEnabled.collectAsState()
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                "Behind-pace messages",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = behindPaceEnabled,
                onCheckedChange = { viewModel.toggleBehindPaceEnabled(it) },
            )
        }
        Text(
            "When off, the watch won't nag you for falling behind your step pace during the day.",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(16.dp))

        val endOfDayEnabled by viewModel.endOfDayEnabled.collectAsState()
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                "End-of-day messages",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = endOfDayEnabled,
                onCheckedChange = { viewModel.toggleEndOfDayEnabled(it) },
            )
        }
        Text(
            "When off, the watch won't nag you at the end of the day for missing your step goal.",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(24.dp))
        Text("Anthropic API key", style = MaterialTheme.typography.titleMedium)

        val apiKeyPresent by viewModel.apiKeyPresent.collectAsState()
        var draftKey by remember { mutableStateOf("") }
        OutlinedTextField(
            value = draftKey,
            onValueChange = { draftKey = it },
            label = { Text(if (apiKeyPresent) "Replace key" else "Paste key") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = {
                    viewModel.saveApiKey(draftKey)
                    draftKey = ""
                },
                enabled = draftKey.isNotBlank(),
            ) { Text("Save") }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = { viewModel.saveApiKey("") }) { Text("Clear") }
            Spacer(Modifier.weight(1f))
            Text(
                if (apiKeyPresent) "✓ Saved" else "Not set",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Spacer(Modifier.height(16.dp))
        val genStatus by viewModel.generationStatus.collectAsState()
        Button(
            onClick = { viewModel.generateNow() },
            enabled = apiKeyPresent,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Generate 10 new insults")
        }
        genStatus?.let {
            val text = when (it) {
                is GenerationResult.Success -> "Generated ${it.messages.size} messages"
                GenerationResult.NoApiKey -> "No API key set"
                is GenerationResult.HttpError -> "HTTP ${it.status}"
                is GenerationResult.Failed -> "Error: ${it.error.message}"
            }
            Text(text, style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.height(24.dp))
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
        val ctxStart by viewModel.contextAwareStart.collectAsState()
        val ctxEnd by viewModel.contextAwareEnd.collectAsState()
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = ctxStart.toString(),
                onValueChange = { v ->
                    v.toIntOrNull()?.let { viewModel.updateContextAwareStart(it.coerceIn(0, 23)) }
                },
                label = { Text("Work-safe start") },
                enabled = contextAware,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.width(140.dp),
            )
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(
                value = ctxEnd.toString(),
                onValueChange = { v ->
                    v.toIntOrNull()?.let { viewModel.updateContextAwareEnd(it.coerceIn(0, 23)) }
                },
                label = { Text("Work-safe end") },
                enabled = contextAware,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.width(140.dp),
            )
        }
    }
}
