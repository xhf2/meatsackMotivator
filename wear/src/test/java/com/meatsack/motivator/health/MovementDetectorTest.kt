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
