package com.meatsack.shared.constants

/**
 * Hard caps for message data. Single source of truth — referenced by
 * MessageSerializer (wire-format gate), AiMessageGenerator (post-generation
 * filter), and Prompts (preempt at generation time).
 */
object MessageLimits {
    /**
     * Maximum length of [Message.text] in characters. Sized so the watch's
     * InsultScreen fits the text + stats line + 👍/👎 vote buttons on a
     * Wear OS LARGE_ROUND face without overflow. Calibrated empirically
     * against the Compose preview in InsultScreen.kt.
     */
    const val MAX_MESSAGE_TEXT_LENGTH = 100
}
