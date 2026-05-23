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
        val END_OF_DAY_HOUR = intPreferencesKey("end_of_day_hour")

        internal fun validateHour(name: String, hour: Int) {
            require(hour in 0..23) { "$name must be in 0..23; was $hour" }
        }

        internal fun validatePositive(name: String, value: Int) {
            require(value > 0) { "$name must be positive; was $value" }
        }
    }

    val dailyStepGoal: Flow<Int> = context.dataStore.data.map {
        it[DAILY_STEP_GOAL] ?: SettingsDefaults.DAILY_STEP_GOAL
    }
    val inactivityThreshold: Flow<Int> = context.dataStore.data.map {
        it[INACTIVITY_THRESHOLD] ?: SettingsDefaults.INACTIVITY_THRESHOLD_MIN
    }
    val activeHoursStart: Flow<Int> = context.dataStore.data.map {
        it[ACTIVE_HOURS_START] ?: SettingsDefaults.ACTIVE_HOURS_START
    }
    val activeHoursEnd: Flow<Int> = context.dataStore.data.map {
        it[ACTIVE_HOURS_END] ?: SettingsDefaults.ACTIVE_HOURS_END
    }
    val quietHoursStart: Flow<Int> = context.dataStore.data.map {
        it[QUIET_HOURS_START] ?: SettingsDefaults.QUIET_HOURS_START
    }
    val quietHoursEnd: Flow<Int> = context.dataStore.data.map {
        it[QUIET_HOURS_END] ?: SettingsDefaults.QUIET_HOURS_END
    }
    val contextAwareEnabled: Flow<Boolean> = context.dataStore.data.map {
        it[CONTEXT_AWARE_ENABLED] ?: SettingsDefaults.CONTEXT_AWARE_ENABLED
    }
    val endOfDayHour: Flow<Int> = context.dataStore.data.map {
        it[END_OF_DAY_HOUR] ?: SettingsDefaults.END_OF_DAY_HOUR
    }

    suspend fun setDailyStepGoal(goal: Int) {
        validatePositive("Daily step goal", goal)
        context.dataStore.edit { it[DAILY_STEP_GOAL] = goal }
    }

    suspend fun setInactivityThreshold(minutes: Int) {
        validatePositive("Inactivity threshold", minutes)
        context.dataStore.edit { it[INACTIVITY_THRESHOLD] = minutes }
    }

    suspend fun setActiveHours(start: Int, end: Int) {
        validateHour("Active hours start", start)
        validateHour("Active hours end", end)
        context.dataStore.edit {
            it[ACTIVE_HOURS_START] = start
            it[ACTIVE_HOURS_END] = end
        }
    }

    suspend fun setQuietHours(start: Int, end: Int) {
        validateHour("Quiet hours start", start)
        validateHour("Quiet hours end", end)
        context.dataStore.edit {
            it[QUIET_HOURS_START] = start
            it[QUIET_HOURS_END] = end
        }
    }

    suspend fun setContextAwareEnabled(enabled: Boolean) {
        context.dataStore.edit { it[CONTEXT_AWARE_ENABLED] = enabled }
    }

    suspend fun setEndOfDayHour(hour: Int) {
        validateHour("End of day hour", hour)
        context.dataStore.edit { it[END_OF_DAY_HOUR] = hour }
    }
}
