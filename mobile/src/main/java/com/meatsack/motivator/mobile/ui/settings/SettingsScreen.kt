package com.meatsack.motivator.mobile.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.RichTooltip
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meatsack.motivator.mobile.ai.GenerationResult
import com.meatsack.motivator.mobile.ui.theme.LocalThemeChoice
import com.meatsack.motivator.mobile.ui.theme.ThemeChoice
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = viewModel(),
    onOpenDebug: () -> Unit = {},
) {
    val stepGoal by viewModel.dailyStepGoal.collectAsState()
    val inactivityThreshold by viewModel.inactivityThreshold.collectAsState()
    val movementSteps by viewModel.movementStepThreshold.collectAsState()
    val activeStart by viewModel.activeHoursStart.collectAsState()
    val activeEnd by viewModel.activeHoursEnd.collectAsState()
    val contextAware by viewModel.contextAwareEnabled.collectAsState()
    val theme by viewModel.themeChoice.collectAsState()
    val vitals = theme == ThemeChoice.VITALS

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Text(
            if (vitals) "CONFIG // SYSTEM" else "Settings 🎀",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            if (vitals) "CALIBRATE THE MACHINE" else "make it yours, girlypop",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(16.dp))

        ThemePicker(current = theme, onSelect = { viewModel.updateThemeChoice(it) })
        Spacer(Modifier.height(16.dp))

        SettingHeader(
            title = "Daily step goal: $stepGoal",
            info = "Your daily step target. Behind-pace and end-of-day checks " +
                "measure your progress against this number.",
        )
        Slider(
            value = stepGoal.toFloat(),
            onValueChange = { viewModel.updateStepGoal(it.toInt()) },
            valueRange = 2000f..30000f,
            steps = 27,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))

        SettingHeader(
            title = "Inactivity threshold: $inactivityThreshold min",
            info = "How long with too little movement before the watch fires an " +
                "inactivity insult.",
        )
        Slider(
            value = inactivityThreshold.toFloat(),
            onValueChange = { viewModel.updateInactivityThreshold(it.toInt()) },
            valueRange = 10f..120f,
            steps = 10,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))

        SettingHeader(
            title = "Movement threshold: $movementSteps steps",
            info = "Steps needed within your inactivity window to count as moving " +
                "and reset the timer.",
        )
        Slider(
            value = movementSteps.toFloat(),
            onValueChange = { viewModel.updateMovementStepThreshold(it.toInt()) },
            valueRange = 10f..500f,
            steps = 48, // 10..500 in increments of 10
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))

        SettingHeader(
            title = "Active hours: $activeStart:00 - $activeEnd:00",
            info = "Hours the watch can nag you. Outside this window it stays quiet.",
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
        Spacer(Modifier.height(16.dp))

        val behindPaceHour by viewModel.behindPaceCheckHour.collectAsState()
        SettingHeader(
            title = "Behind-pace check hour: $behindPaceHour:00",
            info = "Time of day to check whether you're behind your step goal.",
        )
        Slider(
            value = behindPaceHour.toFloat(),
            // coerceIn keeps the picked hour within setBehindPaceCheckHour's 0..23 validation.
            onValueChange = { viewModel.updateBehindPaceCheckHour(it.toInt().coerceIn(0, 23)) },
            // coerceAtLeast guards a zero-width active window (start == end), which would
            // otherwise be an empty valueRange and crash the Slider.
            valueRange = activeStart.toFloat()..(activeEnd.toFloat().coerceAtLeast(activeStart + 1f)),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))

        val behindPaceEnabled by viewModel.behindPaceEnabled.collectAsState()
        SettingHeader(
            title = "Behind-pace alerts",
            info = "When off, the watch won't nag you for falling behind your step " +
                "pace during the day.",
        ) {
            Switch(
                checked = behindPaceEnabled,
                onCheckedChange = { viewModel.toggleBehindPaceEnabled(it) },
            )
        }
        Spacer(Modifier.height(16.dp))

        val endOfDayEnabled by viewModel.endOfDayEnabled.collectAsState()
        SettingHeader(
            title = "End-of-day alerts",
            info = "When off, the watch won't nag you at the end of the day for " +
                "missing your step goal.",
        ) {
            Switch(
                checked = endOfDayEnabled,
                onCheckedChange = { viewModel.toggleEndOfDayEnabled(it) },
            )
        }
        Spacer(Modifier.height(24.dp))
        SettingHeader(title = "Anthropic API key")

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
            val savedLabel = if (vitals) "✓ ARMED" else "✓ Saved 💕"
            val unsetLabel = if (vitals) "NOT SET" else "Not set"
            Text(
                if (apiKeyPresent) savedLabel else unsetLabel,
                style = MaterialTheme.typography.bodySmall,
                color = if (apiKeyPresent) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }

        Spacer(Modifier.height(16.dp))
        val genStatus by viewModel.generationStatus.collectAsState()
        Button(
            onClick = { viewModel.generateNow() },
            enabled = apiKeyPresent,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (vitals) "> GENERATE 20 // 5 PER LEVEL" else "Generate 20 new insults (5 per level) 💌")
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
        SettingHeader(
            title = "Context-aware language",
            info = "When on, uses cleaner language during work hours. When off, " +
                "full send all day. Set the work-safe window below.",
        ) {
            Switch(
                checked = contextAware,
                onCheckedChange = { viewModel.toggleContextAware(it) },
            )
        }
        Spacer(Modifier.height(8.dp))
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

        // Entry point to the on-device diagnostics log (retained dev tool). See
        // docs/debug/triggering-investigation.md.
        Spacer(Modifier.height(24.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = onOpenDebug, modifier = Modifier.fillMaxWidth()) {
            Text("🐛 Trigger debug log")
        }
        Spacer(Modifier.height(16.dp))
    }
}

/** Segmented control to switch the app's visual theme. */
@Composable
private fun ThemePicker(current: ThemeChoice, onSelect: (ThemeChoice) -> Unit) {
    val vitals = current == ThemeChoice.VITALS
    Text(
        if (vitals) "APP THEME" else "App theme",
        style = MaterialTheme.typography.titleMedium,
    )
    Spacer(Modifier.height(8.dp))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(percent = 50))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(4.dp),
    ) {
        ThemeChoice.entries.forEach { choice ->
            val selected = choice == current
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(
                        if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                    )
                    .clickable(role = Role.Button) { onSelect(choice) }
                    .padding(vertical = 10.dp),
            ) {
                Text(
                    text = choice.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

/**
 * A control header: the label (UPPERCASED for the Vitals theme, sentence case for
 * Bubblegum), an optional tap-to-reveal info "ⓘ" whose popup holds the explanation,
 * and an optional [trailing] slot (e.g. a Switch) pinned to the end of the row.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingHeader(
    title: String,
    info: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val displayTitle = if (LocalThemeChoice.current == ThemeChoice.VITALS) title.uppercase() else title
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(displayTitle, style = MaterialTheme.typography.titleMedium)
        if (info != null) {
            val tooltipState = rememberTooltipState(isPersistent = true)
            val scope = rememberCoroutineScope()
            TooltipBox(
                positionProvider = TooltipDefaults.rememberRichTooltipPositionProvider(),
                tooltip = {
                    RichTooltip(
                        colors = TooltipDefaults.richTooltipColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                        ),
                    ) {
                        Text(info, style = MaterialTheme.typography.bodyMedium)
                    }
                },
                state = tooltipState,
            ) {
                IconButton(
                    onClick = { scope.launch { tooltipState.show() } },
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Info,
                        contentDescription = "About $title",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
        if (trailing != null) {
            Spacer(Modifier.weight(1f))
            trailing()
        }
    }
}
