package com.meatsack.motivator.messages

import com.meatsack.shared.constants.MessageTone
import java.util.Calendar

/**
 * Picks the message tone. When context-aware is on and the current hour is
 * inside the user's work-safe window [workSafeStart, workSafeEnd), we soften
 * to WORK_SAFE; otherwise FULL_SEND. The window check is shared with the
 * inactivity active-hours gate via ActiveWindow.
 */
object ToneResolver {
    fun resolve(
        contextAwareEnabled: Boolean,
        workSafeStart: Int,
        workSafeEnd: Int,
        hour: Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY),
    ): MessageTone {
        if (!contextAwareEnabled) return MessageTone.FULL_SEND
        return if (ActiveWindow.contains(hour, workSafeStart, workSafeEnd)) {
            MessageTone.WORK_SAFE
        } else {
            MessageTone.FULL_SEND
        }
    }
}
