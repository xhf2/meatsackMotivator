package com.meatsack.shared.sync

/**
 * Wire-level keys for the /settings DataItem channel. Single source of truth for
 * both PhoneSettingsSyncer (writer) and WatchSettingsReceiver (reader). Adding
 * a key here is the only edit needed before wiring it on each side.
 *
 * No KEY_TIMESTAMP: DataClient dedupes by content bytes, and settings have no
 * "stale" semantics on the watch — including a timestamp would fire onDataChanged
 * on every send even when nothing changed. Deliberate divergence from /messages.
 */
object SettingsKeys {
    const val PATH = "/settings"
    const val KEY_DAILY_STEP_GOAL = "daily_step_goal"
    const val KEY_INACTIVITY_THRESHOLD = "inactivity_threshold_min"
    const val KEY_ACTIVE_HOURS_START = "active_hours_start"
    const val KEY_ACTIVE_HOURS_END = "active_hours_end"
    const val KEY_CONTEXT_AWARE_ENABLED = "context_aware_enabled"
    const val KEY_BEHIND_PACE_CHECK_HOUR = "behind_pace_check_hour"
    const val KEY_BEHIND_PACE_ENABLED = "behind_pace_enabled"
    const val KEY_END_OF_DAY_ENABLED = "end_of_day_enabled"
    const val KEY_CONTEXT_AWARE_START = "context_aware_start"
    const val KEY_CONTEXT_AWARE_END = "context_aware_end"
    const val KEY_MOVEMENT_STEP_THRESHOLD = "movement_step_threshold"
}
