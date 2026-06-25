package com.meatsack.shared.data

import com.meatsack.shared.constants.EscalationLevel
import com.meatsack.shared.constants.MessageSource
import com.meatsack.shared.constants.MessageTone
import com.meatsack.shared.constants.TriggerType
import com.meatsack.shared.model.Message
import kotlinx.serialization.Serializable

/**
 * One record in insults.json. Mirrors the editable fields of [Message];
 * runtime-only fields (`id`, `votesUp`, `votesDown`, `lastShownTimestamp`,
 * `isActive`) and `source` are not serialized — every loaded record is
 * PRE_WRITTEN.
 *
 * All four fields are required (no Kotlin defaults): a record missing any field
 * is a parse error, surfaced by `InsultLoaderParseTest.parse_missingField_throws`
 * and, at the whole-file level, by `InsultLoaderFileTest`.
 */
@Serializable
data class InsultDto(
    val text: String,
    val trigger: TriggerType,
    val level: EscalationLevel,
    val tone: MessageTone,
) {
    init {
        // A blank insult would deliver an empty notification on the watch.
        // Fail at the parse boundary instead of seeding a useless row.
        require(text.isNotBlank()) { "InsultDto.text must not be blank" }
    }

    fun toMessage(): Message = Message(
        text = text,
        level = level,
        triggerType = trigger,
        tone = tone,
        source = MessageSource.PRE_WRITTEN,
    )
}
