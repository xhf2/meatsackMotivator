package com.meatsack.shared.data

import com.meatsack.shared.constants.EscalationLevel
import com.meatsack.shared.constants.MessageSource
import com.meatsack.shared.constants.MessageTone
import com.meatsack.shared.constants.TriggerType
import com.meatsack.shared.model.Message
import kotlinx.serialization.Serializable

/**
 * One record in insults.json. Mirrors the editable fields of [Message];
 * runtime-only fields (id, votes, timestamps, isActive) and [source] are not
 * serialized — every loaded record is PRE_WRITTEN.
 *
 * All four fields are required (no Kotlin defaults): a record missing any field
 * is a parse error, surfaced by the validating test in InsultLoaderFileTest.
 */
@Serializable
data class InsultDto(
    val text: String,
    val trigger: TriggerType,
    val level: EscalationLevel,
    val tone: MessageTone,
) {
    fun toMessage(): Message = Message(
        text = text,
        level = level,
        triggerType = trigger,
        tone = tone,
        source = MessageSource.PRE_WRITTEN,
    )
}
