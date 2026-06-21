package com.meatsack.motivator.messages

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveWindowTest {

    @Test fun sameDayWindow_includesStart_excludesEnd() {
        assertTrue(ActiveWindow.contains(7, 7, 22)) // start is inclusive
        assertTrue(ActiveWindow.contains(21, 7, 22))
        assertFalse(ActiveWindow.contains(22, 7, 22)) // end is exclusive
        assertFalse(ActiveWindow.contains(6, 7, 22))
        assertFalse(ActiveWindow.contains(23, 7, 22))
    }

    @Test fun overnightWindow_wrapsAroundMidnight() {
        assertTrue(ActiveWindow.contains(23, 22, 6))
        assertTrue(ActiveWindow.contains(0, 22, 6))
        assertTrue(ActiveWindow.contains(5, 22, 6))
        assertFalse(ActiveWindow.contains(6, 22, 6)) // end exclusive
        assertFalse(ActiveWindow.contains(12, 22, 6))
    }

    @Test fun degenerateWindow_startEqualsEnd_isEmpty() {
        assertFalse(ActiveWindow.contains(9, 9, 9))
        assertFalse(ActiveWindow.contains(10, 9, 9))
    }
}
