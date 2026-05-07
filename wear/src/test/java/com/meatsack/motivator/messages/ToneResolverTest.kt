package com.meatsack.motivator.messages

import com.meatsack.shared.constants.MessageTone
import org.junit.Assert.assertEquals
import org.junit.Test

class ToneResolverTest {
    @Test fun fullSend_whenToggleOff_regardlessOfTime() {
        assertEquals(MessageTone.FULL_SEND, ToneResolver.resolve(false, 7, 22, hour = 10))
        assertEquals(MessageTone.FULL_SEND, ToneResolver.resolve(false, 7, 22, hour = 23))
    }

    @Test fun workSafe_duringActiveHours_whenToggleOn() {
        assertEquals(MessageTone.WORK_SAFE, ToneResolver.resolve(true, 7, 22, hour = 10))
        assertEquals(MessageTone.WORK_SAFE, ToneResolver.resolve(true, 7, 22, hour = 21))
    }

    @Test fun fullSend_outsideActiveHours_whenToggleOn() {
        assertEquals(MessageTone.FULL_SEND, ToneResolver.resolve(true, 7, 22, hour = 6))
        assertEquals(MessageTone.FULL_SEND, ToneResolver.resolve(true, 7, 22, hour = 23))
    }

    @Test fun wraparoundWindow_22to6_treatsMidnightCorrectly() {
        // Active window 22-06 (night shift); toggle on
        assertEquals(MessageTone.WORK_SAFE, ToneResolver.resolve(true, 22, 6, hour = 23))
        assertEquals(MessageTone.WORK_SAFE, ToneResolver.resolve(true, 22, 6, hour = 3))
        assertEquals(MessageTone.FULL_SEND, ToneResolver.resolve(true, 22, 6, hour = 12))
    }
}
