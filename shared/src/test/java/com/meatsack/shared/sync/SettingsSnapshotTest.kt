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
        assertEquals(SettingsDefaults.BEHIND_PACE_CHECK_HOUR, snap.behindPaceCheckHour)
        assertEquals(SettingsDefaults.BEHIND_PACE_ENABLED, snap.behindPaceEnabled)
        assertEquals(SettingsDefaults.END_OF_DAY_ENABLED, snap.endOfDayEnabled)
        assertEquals(SettingsDefaults.CONTEXT_AWARE_START, snap.contextAwareStart)
        assertEquals(SettingsDefaults.CONTEXT_AWARE_END, snap.contextAwareEnd)
        assertEquals(SettingsDefaults.MOVEMENT_STEP_THRESHOLD, snap.movementStepThreshold)
    }

    @Test
    fun toDataMap_fromDataMap_roundTrip() {
        val original = SettingsSnapshot(
            dailyStepGoal = 12_345,
            inactivityThreshold = 45,
            activeHoursStart = 8,
            activeHoursEnd = 21,
            contextAwareEnabled = true,
            behindPaceCheckHour = 14,
            behindPaceEnabled = false,
            endOfDayEnabled = false,
            contextAwareStart = 8,
            contextAwareEnd = 16,
            movementStepThreshold = 75,
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
        // Non-hour fields intentionally absent — fromDataMap returns defaults
        // for them, verified separately in fromDataMap_missingKeys_returnsDefaults.
        dm.putInt(SettingsKeys.KEY_ACTIVE_HOURS_START, -5)
        dm.putInt(SettingsKeys.KEY_ACTIVE_HOURS_END, 99)
        dm.putInt(SettingsKeys.KEY_BEHIND_PACE_CHECK_HOUR, -1)
        val parsed = SettingsSnapshot.fromDataMap(dm)
        assertEquals(0, parsed.activeHoursStart)
        assertEquals(23, parsed.activeHoursEnd)
        assertEquals(0, parsed.behindPaceCheckHour)
    }

    @Test
    fun fromDataMap_movementStepThresholdBelowOne_coercedToOne() {
        val dm = DataMap()
        dm.putInt(SettingsKeys.KEY_MOVEMENT_STEP_THRESHOLD, 0)
        val parsed = SettingsSnapshot.fromDataMap(dm)
        assertEquals(1, parsed.movementStepThreshold)
    }
}
