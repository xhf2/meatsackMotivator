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

    fun onInactivityDetected(minutesIdle: Int) {
        if (isSnoozed && System.currentTimeMillis() < snoozeUntil) return

        if (!isActive) {
            isActive = true
            inactivityStartTime = System.currentTimeMillis() - (minutesIdle * 60_000L)
        }

        isSnoozed = false
        _currentLevel.value = calculateLevel(minutesIdle)
    }

    fun onMovementDetected() {
        isActive = false
        inactivityStartTime = 0
        _currentLevel.value = EscalationLevel.AGGRESSIVE
    }

    fun snooze(durationMinutes: Int) {
        isSnoozed = true
        snoozeUntil = System.currentTimeMillis() + (durationMinutes * 60_000L)
    }

    fun shouldTrigger(minutesIdle: Int): Boolean {
        if (isSnoozed && System.currentTimeMillis() < snoozeUntil) return false

        val threshold = EscalationLevel.INACTIVITY_THRESHOLD_MINUTES_DEFAULT
        val interval = EscalationLevel.ESCALATION_INTERVAL_MINUTES

        if (minutesIdle < threshold) return false

        // Trigger at threshold, then every interval after
        val minutesPastThreshold = minutesIdle - threshold
        return minutesPastThreshold % interval == 0
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
