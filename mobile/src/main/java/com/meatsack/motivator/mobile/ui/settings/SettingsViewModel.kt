package com.meatsack.motivator.mobile.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.meatsack.motivator.mobile.data.SettingsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = SettingsRepository(application)

    val dailyStepGoal =
        repo.dailyStepGoal.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), 10_000)
    val inactivityThreshold =
        repo.inactivityThreshold.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), 30)
    val activeHoursStart =
        repo.activeHoursStart.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), 7)
    val activeHoursEnd =
        repo.activeHoursEnd.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), 22)
    val quietHoursStart =
        repo.quietHoursStart.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), 22)
    val quietHoursEnd =
        repo.quietHoursEnd.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), 7)
    val contextAwareEnabled =
        repo.contextAwareEnabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), false)

    fun updateStepGoal(goal: Int) = viewModelScope.launch { repo.setDailyStepGoal(goal) }
    fun updateInactivityThreshold(min: Int) =
        viewModelScope.launch { repo.setInactivityThreshold(min) }
    fun updateActiveHours(start: Int, end: Int) =
        viewModelScope.launch { repo.setActiveHours(start, end) }
    fun updateQuietHours(start: Int, end: Int) =
        viewModelScope.launch { repo.setQuietHours(start, end) }
    fun toggleContextAware(enabled: Boolean) =
        viewModelScope.launch { repo.setContextAwareEnabled(enabled) }
}
