package com.meatsack.motivator.mobile.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.meatsack.motivator.mobile.data.SettingsDefaults
import com.meatsack.motivator.mobile.data.SettingsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = SettingsRepository(application)

    val dailyStepGoal = repo.dailyStepGoal.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(),
        SettingsDefaults.DAILY_STEP_GOAL,
    )
    val inactivityThreshold = repo.inactivityThreshold.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(),
        SettingsDefaults.INACTIVITY_THRESHOLD_MIN,
    )
    val activeHoursStart = repo.activeHoursStart.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(),
        SettingsDefaults.ACTIVE_HOURS_START,
    )
    val activeHoursEnd = repo.activeHoursEnd.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(),
        SettingsDefaults.ACTIVE_HOURS_END,
    )
    val quietHoursStart = repo.quietHoursStart.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(),
        SettingsDefaults.QUIET_HOURS_START,
    )
    val quietHoursEnd = repo.quietHoursEnd.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(),
        SettingsDefaults.QUIET_HOURS_END,
    )
    val contextAwareEnabled = repo.contextAwareEnabled.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(),
        SettingsDefaults.CONTEXT_AWARE_ENABLED,
    )
    val endOfDayHour = repo.endOfDayHour.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(),
        SettingsDefaults.END_OF_DAY_HOUR,
    )

    fun updateStepGoal(goal: Int) = viewModelScope.launch { repo.setDailyStepGoal(goal) }
    fun updateInactivityThreshold(min: Int) =
        viewModelScope.launch { repo.setInactivityThreshold(min) }
    fun updateActiveHours(start: Int, end: Int) =
        viewModelScope.launch { repo.setActiveHours(start, end) }
    fun updateQuietHours(start: Int, end: Int) =
        viewModelScope.launch { repo.setQuietHours(start, end) }
    fun toggleContextAware(enabled: Boolean) =
        viewModelScope.launch { repo.setContextAwareEnabled(enabled) }
    fun updateEndOfDayHour(hour: Int) = viewModelScope.launch { repo.setEndOfDayHour(hour) }
}
