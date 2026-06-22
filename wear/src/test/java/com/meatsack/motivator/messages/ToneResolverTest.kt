package com.meatsack.motivator.messages

import com.meatsack.shared.constants.MessageTone
import org.junit.Assert.assertEquals
import org.junit.Test

class ToneResolverTest {
    @Test fun fullSend_whenToggleOff_regardlessOfTime() {
        assertEquals(MessageTone.FULL_SEND, ToneResolver.resolve(false, 9, 17, hour = 10))
        assertEquals(MessageTone.FULL_SEND, ToneResolver.resolve(false, 9, 17, hour = 23))
    }

    @Test fun workSafe_insideWorkSafeWindow_whenToggleOn() {
        assertEquals(MessageTone.WORK_SAFE, ToneResolver.resolve(true, 9, 17, hour = 9))
        assertEquals(MessageTone.WORK_SAFE, ToneResolver.resolve(true, 9, 17, hour = 16))
    }

    @Test fun fullSend_outsideWorkSafeWindow_whenToggleOn() {
        assertEquals(MessageTone.FULL_SEND, ToneResolver.resolve(true, 9, 17, hour = 8))
        assertEquals(MessageTone.FULL_SEND, ToneResolver.resolve(true, 9, 17, hour = 17)) // end exclusive
        assertEquals(MessageTone.FULL_SEND, ToneResolver.resolve(true, 9, 17, hour = 23))
    }

    @Test fun overnightWorkSafeWindow_22to6_treatsMidnightCorrectly() {
        assertEquals(MessageTone.WORK_SAFE, ToneResolver.resolve(true, 22, 6, hour = 23))
        assertEquals(MessageTone.WORK_SAFE, ToneResolver.resolve(true, 22, 6, hour = 3))
        assertEquals(MessageTone.FULL_SEND, ToneResolver.resolve(true, 22, 6, hour = 12))
    }
}
