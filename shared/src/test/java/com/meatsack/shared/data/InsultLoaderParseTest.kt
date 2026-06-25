package com.meatsack.shared.data

import com.meatsack.shared.constants.EscalationLevel
import com.meatsack.shared.constants.MessageSource
import com.meatsack.shared.constants.MessageTone
import com.meatsack.shared.constants.TriggerType
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class InsultLoaderParseTest {

    @Test
    fun parse_mapsAllFields_andForcesPreWrittenSource() {
        val json = """
            [
              { "text": "GET UP.", "trigger": "INACTIVITY", "level": "AGGRESSIVE", "tone": "FULL_SEND" },
              { "text": "Behind pace.", "trigger": "BEHIND_PACE", "level": "SAVAGE", "tone": "WORK_SAFE" }
            ]
        """.trimIndent()

        val messages = InsultLoader.parse(json)

        assertEquals(2, messages.size)
        val first = messages[0]
        assertEquals("GET UP.", first.text)
        assertEquals(TriggerType.INACTIVITY, first.triggerType)
        assertEquals(EscalationLevel.AGGRESSIVE, first.level)
        assertEquals(MessageTone.FULL_SEND, first.tone)
        assertEquals(MessageSource.PRE_WRITTEN, first.source)
        assertEquals(TriggerType.BEHIND_PACE, messages[1].triggerType)
        assertEquals(MessageTone.WORK_SAFE, messages[1].tone)
    }

    @Test
    fun parse_missingField_throws() {
        // Missing "tone": kotlinx.serialization raises MissingFieldException, a
        // SerializationException. Asserting the specific type catches a regression
        // where the record fails to parse for some unrelated reason.
        val json = """[ { "text": "x", "trigger": "INACTIVITY", "level": "SAVAGE" } ]"""
        assertThrows(SerializationException::class.java) { InsultLoader.parse(json) }
    }

    @Test
    fun parse_unknownEnumValue_throws() {
        // A typo'd enum (here "TYPO" for level) must fail loudly, not coerce to a
        // default — locks the fail-fast contract InsultLoader's KDoc promises.
        val json = """[ { "text": "x", "trigger": "INACTIVITY", "level": "TYPO", "tone": "FULL_SEND" } ]"""
        assertThrows(SerializationException::class.java) { InsultLoader.parse(json) }
    }

    @Test
    fun parse_blankText_throws() {
        // InsultDto.init rejects blank text so a useless empty insult never seeds.
        val json = """[ { "text": "   ", "trigger": "INACTIVITY", "level": "SAVAGE", "tone": "FULL_SEND" } ]"""
        assertThrows(IllegalArgumentException::class.java) { InsultLoader.parse(json) }
    }
}
