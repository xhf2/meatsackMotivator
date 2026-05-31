package com.meatsack.shared.sync

import com.google.android.gms.wearable.DataMap
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsSnapshotTest {

    @Test
    fun defaults_matchSettingsDefaults() {
        val snap = SettingsSnapshot.defaults
        assertEquals(SettingsDefaults.DAILY_STEP_GOAL, snap.dailyStepGoal)
        assertEquals(SettingsDefaults.INACTIVITY_THRESHOLD_MIN, snap.inactivityThreshold)
        assertEquals(SettingsDefaults.ACTIVE_HOURS_START, snap.activeHoursStart)
        assertEquals(SettingsDefaults.ACTIVE_HOURS_END, snap.activeHoursEnd)
        assertEquals(SettingsDefaults.CONTEXT_AWARE_ENABLED, snap.contextAwareEnabled)
        assertEquals(SettingsDefaults.END_OF_DAY_HOUR, snap.endOfDayHour)
        assertEquals(SettingsDefaults.BEHIND_PACE_CHECK_HOUR, snap.behindPaceCheckHour)
    }

    @Test
    fun toDataMap_fromDataMap_roundTrip() {
        val original = SettingsSnapshot(
            dailyStepGoal = 12_345,
            inactivityThreshold = 45,
            activeHoursStart = 8,
            activeHoursEnd = 21,
            contextAwareEnabled = true,
            endOfDayHour = 20,
            behindPaceCheckHour = 14,
        )
        val dm = DataMap()
        original.toDataMap(dm)
        val parsed = SettingsSnapshot.fromDataMap(dm)
        assertEquals(original, parsed)
    }

    @Test
    fun fromDataMap_missingKeys_returnsDefaults() {
        val dm = DataMap() // empty
        val parsed = SettingsSnapshot.fromDataMap(dm)
        assertEquals(SettingsSnapshot.defaults, parsed)
    }

    @Test
    fun fromDataMap_outOfRangeHours_clampedTo0to23() {
        val dm = DataMap()
        dm.putInt(SettingsKeys.KEY_ACTIVE_HOURS_START, -5)
        dm.putInt(SettingsKeys.KEY_ACTIVE_HOURS_END, 99)
        dm.putInt(SettingsKeys.KEY_END_OF_DAY_HOUR, 24)
        dm.putInt(SettingsKeys.KEY_BEHIND_PACE_CHECK_HOUR, -1)
        val parsed = SettingsSnapshot.fromDataMap(dm)
        assertEquals(0, parsed.activeHoursStart)
        assertEquals(23, parsed.activeHoursEnd)
        assertEquals(23, parsed.endOfDayHour)
        assertEquals(0, parsed.behindPaceCheckHour)
    }
}
