package com.meatsack.shared.constants

enum class EscalationLevel(val value: Int) {
    AGGRESSIVE(1),
    SAVAGE(2),
    NUCLEAR(3),
    EXISTENTIAL(4),
    ;

    companion object {
        const val INACTIVITY_THRESHOLD_MINUTES_DEFAULT = 30
        const val ESCALATION_INTERVAL_MINUTES = 30
        const val MOVEMENT_RESET_STEPS = 50
        const val MOVEMENT_RESET_WINDOW_MINUTES = 5

        fun fromValue(value: Int): EscalationLevel =
            entries.first { it.value == value }
    }
}

enum class TriggerType {
    INACTIVITY,
    BEHIND_PACE,
    END_OF_DAY,
    NO_WORKOUT,
}

enum class MessageTone {
    FULL_SEND,
    WORK_SAFE,
}

enum class MessageSource {
    PRE_WRITTEN,
    AI_GENERATED,
    USER_CUSTOM,
}
