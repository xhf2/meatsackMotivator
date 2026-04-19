package com.meatsack.motivator.mobile.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("meatsack_settings")

class SettingsRepository(private val context: Context) {

    companion object {
        val DAILY_STEP_GOAL = intPreferencesKey("daily_step_goal")
        val INACTIVITY_THRESHOLD = intPreferencesKey("inactivity_threshold_min")
        val ACTIVE_HOURS_START = intPreferencesKey("active_hours_start")
        val ACTIVE_HOURS_END = intPreferencesKey("active_hours_end")
        val QUIET_HOURS_START = intPreferencesKey("quiet_hours_start")
        val QUIET_HOURS_END = intPreferencesKey("quiet_hours_end")
        val CONTEXT_AWARE_ENABLED = booleanPreferencesKey("context_aware_enabled")
    }

    val dailyStepGoal: Flow<Int> = context.dataStore.data.map { it[DAILY_STEP_GOAL] ?: 10_000 }
    val inactivityThreshold: Flow<Int> = context.dataStore.data.map { it[INACTIVITY_THRESHOLD] ?: 30 }
    val activeHoursStart: Flow<Int> = context.dataStore.data.map { it[ACTIVE_HOURS_START] ?: 7 }
    val activeHoursEnd: Flow<Int> = context.dataStore.data.map { it[ACTIVE_HOURS_END] ?: 22 }
    val quietHoursStart: Flow<Int> = context.dataStore.data.map { it[QUIET_HOURS_START] ?: 22 }
    val quietHoursEnd: Flow<Int> = context.dataStore.data.map { it[QUIET_HOURS_END] ?: 7 }
    val contextAwareEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[CONTEXT_AWARE_ENABLED] ?: false }

    suspend fun setDailyStepGoal(goal: Int) {
        context.dataStore.edit { it[DAILY_STEP_GOAL] = goal }
    }

    suspend fun setInactivityThreshold(minutes: Int) {
        context.dataStore.edit { it[INACTIVITY_THRESHOLD] = minutes }
    }

    suspend fun setActiveHours(start: Int, end: Int) {
        context.dataStore.edit {
            it[ACTIVE_HOURS_START] = start
            it[ACTIVE_HOURS_END] = end
        }
    }

    suspend fun setQuietHours(start: Int, end: Int) {
        context.dataStore.edit {
            it[QUIET_HOURS_START] = start
            it[QUIET_HOURS_END] = end
        }
    }

    suspend fun setContextAwareEnabled(enabled: Boolean) {
        context.dataStore.edit { it[CONTEXT_AWARE_ENABLED] = enabled }
    }
}
