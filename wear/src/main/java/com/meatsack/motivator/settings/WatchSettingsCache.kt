package com.meatsack.motivator.settings

import android.content.Context
import com.meatsack.shared.sync.SettingsDefaults

/**
 * Cache of user settings mirrored from the phone over the /settings DataItem.
 * Each key falls back to SettingsDefaults until that key has been written by a sync.
 */
class WatchSettingsCache(context: Context) {
    private val prefs = context.getSharedPreferences("watch_settings", Context.MODE_PRIVATE)

    var contextAwareEnabled: Boolean
        get() = prefs.getBoolean(KEY_CONTEXT_AWARE, SettingsDefaults.CONTEXT_AWARE_ENABLED)
        set(value) = prefs.edit().putBoolean(KEY_CONTEXT_AWARE, value).apply()

    var activeHoursStart: Int
        get() = prefs.getInt(KEY_ACTIVE_START, SettingsDefaults.ACTIVE_HOURS_START)
        set(value) = prefs.edit().putInt(KEY_ACTIVE_START, value).apply()

    var activeHoursEnd: Int
        get() = prefs.getInt(KEY_ACTIVE_END, SettingsDefaults.ACTIVE_HOURS_END)
        set(value) = prefs.edit().putInt(KEY_ACTIVE_END, value).apply()

    var dailyStepGoal: Int
        get() = prefs.getInt(KEY_GOAL, SettingsDefaults.DAILY_STEP_GOAL)
        set(value) = prefs.edit().putInt(KEY_GOAL, value).apply()

    var behindPaceCheckHour: Int
        get() = prefs.getInt(KEY_BEHIND_PACE_HOUR, SettingsDefaults.BEHIND_PACE_CHECK_HOUR)
        set(value) = prefs.edit().putInt(KEY_BEHIND_PACE_HOUR, value).apply()

    var inactivityThreshold: Int
        get() = prefs.getInt(KEY_INACTIVITY_THRESHOLD, SettingsDefaults.INACTIVITY_THRESHOLD_MIN)
        set(value) = prefs.edit().putInt(KEY_INACTIVITY_THRESHOLD, value).apply()

    var behindPaceEnabled: Boolean
        get() = prefs.getBoolean(KEY_BEHIND_PACE_ENABLED, SettingsDefaults.BEHIND_PACE_ENABLED)
        set(value) = prefs.edit().putBoolean(KEY_BEHIND_PACE_ENABLED, value).apply()

    var endOfDayEnabled: Boolean
        get() = prefs.getBoolean(KEY_END_OF_DAY_ENABLED, SettingsDefaults.END_OF_DAY_ENABLED)
        set(value) = prefs.edit().putBoolean(KEY_END_OF_DAY_ENABLED, value).apply()

    var contextAwareStart: Int
        get() = prefs.getInt(KEY_CONTEXT_AWARE_START, SettingsDefaults.CONTEXT_AWARE_START)
        set(value) = prefs.edit().putInt(KEY_CONTEXT_AWARE_START, value).apply()

    var contextAwareEnd: Int
        get() = prefs.getInt(KEY_CONTEXT_AWARE_END, SettingsDefaults.CONTEXT_AWARE_END)
        set(value) = prefs.edit().putInt(KEY_CONTEXT_AWARE_END, value).apply()

    companion object {
        private const val KEY_CONTEXT_AWARE = "context_aware_enabled"
        private const val KEY_ACTIVE_START = "active_hours_start"
        private const val KEY_ACTIVE_END = "active_hours_end"
        private const val KEY_GOAL = "daily_step_goal"
        private const val KEY_BEHIND_PACE_HOUR = "behind_pace_check_hour"
        private const val KEY_INACTIVITY_THRESHOLD = "inactivity_threshold_min"
        private const val KEY_BEHIND_PACE_ENABLED = "behind_pace_enabled"
        private const val KEY_END_OF_DAY_ENABLED = "end_of_day_enabled"
        private const val KEY_CONTEXT_AWARE_START = "context_aware_start"
        private const val KEY_CONTEXT_AWARE_END = "context_aware_end"
    }
}
