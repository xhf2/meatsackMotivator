package com.meatsack.shared.retention

import com.meatsack.shared.model.Message

/**
 * Pure selection of which messages to hard-delete during the retention sweep. No Android / DB
 * dependencies so it is fully unit-testable. See
 * docs/superpowers/specs/2026-07-03-vote-aware-generation-design.md (item E).
 *
 * Per (level, tone, trigger) bucket:
 *  - loved (votesUp > votesDown) rows are permanent (never returned).
 *  - non-loved rows with votesDown >= 3 (rejected) are always deleted.
 *  - when the bucket exceeds [cap], the lowest-net non-loved rows are deleted down to the cap.
 *  - the floor guard then restores the highest-net *fireable* (votesDown < 3) marked rows so at
 *    least [floor] fireable rows survive. Rejected rows are not fireable, so they are never
 *    restored — which is why an all-rejected bucket empties (it could not fire anyway).
 */
object LibraryPruner {

    private data class Bucket(val level: Any, val tone: Any, val trigger: Any)

    fun selectForDeletion(messages: List<Message>, cap: Int, floor: Int): List<Long> {
        val toDelete = mutableListOf<Long>()

        val byBucket = messages.groupBy { Bucket(it.level, it.tone, it.triggerType) }
        for ((_, bucket) in byBucket) {
            val loved = bucket.filter { it.votesUp > it.votesDown }.map { it.id }.toSet()
            val marked = LinkedHashSet<Long>()

            // Rule: rejected non-loved rows.
            bucket.filter { it.id !in loved && it.votesDown >= 3 }.forEach { marked += it.id }

            // Rule: surplus over cap — delete lowest-net non-loved until bucket size <= cap.
            var remaining = bucket.size - marked.size
            if (remaining > cap) {
                val surplus = remaining - cap
                bucket.asSequence()
                    .filter { it.id !in loved && it.id !in marked }
                    .sortedWith(compareBy({ it.votesUp - it.votesDown }, { -it.id })) // lowest net first, highest ID on ties
                    .take(surplus)
                    .forEach { marked += it.id }
            }

            // Floor guard: keep >= floor fireable (votesDown < 3) survivors.
            val fireableSurvivors = bucket.count { it.id !in marked && it.votesDown < 3 }
            if (fireableSurvivors < floor) {
                val needed = floor - fireableSurvivors
                bucket.asSequence()
                    .filter { it.id in marked && it.votesDown < 3 }
                    .sortedByDescending { it.votesUp - it.votesDown } // keep best first
                    .take(needed)
                    .forEach { marked -= it.id }
            }

            toDelete += marked
        }
        return toDelete
    }
}
