package com.meatsack.motivator.mobile.ai

import com.meatsack.shared.constants.EscalationLevel
import com.meatsack.shared.constants.MessageTone
import com.meatsack.shared.constants.TriggerType

/**
 * Builds the user-prompt string sent to Claude. The system-level style
 * ("David Goggins as angry drill sergeant") is baked in; the per-call
 * variables are the trigger context and the user's top-upvoted messages
 * as style examples.
 */
object Prompts {

    fun buildUserPrompt(
        currentSteps: Int,
        hourOfDay: Int,
        level: EscalationLevel,
        trigger: TriggerType,
        tone: MessageTone,
        topVoted: List<String>,
        count: Int = 10,
    ): String {
        val toneLine = when (tone) {
            MessageTone.FULL_SEND -> "Full send. Swear. Be brutal."
            MessageTone.WORK_SAFE -> "Keep it clean. No profanity. Still savage."
        }
        val triggerLine = when (trigger) {
            TriggerType.INACTIVITY -> "They have been sitting still too long."
            TriggerType.BEHIND_PACE -> "They are behind their step pace for the day."
            TriggerType.END_OF_DAY -> "The day is ending and they missed the goal."
            TriggerType.NO_WORKOUT -> "They didn't work out today."
        }
        val timeLabel = if (hourOfDay < 12) "${hourOfDay}am" else "${hourOfDay - 12}pm"
        val examples = topVoted.take(5).ifEmpty { listOf("(no examples yet)") }
            .joinToString("\n") { "- $it" }

        return """
            Generate $count short (1-2 sentence, max 20 words each) motivational insults.
            Style: David Goggins as an angry, disgusted drill sergeant.
            $triggerLine Steps so far: $currentSteps. Time: $timeLabel.
            Level: ${level.name} — crank the venom accordingly.
            Tone: $toneLine
            Use creative medical/anatomical compound insults like 'sarcopenic motherfucker',
            'osteopenic jello mold', 'arthritic waste of a skeleton', 'osteoporotic coward'.
            Never use 'fat' as an insult.

            Here are messages this user loved — match this voice:
            $examples

            Return ONE message per line. No numbering, no bullets, no quotes.
        """.trimIndent()
    }

    const val SYSTEM_PROMPT =
        "You are a pitiless motivational drill sergeant. No softening, no hedging. " +
            "Every response is raw motivational insults, one per line. No preamble."
}
