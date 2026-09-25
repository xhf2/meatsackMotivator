package com.meatsack.shared.sync

import com.meatsack.shared.model.Message

/**
 * Merges a phone → watch `/messages` payload onto the watch's existing rows.
 *
 * The phone owns every editable field (text, bucket, votes, isActive) and the
 * payload is applied verbatim for those. The one field the watch owns is
 * `lastShownTimestamp`: the phone never delivers an insult, so its rows always
 * carry 0, and inserting them with REPLACE would wipe the watch's 24 h cooldown
 * on every sync. This keeps the watch's value for rows it already has; new rows
 * keep the incoming 0.
 *
 * Pure so it is unit-testable on the JVM; [com.meatsack.shared.db.MessageDao]
 * wraps it in a transaction.
 */
object ShownTimestampMerger {
    fun merge(incoming: List<Message>, existingShownById: Map<Long, Long>): List<Message> =
        incoming.map { m ->
            val shown = existingShownById[m.id] ?: return@map m
            m.copy(lastShownTimestamp = shown)
        }
}
