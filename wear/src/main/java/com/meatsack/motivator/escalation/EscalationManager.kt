package com.meatsack.motivator.escalation

import com.meatsack.shared.constants.EscalationLevel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class EscalationManager {
    private val _currentLevel = MutableStateFlow(EscalationLevel.AGGRESSIVE)
    val currentLevel: StateFlow<EscalationLevel> = _currentLevel.asStateFlow()

    private var inactivityStartTime: Long = 0
    private var isActive: Boolean = false
    private var isSnoozed: Boolean = false
    private var snoozeUntil: Long = 0

    /**
     * Minutes-idle at the moment we last fired an insult; null if we haven't fired
     * this idle streak. Using minutesIdle (not wall clock) keeps the logic testable
     * without a fake Clock and naturally resets on movement.
     */
    private var lastFiredAtIdle: Int? = null

    fun onInactivityDetected(minutesIdle: Int) {
        if (isSnoozed && System.currentTimeMillis() < snoozeUntil) return

        if (!isActive) {
            isActive = true
            inactivityStartTime = System.currentTimeMillis() - (minutesIdle * 60_000L)
        }

        isSnoozed = false
        _currentLevel.value = calculateLevel(minutesIdle)
        lastFiredAtIdle = minutesIdle
    }

    fun onMovementDetected() {
        isActive = false
        inactivityStartTime = 0
        _currentLevel.value = EscalationLevel.AGGRESSIVE
        lastFiredAtIdle = null
    }

    fun snooze(durationMinutes: Int) {
        isSnoozed = true
        snoozeUntil = System.currentTimeMillis() + (durationMinutes * 60_000L)
    }

    /**
     * Fires on first cross of [INACTIVITY_THRESHOLD_MINUTES_DEFAULT] and then
     * every [ESCALATION_INTERVAL_MINUTES] of *additional* idle time since the
     * last fire. The previous modulo-on-minutesIdle rule silently skipped fires
     * when the poller drifted past an exact boundary (e.g. 29→61 jumped 60 entirely).
     */
    fun shouldTrigger(minutesIdle: Int): Boolean {
        if (isSnoozed && System.currentTimeMillis() < snoozeUntil) return false
        val threshold = EscalationLevel.INACTIVITY_THRESHOLD_MINUTES_DEFAULT
        if (minutesIdle < threshold) return false
        val interval = EscalationLevel.ESCALATION_INTERVAL_MINUTES
        val last = lastFiredAtIdle ?: return true
        return (minutesIdle - last) >= interval
    }

    fun isCurrentlySnoozed(): Boolean = isSnoozed && System.currentTimeMillis() < snoozeUntil

    private fun calculateLevel(minutesIdle: Int): EscalationLevel {
        val threshold = EscalationLevel.INACTIVITY_THRESHOLD_MINUTES_DEFAULT
        val interval = EscalationLevel.ESCALATION_INTERVAL_MINUTES
        val minutesPastThreshold = maxOf(0, minutesIdle - threshold)
        val escalations = minutesPastThreshold / interval

        return when {
            escalations >= 3 -> EscalationLevel.EXISTENTIAL
            escalations >= 2 -> EscalationLevel.NUCLEAR
            escalations >= 1 -> EscalationLevel.SAVAGE
            else -> EscalationLevel.AGGRESSIVE
        }
    }
}
