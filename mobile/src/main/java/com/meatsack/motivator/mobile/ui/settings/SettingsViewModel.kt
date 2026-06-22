package com.meatsack.motivator.mobile.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.meatsack.motivator.mobile.ai.AiMessageGenerator
import com.meatsack.motivator.mobile.ai.ApiKeyStore
import com.meatsack.motivator.mobile.ai.GenerationResult
import com.meatsack.motivator.mobile.data.SettingsRepository
import com.meatsack.shared.constants.EscalationLevel
import com.meatsack.shared.constants.MessageTone
import com.meatsack.shared.constants.TriggerType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Calendar
import com.meatsack.shared.sync.SettingsDefaults as SharedDefaults

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = SettingsRepository(application)
    private val apiKeyStore = ApiKeyStore(application)

    private val _apiKeyPresent = MutableStateFlow(apiKeyStore.hasKey())
    val apiKeyPresent: StateFlow<Boolean> = _apiKeyPresent.asStateFlow()

    private val _generationStatus = MutableStateFlow<GenerationResult?>(null)
    val generationStatus: StateFlow<GenerationResult?> = _generationStatus.asStateFlow()

    val dailyStepGoal = repo.dailyStepGoal.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(),
        SharedDefaults.DAILY_STEP_GOAL,
    )
    val inactivityThreshold = repo.inactivityThreshold.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(),
        SharedDefaults.INACTIVITY_THRESHOLD_MIN,
    )
    val activeHoursStart = repo.activeHoursStart.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(),
        SharedDefaults.ACTIVE_HOURS_START,
    )
    val activeHoursEnd = repo.activeHoursEnd.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(),
        SharedDefaults.ACTIVE_HOURS_END,
    )
    val contextAwareEnabled = repo.contextAwareEnabled.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(),
        SharedDefaults.CONTEXT_AWARE_ENABLED,
    )
    val behindPaceCheckHour = repo.behindPaceCheckHour.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(),
        SharedDefaults.BEHIND_PACE_CHECK_HOUR,
    )
    val behindPaceEnabled = repo.behindPaceEnabled.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(),
        SharedDefaults.BEHIND_PACE_ENABLED,
    )
    val endOfDayEnabled = repo.endOfDayEnabled.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(),
        SharedDefaults.END_OF_DAY_ENABLED,
    )
    val contextAwareStart = repo.contextAwareStart.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(),
        SharedDefaults.CONTEXT_AWARE_START,
    )
    val contextAwareEnd = repo.contextAwareEnd.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(),
        SharedDefaults.CONTEXT_AWARE_END,
    )

    fun updateStepGoal(goal: Int) = viewModelScope.launch { repo.setDailyStepGoal(goal) }
    fun updateInactivityThreshold(min: Int) =
        viewModelScope.launch { repo.setInactivityThreshold(min) }
    fun updateActiveHours(start: Int, end: Int) =
        viewModelScope.launch { repo.setActiveHours(start, end) }
    fun toggleContextAware(enabled: Boolean) =
        viewModelScope.launch { repo.setContextAwareEnabled(enabled) }
    fun updateContextAwareStart(hour: Int) =
        viewModelScope.launch { repo.setContextAwareStart(hour) }
    fun updateContextAwareEnd(hour: Int) =
        viewModelScope.launch { repo.setContextAwareEnd(hour) }
    fun updateBehindPaceCheckHour(hour: Int) =
        viewModelScope.launch { repo.setBehindPaceCheckHour(hour) }
    fun toggleBehindPaceEnabled(enabled: Boolean) =
        viewModelScope.launch { repo.setBehindPaceEnabled(enabled) }
    fun toggleEndOfDayEnabled(enabled: Boolean) =
        viewModelScope.launch { repo.setEndOfDayEnabled(enabled) }

    fun saveApiKey(key: String) {
        if (key.isBlank()) apiKeyStore.clear() else apiKeyStore.save(key)
        _apiKeyPresent.value = apiKeyStore.hasKey()
    }

    fun generateNow() = viewModelScope.launch {
        _generationStatus.value = null
        // v2 scope: generate at SAVAGE/INACTIVITY/FULL_SEND. Users who want
        // other axes can run multiple generations.
        // try/catch guards against MessageSerializer.serialize's `require {}`
        // throwing through generateBatch — without this the UI gets stuck in
        // "generating…" forever on an unhandled exception.
        _generationStatus.value = try {
            val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            val generator = AiMessageGenerator(getApplication())
            generator.generateBatch(
                level = EscalationLevel.SAVAGE,
                trigger = TriggerType.INACTIVITY,
                tone = MessageTone.FULL_SEND,
                hourOfDay = hour,
                currentSteps = 0,
            )
        } catch (t: Throwable) {
            GenerationResult.Failed(t)
        }
    }
}
