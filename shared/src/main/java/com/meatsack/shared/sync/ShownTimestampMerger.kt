package com.meatsack.shared.sync

import com.meatsack.shared.model.Message

/**
 * Merges a phone → watch `/messages` payload onto the watch's existing rows.
 *
 * The phone owns every field except `lastShownTimestamp` (text, level,
 * triggerType, tone, source, votes, isActive) and the payload is applied
 * verbatim for those. `lastShownTimestamp` is watch-owned: the phone never
 * delivers an insult, so its rows always carry 0, and inserting them with
 * REPLACE would wipe the watch's repeat cooldown (enforced by the wear
 * MessageRepository) on every sync. This keeps the watch's value for rows it
 * already has; rows the watch does not have yet are inserted as sent.
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
