package com.meatsack.motivator.trigger

import com.meatsack.shared.constants.EscalationLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PaceCalculatorTest {

    @Test fun expected_zero_beforeActiveStart() {
        assertEquals(0, PaceCalculator.expectedStepsByHour(6, 0, 10_000, 7, 22))
    }

    @Test fun expected_fullGoal_afterActiveEnd() {
        assertEquals(10_000, PaceCalculator.expectedStepsByHour(23, 0, 10_000, 7, 22))
    }

    @Test fun linearProgression_midDay() {
        // 15h active window, goal 10000 -> ~667 steps/hour
        // At 14:30, 7.5 hours elapsed -> expected ~5000
        val expected = PaceCalculator.expectedStepsByHour(14, 30, 10_000, 7, 22)
        assertEquals(5000, expected)
    }

    @Test fun noEscalation_whenOnPace() {
        assertNull(PaceCalculator.escalationIfBehind(5000, 5000))
        assertNull(PaceCalculator.escalationIfBehind(6000, 5000))
    }

    @Test fun aggressive_whenUnder25PctBehind() {
        assertEquals(EscalationLevel.AGGRESSIVE, PaceCalculator.escalationIfBehind(4000, 5000))
    }

    @Test fun savage_when26to50PctBehind() {
        // 3000/5000 = 60% of pace = 40% short -> SAVAGE (in the (0.25, 0.50] band).
        assertEquals(EscalationLevel.SAVAGE, PaceCalculator.escalationIfBehind(3000, 5000))
    }

    @Test fun nuclear_when51to75PctBehind() {
        // 2000/5000 = 40% of pace = 60% short -> NUCLEAR (in the (0.50, 0.75] band).
        assertEquals(EscalationLevel.NUCLEAR, PaceCalculator.escalationIfBehind(2000, 5000))
    }

    @Test fun existential_whenOver75PctBehind() {
        assertEquals(EscalationLevel.EXISTENTIAL, PaceCalculator.escalationIfBehind(500, 5000))
    }
}
