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

    companion object {
        private const val KEY_CONTEXT_AWARE = "context_aware_enabled"
        private const val KEY_ACTIVE_START = "active_hours_start"
        private const val KEY_ACTIVE_END = "active_hours_end"
    }
}
