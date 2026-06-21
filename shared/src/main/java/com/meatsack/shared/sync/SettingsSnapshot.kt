package com.meatsack.shared.sync

import com.google.android.gms.wearable.DataMap

/**
 * Wire-shape for the /settings DataItem channel. Single source of truth for the
 * field set — adding a property here forces both phone (writer) and watch
 * (reader) to handle the new field — centralizing the field set prevents the
 * phone-side and watch-side from drifting independently.
 *
 * fromDataMap returns SettingsDefaults values for any missing keys (forward
 * and backward compatibility) and coerces hour fields to 0..23 (defense in
 * depth against malformed payloads; phone's validateHour should already
 * prevent invalid sends).
 */
data class SettingsSnapshot(
    val dailyStepGoal: Int,
    val inactivityThreshold: Int,
    val activeHoursStart: Int,
    val activeHoursEnd: Int,
    val contextAwareEnabled: Boolean,
    val endOfDayHour: Int,
    val behindPaceCheckHour: Int,
    val behindPaceEnabled: Boolean,
    val endOfDayEnabled: Boolean,
) {

    fun toDataMap(dm: DataMap) {
        dm.putInt(SettingsKeys.KEY_DAILY_STEP_GOAL, dailyStepGoal)
        dm.putInt(SettingsKeys.KEY_INACTIVITY_THRESHOLD, inactivityThreshold)
        dm.putInt(SettingsKeys.KEY_ACTIVE_HOURS_START, activeHoursStart)
        dm.putInt(SettingsKeys.KEY_ACTIVE_HOURS_END, activeHoursEnd)
        dm.putBoolean(SettingsKeys.KEY_CONTEXT_AWARE_ENABLED, contextAwareEnabled)
        dm.putInt(SettingsKeys.KEY_END_OF_DAY_HOUR, endOfDayHour)
        dm.putInt(SettingsKeys.KEY_BEHIND_PACE_CHECK_HOUR, behindPaceCheckHour)
        dm.putBoolean(SettingsKeys.KEY_BEHIND_PACE_ENABLED, behindPaceEnabled)
        dm.putBoolean(SettingsKeys.KEY_END_OF_DAY_ENABLED, endOfDayEnabled)
    }

    companion object {
        val defaults = SettingsSnapshot(
            dailyStepGoal = SettingsDefaults.DAILY_STEP_GOAL,
            inactivityThreshold = SettingsDefaults.INACTIVITY_THRESHOLD_MIN,
            activeHoursStart = SettingsDefaults.ACTIVE_HOURS_START,
            activeHoursEnd = SettingsDefaults.ACTIVE_HOURS_END,
            contextAwareEnabled = SettingsDefaults.CONTEXT_AWARE_ENABLED,
            endOfDayHour = SettingsDefaults.END_OF_DAY_HOUR,
            behindPaceCheckHour = SettingsDefaults.BEHIND_PACE_CHECK_HOUR,
            behindPaceEnabled = SettingsDefaults.BEHIND_PACE_ENABLED,
            endOfDayEnabled = SettingsDefaults.END_OF_DAY_ENABLED,
        )

        fun fromDataMap(dm: DataMap): SettingsSnapshot = SettingsSnapshot(
            dailyStepGoal = dm.getInt(SettingsKeys.KEY_DAILY_STEP_GOAL, defaults.dailyStepGoal),
            inactivityThreshold = dm.getInt(SettingsKeys.KEY_INACTIVITY_THRESHOLD, defaults.inactivityThreshold),
            activeHoursStart = dm.getInt(SettingsKeys.KEY_ACTIVE_HOURS_START, defaults.activeHoursStart).coerceIn(0, 23),
            activeHoursEnd = dm.getInt(SettingsKeys.KEY_ACTIVE_HOURS_END, defaults.activeHoursEnd).coerceIn(0, 23),
            contextAwareEnabled = dm.getBoolean(SettingsKeys.KEY_CONTEXT_AWARE_ENABLED, defaults.contextAwareEnabled),
            endOfDayHour = dm.getInt(SettingsKeys.KEY_END_OF_DAY_HOUR, defaults.endOfDayHour).coerceIn(0, 23),
            behindPaceCheckHour = dm.getInt(SettingsKeys.KEY_BEHIND_PACE_CHECK_HOUR, defaults.behindPaceCheckHour).coerceIn(0, 23),
            behindPaceEnabled = dm.getBoolean(SettingsKeys.KEY_BEHIND_PACE_ENABLED, defaults.behindPaceEnabled),
            endOfDayEnabled = dm.getBoolean(SettingsKeys.KEY_END_OF_DAY_ENABLED, defaults.endOfDayEnabled),
        )
    }
}
