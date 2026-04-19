package com.meatsack.motivator.escalation

import com.meatsack.shared.constants.EscalationLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class EscalationManagerTest {
    private lateinit var manager: EscalationManager

    @Before
    fun setUp() {
        manager = EscalationManager()
    }

    @Test
    fun `starts at aggressive level`() {
        assertEquals(EscalationLevel.AGGRESSIVE, manager.currentLevel.value)
    }

    @Test
    fun `does not trigger before threshold`() {
        assertFalse(manager.shouldTrigger(20))
        assertFalse(manager.shouldTrigger(29))
    }

    @Test
    fun `triggers at threshold`() {
        assertTrue(manager.shouldTrigger(30))
    }

    @Test
    fun `triggers at escalation levels from cold start`() {
        // From a fresh manager (never fired), any minutesIdle >= threshold triggers.
        assertTrue(manager.shouldTrigger(30))
        assertTrue(manager.shouldTrigger(60))
        assertTrue(manager.shouldTrigger(90))
        assertTrue(manager.shouldTrigger(120))
    }

    @Test
    fun `does not retrigger within interval after firing`() {
        manager.onInactivityDetected(30)
        assertFalse(manager.shouldTrigger(31))
        assertFalse(manager.shouldTrigger(45))
        assertFalse(manager.shouldTrigger(59))
    }

    @Test
    fun `retriggers after interval elapses since last fire`() {
        manager.onInactivityDetected(30)
        assertTrue(manager.shouldTrigger(60))
    }

    @Test
    fun `triggers reliably when poller drifts past exact boundary`() {
        // Regression: old modulo-on-minutesIdle rule skipped fires when the poll
        // advanced from 29 → 61, never observing the exact 60-min boundary.
        manager.onInactivityDetected(30)
        assertTrue(manager.shouldTrigger(61))
    }

    @Test
    fun `escalates levels correctly`() {
        manager.onInactivityDetected(30)
        assertEquals(EscalationLevel.AGGRESSIVE, manager.currentLevel.value)

        manager.onInactivityDetected(60)
        assertEquals(EscalationLevel.SAVAGE, manager.currentLevel.value)

        manager.onInactivityDetected(90)
        assertEquals(EscalationLevel.NUCLEAR, manager.currentLevel.value)

        manager.onInactivityDetected(120)
        assertEquals(EscalationLevel.EXISTENTIAL, manager.currentLevel.value)
    }

    @Test
    fun `movement resets to aggressive and re-arms trigger`() {
        manager.onInactivityDetected(90)
        assertEquals(EscalationLevel.NUCLEAR, manager.currentLevel.value)

        manager.onMovementDetected()
        assertEquals(EscalationLevel.AGGRESSIVE, manager.currentLevel.value)

        // After movement, first idle fire should trigger again without waiting for interval.
        assertTrue(manager.shouldTrigger(30))
    }

    @Test
    fun `stays at existential for extended inactivity`() {
        manager.onInactivityDetected(150)
        assertEquals(EscalationLevel.EXISTENTIAL, manager.currentLevel.value)

        manager.onInactivityDetected(300)
        assertEquals(EscalationLevel.EXISTENTIAL, manager.currentLevel.value)
    }

    @Test
    fun `snooze blocks triggers during window`() {
        manager.snooze(durationMinutes = 60)
        assertFalse(manager.shouldTrigger(30))
        assertFalse(manager.shouldTrigger(120))
    }
}
