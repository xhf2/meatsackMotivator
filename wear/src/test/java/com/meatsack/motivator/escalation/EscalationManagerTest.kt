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
    fun `triggers at escalation intervals`() {
        assertTrue(manager.shouldTrigger(30))  // Level 1
        assertTrue(manager.shouldTrigger(60))  // Level 2
        assertTrue(manager.shouldTrigger(90))  // Level 3
        assertTrue(manager.shouldTrigger(120)) // Level 4
    }

    @Test
    fun `does not trigger between intervals`() {
        assertFalse(manager.shouldTrigger(45))
        assertFalse(manager.shouldTrigger(75))
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
    fun `movement resets to aggressive`() {
        manager.onInactivityDetected(90)
        assertEquals(EscalationLevel.NUCLEAR, manager.currentLevel.value)

        manager.onMovementDetected()
        assertEquals(EscalationLevel.AGGRESSIVE, manager.currentLevel.value)
    }

    @Test
    fun `stays at existential for extended inactivity`() {
        manager.onInactivityDetected(150)
        assertEquals(EscalationLevel.EXISTENTIAL, manager.currentLevel.value)

        manager.onInactivityDetected(300)
        assertEquals(EscalationLevel.EXISTENTIAL, manager.currentLevel.value)
    }
}
