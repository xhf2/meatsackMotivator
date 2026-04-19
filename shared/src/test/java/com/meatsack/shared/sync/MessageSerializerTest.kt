package com.meatsack.shared.sync

import com.meatsack.shared.constants.EscalationLevel
import com.meatsack.shared.constants.MessageSource
import com.meatsack.shared.constants.MessageTone
import com.meatsack.shared.constants.TriggerType
import com.meatsack.shared.model.Message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageSerializerTest {

    private fun sampleMessage(
        id: Long = 42L,
        text: String = "GET UP.",
        level: EscalationLevel = EscalationLevel.SAVAGE,
        triggerType: TriggerType = TriggerType.INACTIVITY,
        tone: MessageTone = MessageTone.FULL_SEND,
        source: MessageSource = MessageSource.PRE_WRITTEN,
        votesUp: Int = 5,
        votesDown: Int = 2,
        lastShownTimestamp: Long = 1_700_000_000_000L,
        isActive: Boolean = true,
    ) = Message(
        id = id,
        text = text,
        level = level,
        triggerType = triggerType,
        tone = tone,
        source = source,
        votesUp = votesUp,
        votesDown = votesDown,
        lastShownTimestamp = lastShownTimestamp,
        isActive = isActive,
    )

    @Test
    fun roundTrip_preservesAllFields() {
        val original = sampleMessage()
        val roundTripped = MessageSerializer.deserialize(
            MessageSerializer.serialize(listOf(original)),
        )
        assertEquals(1, roundTripped.size)
        assertEquals(original, roundTripped[0])
    }

    @Test
    fun roundTrip_preservesIsActiveFalse() {
        // Regression: earlier version of the serializer dropped isActive entirely,
        // defaulting every deserialized message back to true.
        val inactive = sampleMessage(isActive = false)
        val result = MessageSerializer.deserialize(
            MessageSerializer.serialize(listOf(inactive)),
        )
        assertEquals(false, result[0].isActive)
    }

    @Test
    fun roundTrip_preservesLastShownTimestamp() {
        // Regression: earlier version dropped this field.
        val withTimestamp = sampleMessage(lastShownTimestamp = 1_234_567_890L)
        val result = MessageSerializer.deserialize(
            MessageSerializer.serialize(listOf(withTimestamp)),
        )
        assertEquals(1_234_567_890L, result[0].lastShownTimestamp)
    }

    @Test
    fun roundTrip_handlesMultipleMessages() {
        val messages = listOf(
            sampleMessage(id = 1, text = "one", level = EscalationLevel.AGGRESSIVE),
            sampleMessage(id = 2, text = "two", level = EscalationLevel.NUCLEAR),
            sampleMessage(id = 3, text = "three", level = EscalationLevel.EXISTENTIAL),
        )
        val result = MessageSerializer.deserialize(MessageSerializer.serialize(messages))
        assertEquals(messages, result)
    }

    @Test
    fun roundTrip_coversAllEnumValues() {
        val messages = EscalationLevel.entries.flatMap { level ->
            TriggerType.entries.flatMap { trigger ->
                MessageTone.entries.flatMap { tone ->
                    MessageSource.entries.map { source ->
                        sampleMessage(
                            id = level.ordinal * 1000L,
                            level = level,
                            triggerType = trigger,
                            tone = tone,
                            source = source,
                        )
                    }
                }
            }
        }
        val result = MessageSerializer.deserialize(MessageSerializer.serialize(messages))
        assertEquals(messages.size, result.size)
        assertEquals(messages.toSet(), result.toSet())
    }

    @Test
    fun serialize_emptyList_returnsEmptyString() {
        assertEquals("", MessageSerializer.serialize(emptyList()))
    }

    @Test
    fun deserialize_emptyString_returnsEmptyList() {
        assertTrue(MessageSerializer.deserialize("").isEmpty())
    }

    @Test
    fun deserialize_malformedLine_isDroppedSilently() {
        // Mix one valid + one malformed (too few fields) + one valid.
        val valid1 = sampleMessage(id = 1)
        val valid2 = sampleMessage(id = 2, text = "another")
        val payload = buildString {
            append(MessageSerializer.serialize(listOf(valid1)))
            append('\n')
            append("1|this|line|has|too|few|fields") // 7 fields instead of 10
            append('\n')
            append(MessageSerializer.serialize(listOf(valid2)))
        }
        val result = MessageSerializer.deserialize(payload)
        assertEquals(listOf(valid1, valid2), result)
    }

    @Test
    fun deserialize_nonNumericField_isDroppedSilently() {
        val valid = sampleMessage()
        val bad = "99|text|NOT_A_NUMBER|INACTIVITY|FULL_SEND|PRE_WRITTEN|0|0|0|1"
        val payload = MessageSerializer.serialize(listOf(valid)) + "\n" + bad
        val result = MessageSerializer.deserialize(payload)
        assertEquals(listOf(valid), result)
    }

    @Test
    fun deserialize_unknownEnumName_isDroppedSilently() {
        val bad = "1|text|1|UNKNOWN_TRIGGER|FULL_SEND|PRE_WRITTEN|0|0|0|1"
        val result = MessageSerializer.deserialize(bad)
        assertTrue(result.isEmpty())
    }

    @Test
    fun deserialize_unknownLevelValue_isDroppedSilently() {
        // Level 99 doesn't map to any EscalationLevel; fromValue throws, line is dropped.
        val bad = "1|text|99|INACTIVITY|FULL_SEND|PRE_WRITTEN|0|0|0|1"
        val result = MessageSerializer.deserialize(bad)
        assertTrue(result.isEmpty())
    }

    @Test
    fun deserialize_tooManyFields_isDroppedSilently() {
        // Documents the known limitation: if text contains '|', the line splits into
        // extra fields and is rejected rather than parsed incorrectly.
        val badTextWithPipe = "1|text with|pipe|1|INACTIVITY|FULL_SEND|PRE_WRITTEN|0|0|0|1"
        val result = MessageSerializer.deserialize(badTextWithPipe)
        assertTrue(result.isEmpty())
    }

    @Test
    fun roundTrip_handlesDefaultVoteCounts() {
        val unvoted = sampleMessage(votesUp = 0, votesDown = 0)
        val result = MessageSerializer.deserialize(
            MessageSerializer.serialize(listOf(unvoted)),
        )
        assertEquals(0, result[0].votesUp)
        assertEquals(0, result[0].votesDown)
    }

    @Test
    fun serialize_producesDeterministicOutput() {
        val msg = sampleMessage()
        val first = MessageSerializer.serialize(listOf(msg))
        val second = MessageSerializer.serialize(listOf(msg))
        assertEquals(first, second)
    }

    @Test
    fun deserialize_trailingNewline_producesEmptyLineThatIsDropped() {
        val msg = sampleMessage()
        val serialized = MessageSerializer.serialize(listOf(msg)) + "\n"
        val result = MessageSerializer.deserialize(serialized)
        assertNotNull(result)
        assertEquals(1, result.size)
        assertEquals(msg, result[0])
    }
}
