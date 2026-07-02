package com.meatsack.motivator.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class MovementDetectorTest {

    private var clock = 0L
    private var stepThreshold = 50
    private var windowMinutes = 30

    private fun newDetector() = MovementDetector(
        stepThresholdProvider = { stepThreshold },
        windowMinutesProvider = { windowMinutes },
        now = { clock },
    )

    @Test
    fun firstReading_setsBaseline_doesNotCountWholeDailyTotal() {
        clock = 0
        val d = newDetector()
        d.onStepTotal(5000) // first reading: huge cumulative total must NOT count as movement
        assertFalse(d.hasSignificantMovement())
        clock = 30 * 60_000L // 30 min later, no further steps
        assertEquals(30, d.minutesSinceLastMovement())
    }

    @Test
    fun deltasAccumulateAcrossCallbacks_crossingThreshold_resetsIdleClock() {
        clock = 0
        val d = newDetector()
        d.onStepTotal(1000) // baseline
        clock = 60_000L
        d.onStepTotal(1040) // +40, below 50
        assertFalse(d.hasSignificantMovement())
        clock = 120_000L
        d.onStepTotal(1055) // +15 => window total 55 >= 50 => movement, idle clock resets to now
        assertEquals(0, d.minutesSinceLastMovement())
    }

    @Test
    fun dailyRollover_doesNotProduceNegativeOrHugeDelta() {
        clock = 0
        val d = newDetector()
        d.onStepTotal(9000) // baseline late in the day
        clock = 60_000L
        d.onStepTotal(20) // midnight rollover: cumulative dropped => delta treated as 0
        clock = 120_000L
        d.onStepTotal(30) // +10 only
        assertFalse(d.hasSignificantMovement())
        assertEquals(2, d.minutesSinceLastMovement()) // idle clock NOT reset by the rollover
    }

    @Test
    fun windowTumble_clearsPartialCount() {
        clock = 0
        val d = newDetector()
        d.onStepTotal(1000) // baseline, window opens at t=0
        clock = 60_000L
        d.onStepTotal(1040) // +40 in window 1
        clock = 31 * 60_000L // > 30-min window since t=0 => tumble, partial 40 discarded
        d.onStepTotal(1075) // +35 in a fresh window => 35, still below 50
        assertFalse(d.hasSignificantMovement())
        assertEquals(31, d.minutesSinceLastMovement()) // never crossed => idle clock never reset
    }

    @Test
    fun thresholdCrossing_reArmsWindow_doesNotReFireOnNextSmallDelta() {
        clock = 0
        val d = newDetector()
        d.onStepTotal(1000) // baseline
        clock = 60_000L
        d.onStepTotal(1050) // +50 >= 50 => fires; window must reset to 0 (re-arm)
        assertEquals(0, d.minutesSinceLastMovement())
        assertFalse(d.hasSignificantMovement())
        clock = 120_000L
        d.onStepTotal(1055) // +5 in the re-armed window => below 50, must NOT re-fire
        assertFalse(d.hasSignificantMovement())
        // idle clock advanced from the crossing (60s) to now (120s) = 1 min, NOT reset to 0.
        // If the window weren't re-armed, the banked 50 + 5 would re-fire and this would be 0.
        assertEquals(1, d.minutesSinceLastMovement())
    }

    @Test
    fun windowMinutesProviderIsReadLive() {
        clock = 0
        windowMinutes = 30
        val d = newDetector()
        d.onStepTotal(1000) // baseline, window opens at t=0
        clock = 60_000L
        d.onStepTotal(1040) // +40 in the window
        assertFalse(d.hasSignificantMovement())
        windowMinutes = 1 // shrink the window mid-stream
        clock = 180_000L // 3 min since window start: tumbles under the new 1-min window, not the old 30-min
        d.onStepTotal(1055) // +15 in a fresh (tumbled) window => 15, below 50
        assertFalse(d.hasSignificantMovement())
        // Never crossed => idle clock not reset. If the window value were captured at
        // construction (30 min, no tumble), 40 + 15 = 55 would have fired and this would be 0.
        assertEquals(3, d.minutesSinceLastMovement())
    }

    @Test
    fun rebaseline_resetsIdleClockToNow() {
        // Used on active-window re-entry so the morning ramp starts fresh instead of
        // inheriting the overnight idle accumulation that fired level 4 at 7am
        // (docs/debug/triggering-investigation.md, root cause A).
        clock = 0
        val d = newDetector()
        d.onStepTotal(5000) // baseline
        clock = 540 * 60_000L // 9 hours later with no movement
        assertEquals(540, d.minutesSinceLastMovement())
        d.rebaseline()
        assertEquals(0, d.minutesSinceLastMovement())
    }

    @Test
    fun rebaseline_clearsPartialWindowSteps() {
        clock = 0
        val d = newDetector()
        d.onStepTotal(1000) // baseline
        clock = 60_000L
        d.onStepTotal(1040) // +40 banked in the window, below the 50 threshold
        d.rebaseline() // rebaseline at t=1min: idle clock -> now, partial window -> 0
        // A stale partial window must not survive and later tip a false movement crossing.
        clock = 120_000L
        d.onStepTotal(1055) // +15 => 15 in the fresh window; if the stale 40 survived, 40+15=55 >= 50 would cross
        // No crossing => idle clock still anchored at the rebaseline (1 min ago), not reset to 0.
        assertEquals(1, d.minutesSinceLastMovement())
    }

    @Test
    fun stepThresholdProviderIsReadLive() {
        clock = 0
        stepThreshold = 100
        val d = newDetector()
        d.onStepTotal(1000) // baseline
        clock = 1_000L
        d.onStepTotal(1060) // +60, below 100
        assertFalse(d.hasSignificantMovement())
        stepThreshold = 50 // lower the bar mid-stream
        clock = 2_000L
        d.onStepTotal(1070) // +10 => window total 70 >= 50 now => movement
        assertEquals(0, d.minutesSinceLastMovement())
    }
}
