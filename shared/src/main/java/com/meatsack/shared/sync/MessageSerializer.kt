package com.meatsack.shared.sync

import com.meatsack.shared.constants.EscalationLevel
import com.meatsack.shared.constants.MessageSource
import com.meatsack.shared.constants.MessageTone
import com.meatsack.shared.constants.TriggerType
import com.meatsack.shared.model.Message

/**
 * Symmetric (de)serializer for shipping [Message] lists over the Wear Data Layer.
 *
 * Format: one message per `\n`-separated line, 10 `|`-separated fields in the order
 * declared by [FIELD_COUNT]. Invariants: message text must not contain `|` or `\n`;
 * enum names must match current definitions. Malformed lines are dropped silently so
 * a corrupt payload never takes down the receiver.
 */
object MessageSerializer {

    private const val FIELD_COUNT = 10
    private const val FIELD_SEPARATOR = "|"
    private const val LINE_SEPARATOR = "\n"

    fun serialize(messages: List<Message>): String =
        messages.joinToString(LINE_SEPARATOR) { m ->
            listOf(
                m.id,
                m.text,
                m.level.value,
                m.triggerType.name,
                m.tone.name,
                m.source.name,
                m.votesUp,
                m.votesDown,
                m.lastShownTimestamp,
                if (m.isActive) 1 else 0,
            ).joinToString(FIELD_SEPARATOR)
        }

    fun deserialize(data: String): List<Message> {
        if (data.isEmpty()) return emptyList()
        return data.split(LINE_SEPARATOR).mapNotNull { parseLine(it) }
    }

    private fun parseLine(line: String): Message? {
        val parts = line.split(FIELD_SEPARATOR)
        if (parts.size != FIELD_COUNT) return null
        return runCatching {
            Message(
                id = parts[0].toLong(),
                text = parts[1],
                level = EscalationLevel.fromValue(parts[2].toInt()),
                triggerType = TriggerType.valueOf(parts[3]),
                tone = MessageTone.valueOf(parts[4]),
                source = MessageSource.valueOf(parts[5]),
                votesUp = parts[6].toInt(),
                votesDown = parts[7].toInt(),
                lastShownTimestamp = parts[8].toLong(),
                isActive = parts[9].toInt() != 0,
            )
        }.getOrNull()
    }
}
