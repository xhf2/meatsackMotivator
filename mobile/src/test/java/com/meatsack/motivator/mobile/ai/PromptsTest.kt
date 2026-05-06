package com.meatsack.motivator.mobile.ai

import com.meatsack.shared.constants.EscalationLevel
import com.meatsack.shared.constants.MessageTone
import com.meatsack.shared.constants.TriggerType
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptsTest {

    @Test fun includesTopVotedExamples() {
        val prompt = Prompts.buildUserPrompt(
            currentSteps = 500,
            hourOfDay = 14,
            level = EscalationLevel.SAVAGE,
            trigger = TriggerType.INACTIVITY,
            tone = MessageTone.FULL_SEND,
            topVoted = listOf("GET UP.", "Your chair knows you."),
        )
        assertTrue(prompt.contains("GET UP."))
        assertTrue(prompt.contains("Your chair knows you."))
    }

    @Test fun workSafe_changesLanguageLine() {
        val prompt = Prompts.buildUserPrompt(
            0,
            10,
            EscalationLevel.AGGRESSIVE,
            TriggerType.INACTIVITY,
            MessageTone.WORK_SAFE,
            emptyList(),
        )
        assertTrue(prompt.contains("Keep it clean"))
    }

    @Test fun behindPace_changesTriggerLine() {
        val prompt = Prompts.buildUserPrompt(
            500,
            14,
            EscalationLevel.SAVAGE,
            TriggerType.BEHIND_PACE,
            MessageTone.FULL_SEND,
            emptyList(),
        )
        assertTrue(prompt.contains("behind their step pace"))
    }

    @Test fun handlesEmptyExamplesGracefully() {
        val prompt = Prompts.buildUserPrompt(
            0,
            10,
            EscalationLevel.AGGRESSIVE,
            TriggerType.INACTIVITY,
            MessageTone.FULL_SEND,
            emptyList(),
        )
        assertTrue(prompt.contains("no examples yet"))
    }
}
