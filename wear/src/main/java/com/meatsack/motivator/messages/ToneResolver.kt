package com.meatsack.motivator.messages

import com.meatsack.shared.constants.MessageTone
import java.util.Calendar

/**
 * Picks the right tone given the user's context-aware preference and
 * the current time-of-day. When the toggle is on and we're inside the
 * user's "active hours" window (which v1 treats as a proxy for work
 * hours), we use WORK_SAFE; otherwise FULL_SEND.
 */
object ToneResolver {
    fun resolve(
        contextAwareEnabled: Boolean,
        activeHoursStart: Int,
        activeHoursEnd: Int,
        hour: Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY),
    ): MessageTone {
        if (!contextAwareEnabled) return MessageTone.FULL_SEND
        val inActiveWindow = if (activeHoursStart <= activeHoursEnd) {
            hour in activeHoursStart until activeHoursEnd
        } else {
            hour >= activeHoursStart || hour < activeHoursEnd
        }
        return if (inActiveWindow) MessageTone.WORK_SAFE else MessageTone.FULL_SEND
    }
}
