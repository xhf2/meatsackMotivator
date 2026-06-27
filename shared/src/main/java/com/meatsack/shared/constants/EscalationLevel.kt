package com.meatsack.shared.constants

import kotlinx.serialization.Serializable

@Serializable
enum class EscalationLevel(val value: Int) {
    AGGRESSIVE(1),
    SAVAGE(2),
    NUCLEAR(3),
    EXISTENTIAL(4),
    ;

    companion object {
        const val INACTIVITY_THRESHOLD_MINUTES_DEFAULT = 30
        const val ESCALATION_INTERVAL_MINUTES = 30

        fun fromValueOrNull(value: Int): EscalationLevel? =
            entries.firstOrNull { it.value == value }

        fun fromValue(value: Int): EscalationLevel =
            fromValueOrNull(value)
                ?: throw IllegalArgumentException("Unknown EscalationLevel value: $value")
    }
}

@Serializable
enum class TriggerType {
    INACTIVITY,
    BEHIND_PACE,
    END_OF_DAY,
    NO_WORKOUT,
}

@Serializable
enum class MessageTone {
    FULL_SEND,
    WORK_SAFE,
}

enum class MessageSource {
    PRE_WRITTEN,
    AI_GENERATED,
    USER_CUSTOM,
}
