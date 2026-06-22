package com.meatsack.motivator.sync

import com.meatsack.shared.sync.SettingsSnapshot
import org.junit.Assert.assertEquals
import org.junit.Test

class WatchSettingsReceiverTest {

    /** Map-backed fake that records all writes. */
    private class FakeApplySink : WatchSettingsReceiver.ApplySink {
        // Use distinct backing field names to avoid JVM signature clash with the
        // interface methods (e.g. setDailyStepGoal vs Kotlin's generated setter).
        var recordedDailyStepGoal: Int = -1
        var recordedInactivityThreshold: Int = -1
        var recordedActiveHoursStart: Int = -1
        var recordedActiveHoursEnd: Int = -1
        var recordedContextAwareEnabled: Boolean = false
        var recordedBehindPaceCheckHour: Int = -1
        var recordedBehindPaceEnabled: Boolean = true
        var recordedEndOfDayEnabled: Boolean = true
        var recordedContextAwareStart: Int = -1
        var recordedContextAwareEnd: Int = -1
        override fun setDailyStepGoal(v: Int) {
            recordedDailyStepGoal = v
        }
        override fun setInactivityThreshold(v: Int) {
            recordedInactivityThreshold = v
        }
        override fun setActiveHoursStart(v: Int) {
            recordedActiveHoursStart = v
        }
        override fun setActiveHoursEnd(v: Int) {
            recordedActiveHoursEnd = v
        }
        override fun setContextAwareEnabled(v: Boolean) {
            recordedContextAwareEnabled = v
        }
        override fun setBehindPaceCheckHour(v: Int) {
            recordedBehindPaceCheckHour = v
        }
        override fun setBehindPaceEnabled(v: Boolean) {
            recordedBehindPaceEnabled = v
        }
        override fun setEndOfDayEnabled(v: Boolean) {
            recordedEndOfDayEnabled = v
        }
        override fun setContextAwareStart(v: Int) {
            recordedContextAwareStart = v
        }
        override fun setContextAwareEnd(v: Int) {
            recordedContextAwareEnd = v
        }
    }

    @Test
    fun applySnapshot_writesEveryField() {
        val sink = FakeApplySink()
        val snap = SettingsSnapshot(
            dailyStepGoal = 12_000,
            inactivityThreshold = 45,
            activeHoursStart = 8,
            activeHoursEnd = 21,
            contextAwareEnabled = true,
            behindPaceCheckHour = 14,
            behindPaceEnabled = false,
            endOfDayEnabled = false,
            contextAwareStart = 8,
            contextAwareEnd = 16,
        )
        WatchSettingsReceiver.applySnapshot(snap, sink)
        assertEquals(12_000, sink.recordedDailyStepGoal)
        assertEquals(45, sink.recordedInactivityThreshold)
        assertEquals(8, sink.recordedActiveHoursStart)
        assertEquals(21, sink.recordedActiveHoursEnd)
        assertEquals(true, sink.recordedContextAwareEnabled)
        assertEquals(14, sink.recordedBehindPaceCheckHour)
        assertEquals(false, sink.recordedBehindPaceEnabled)
        assertEquals(false, sink.recordedEndOfDayEnabled)
        assertEquals(8, sink.recordedContextAwareStart)
        assertEquals(16, sink.recordedContextAwareEnd)
    }
}
