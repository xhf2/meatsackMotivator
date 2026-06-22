package com.meatsack.shared.sync

/**
 * Default values for the settings the watch consumes. Mirrored by phone
 * (SettingsRepository fallbacks) and watch (WatchSettingsCache fallbacks).
 * Single source of truth — adding a default here is the only place to change it.
 */
object SettingsDefaults {
    const val DAILY_STEP_GOAL = 10_000
    const val INACTIVITY_THRESHOLD_MIN = 30
    const val ACTIVE_HOURS_START = 7
    const val ACTIVE_HOURS_END = 22
    const val CONTEXT_AWARE_ENABLED = false
    const val END_OF_DAY_HOUR = 21 // 9pm
    const val BEHIND_PACE_CHECK_HOUR = 12 // noon
    const val BEHIND_PACE_ENABLED = true
    const val END_OF_DAY_ENABLED = true
    const val CONTEXT_AWARE_START = 9 // 9am
    const val CONTEXT_AWARE_END = 17 // 5pm
}
