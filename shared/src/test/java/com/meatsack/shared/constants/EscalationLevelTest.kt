package com.meatsack.shared.constants

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EscalationLevelTest {

    @Test
    fun fromValueOrNull_returnsLevelForEveryDeclaredValue() {
        assertEquals(EscalationLevel.AGGRESSIVE, EscalationLevel.fromValueOrNull(1))
        assertEquals(EscalationLevel.SAVAGE, EscalationLevel.fromValueOrNull(2))
        assertEquals(EscalationLevel.NUCLEAR, EscalationLevel.fromValueOrNull(3))
        assertEquals(EscalationLevel.EXISTENTIAL, EscalationLevel.fromValueOrNull(4))
    }

    @Test
    fun fromValueOrNull_returnsNullForUnknownValues_withoutThrowing() {
        // Contract anchor: wire / IPC callers (e.g., MessageSerializer) rely on this
        // never throwing so a single bad row doesn't poison a whole sync payload.
        assertNull(EscalationLevel.fromValueOrNull(0))
        assertNull(EscalationLevel.fromValueOrNull(5))
        assertNull(EscalationLevel.fromValueOrNull(-1))
        assertNull(EscalationLevel.fromValueOrNull(99))
        assertNull(EscalationLevel.fromValueOrNull(Int.MAX_VALUE))
        assertNull(EscalationLevel.fromValueOrNull(Int.MIN_VALUE))
    }

    @Test
    fun fromValue_returnsLevelForEveryDeclaredValue() {
        assertEquals(EscalationLevel.AGGRESSIVE, EscalationLevel.fromValue(1))
        assertEquals(EscalationLevel.SAVAGE, EscalationLevel.fromValue(2))
        assertEquals(EscalationLevel.NUCLEAR, EscalationLevel.fromValue(3))
        assertEquals(EscalationLevel.EXISTENTIAL, EscalationLevel.fromValue(4))
    }

    @Test
    fun fromValue_throwsIllegalArgumentException_onUnknownValue() {
        // Room TypeConverter relies on this throwing (corrupt DB row should fail loud,
        // not silently coerce to a default).
        val bad = 99
        try {
            EscalationLevel.fromValue(bad)
            error("Expected IllegalArgumentException for value=$bad")
        } catch (e: IllegalArgumentException) {
            assertTrue(
                "Exception message should include the offending value for log triage; was: ${e.message}",
                e.message?.contains(bad.toString()) == true,
            )
        }
    }
}
