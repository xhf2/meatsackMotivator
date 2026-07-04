package com.meatsack.motivator.mobile.ai

import com.meatsack.shared.constants.EscalationLevel
import com.meatsack.shared.constants.MessageTone
import com.meatsack.shared.constants.TriggerType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptsTest {

    private fun prompt(
        loved: List<String> = emptyList(),
        hated: List<String> = emptyList(),
        level: EscalationLevel = EscalationLevel.SAVAGE,
        tone: MessageTone = MessageTone.FULL_SEND,
        trigger: TriggerType = TriggerType.INACTIVITY,
    ) = Prompts.buildUserPrompt(
        currentSteps = 500,
        hourOfDay = 14,
        level = level,
        trigger = trigger,
        tone = tone,
        loved = loved,
        hated = hated,
    )

    @Test fun includesLovedExamplesUnderMatchThisVoice() {
        val p = prompt(loved = listOf("GET UP.", "Your chair knows you."))
        assertTrue(p.contains("match this voice"))
        assertTrue(p.contains("GET UP."))
        assertTrue(p.contains("Your chair knows you."))
    }

    @Test fun includesHatedExamplesUnderAvoidBlock() {
        val p = prompt(loved = listOf("good one"), hated = listOf("weak sauce"))
        assertTrue(p.contains("do NOT write"))
        assertTrue(p.contains("weak sauce"))
    }

    @Test fun omitsAvoidBlockWhenNoHated() {
        val p = prompt(loved = listOf("good one"), hated = emptyList())
        assertFalse(p.contains("do NOT write"))
    }

    @Test fun noLoved_usesHonestFallbackNotFakePraise() {
        val p = prompt(loved = emptyList(), hated = emptyList())
        assertTrue(p.contains("hasn't rated any favorites yet"))
        assertFalse(p.contains("match this voice"))
    }

    @Test fun levelLineReflectsRequestedLevel() {
        val p = prompt(level = EscalationLevel.EXISTENTIAL)
        assertTrue(p.contains("EXISTENTIAL"))
    }

    @Test fun workSafeToneChangesLanguageLine() {
        val p = prompt(tone = MessageTone.WORK_SAFE)
        assertTrue(p.contains("Keep it clean"))
    }
}
