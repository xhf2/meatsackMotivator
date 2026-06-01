package com.meatsack.shared.sync

import com.meatsack.shared.constants.EscalationLevel
import com.meatsack.shared.constants.MessageLimits
import com.meatsack.shared.constants.MessageSource
import com.meatsack.shared.constants.MessageTone
import com.meatsack.shared.constants.TriggerType
import com.meatsack.shared.model.Message

/**
 * Symmetric (de)serializer for shipping [Message] lists over the Wear Data Layer.
 *
 * Format: one message per `\n`-separated line, 10 `|`-separated fields in this fixed
 * order: id, text, level, triggerType, tone, source, votesUp, votesDown,
 * lastShownTimestamp, isActive.
 *
 * Invariants:
 * - Message text must not contain `|` or `\n`. Enforced by [serialize].
 * - Renaming an enum constant is a breaking wire change: a watch on an old APK will
 *   silently drop affected lines (logged as a warning, not surfaced to the user).
 *
 * Malformed lines are dropped (and logged at WARN) rather than throwing, so a corrupt
 * payload never takes down the receiver.
 */
object MessageSerializer {

    private const val FIELD_COUNT = 10
    private const val FIELD_SEPARATOR = "|"
    private const val LINE_SEPARATOR = "\n"

    fun serialize(messages: List<Message>): String =
        messages.joinToString(LINE_SEPARATOR) { m ->
            require(
                !m.text.contains(FIELD_SEPARATOR) &&
                    !m.text.contains(LINE_SEPARATOR) &&
                    m.text.length <= MessageLimits.MAX_MESSAGE_TEXT_LENGTH,
            ) {
                "Message text invalid (length=${m.text.length}, max=${MessageLimits.MAX_MESSAGE_TEXT_LENGTH}, " +
                    "has '|'=${m.text.contains(FIELD_SEPARATOR)}, has newline=${m.text.contains(LINE_SEPARATOR)}); id=${m.id}"
            }
            listOf(
                m.id, m.text, m.level.value, m.triggerType.name, m.tone.name,
                m.source.name, m.votesUp, m.votesDown, m.lastShownTimestamp,
                if (m.isActive) 1 else 0,
            ).joinToString(FIELD_SEPARATOR)
        }

    fun deserialize(data: String): List<Message> {
        if (data.isEmpty()) return emptyList()
        return data.split(LINE_SEPARATOR).mapNotNull { parseLine(it) }
    }

    private fun parseLine(line: String): Message? {
        val parts = line.split(FIELD_SEPARATOR)
        if (parts.size != FIELD_COUNT) {
            android.util.Log.w(
                "MessageSerializer",
                "Dropped line with ${parts.size} fields (expected $FIELD_COUNT): ${line.take(80)}",
            )
            return null
        }
        return runCatching {
            val level = EscalationLevel.fromValueOrNull(parts[2].toInt())
                ?: return null.also {
                    android.util.Log.w(
                        "MessageSerializer",
                        "Dropped line with unknown EscalationLevel '${parts[2]}': ${line.take(80)}",
                    )
                }
            Message(
                id = parts[0].toLong(),
                text = parts[1],
                level = level,
                triggerType = TriggerType.valueOf(parts[3]),
                tone = MessageTone.valueOf(parts[4]),
                source = MessageSource.valueOf(parts[5]),
                votesUp = parts[6].toInt(),
                votesDown = parts[7].toInt(),
                lastShownTimestamp = parts[8].toLong(),
                isActive = parts[9].toInt() != 0,
            )
        }.onFailure { error ->
            android.util.Log.w("MessageSerializer", "Dropped malformed line: ${line.take(80)}", error)
        }.getOrNull()
    }
}
