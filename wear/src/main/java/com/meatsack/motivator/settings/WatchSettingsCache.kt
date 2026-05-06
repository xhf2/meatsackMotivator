package com.meatsack.motivator.settings

import android.content.Context

/**
 * Cache of user settings mirrored from the phone. Populated by a
 * future settings-sync DataItem (Task 16); reads default values until
 * the first sync.
 */
class WatchSettingsCache(context: Context) {
    private val prefs = context.getSharedPreferences("watch_settings", Context.MODE_PRIVATE)

    var contextAwareEnabled: Boolean
        get() = prefs.getBoolean(KEY_CONTEXT_AWARE, false)
        set(value) = prefs.edit().putBoolean(KEY_CONTEXT_AWARE, value).apply()

    var activeHoursStart: Int
        get() = prefs.getInt(KEY_ACTIVE_START, 7)
        set(value) = prefs.edit().putInt(KEY_ACTIVE_START, value).apply()

    var activeHoursEnd: Int
        get() = prefs.getInt(KEY_ACTIVE_END, 22)
        set(value) = prefs.edit().putInt(KEY_ACTIVE_END, value).apply()

    var dailyStepGoal: Int
        get() = prefs.getInt(KEY_GOAL, 10_000)
        set(value) = prefs.edit().putInt(KEY_GOAL, value).apply()

    var endOfDayHour: Int
        get() = prefs.getInt(KEY_END_OF_DAY, 21)
        set(value) = prefs.edit().putInt(KEY_END_OF_DAY, value).apply()

    companion object {
        private const val KEY_CONTEXT_AWARE = "context_aware_enabled"
        private const val KEY_ACTIVE_START = "active_hours_start"
        private const val KEY_ACTIVE_END = "active_hours_end"
        private const val KEY_GOAL = "daily_step_goal"
        private const val KEY_END_OF_DAY = "end_of_day_hour"
    }
}
