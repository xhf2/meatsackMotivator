package com.meatsack.motivator.trigger

import com.meatsack.shared.constants.EscalationLevel

/**
 * Computes how behind (or ahead) the user is on their step goal given
 * the current time of day. Used by BehindPaceWorker to decide whether
 * to fire a BEHIND_PACE insult and at what escalation level.
 */
object PaceCalculator {

    /**
     * Linear pace expectation. Between activeStart and activeEnd hours,
     * the user is "expected" to accumulate steps proportionally. Outside
     * that window we don't expect progress.
     */
    fun expectedStepsByHour(
        hour: Int,
        minute: Int,
        goal: Int,
        activeStart: Int,
        activeEnd: Int,
    ): Int {
        if (hour < activeStart) return 0
        if (hour >= activeEnd) return goal
        val hoursInDay = activeEnd - activeStart
        val elapsedHours = (hour - activeStart) + minute / 60.0
        return (goal * (elapsedHours / hoursInDay)).toInt().coerceAtLeast(0)
    }

    /**
     * Returns null if on pace or ahead; otherwise returns an escalation
     * level matching how far behind the user is.
     */
    fun escalationIfBehind(
        currentSteps: Int,
        expectedSteps: Int,
    ): EscalationLevel? {
        if (currentSteps >= expectedSteps) return null
        val shortfallPct = 1.0 - (currentSteps.toDouble() / expectedSteps.coerceAtLeast(1))
        return when {
            shortfallPct > 0.75 -> EscalationLevel.EXISTENTIAL
            shortfallPct > 0.50 -> EscalationLevel.NUCLEAR
            shortfallPct > 0.25 -> EscalationLevel.SAVAGE
            else -> EscalationLevel.AGGRESSIVE
        }
    }
}
