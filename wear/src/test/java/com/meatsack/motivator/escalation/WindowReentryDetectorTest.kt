package com.meatsack.motivator.escalation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WindowReentryDetectorTest {

    @Test
    fun `signals re-entry only on an outside-to-inside transition`() {
        val d = WindowReentryDetector()
        // First in-window poll counts as a re-entry (fresh baseline on start / after being out).
        assertTrue("first inside poll is a re-entry", d.onPoll(inWindow = true))
        assertFalse("staying inside is not a re-entry", d.onPoll(inWindow = true))
        assertFalse("leaving the window is not a re-entry", d.onPoll(inWindow = false))
        assertFalse("staying outside is not a re-entry", d.onPoll(inWindow = false))
        assertTrue("coming back inside is a re-entry", d.onPoll(inWindow = true))
    }

    @Test
    fun `does not signal re-entry when starting outside the window`() {
        val d = WindowReentryDetector()
        assertFalse(d.onPoll(inWindow = false))
    }
}
