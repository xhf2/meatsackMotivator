package com.meatsack.motivator.mobile.ui.library

import com.meatsack.shared.constants.GenerationLimits.RETIRE_DOWNVOTES
import com.meatsack.shared.model.Message

/** The three mutually exclusive Library states. See spec §Behaviour/States. */
enum class LibraryFilter { ACTIVE, ARCHIVED, RETIRED }

/**
 * Single source of truth for which state a [Message] is in. Pure (no Android
 * deps) so the ViewModel and screen stay thin and this is JVM-testable.
 *
 * - ARCHIVED: `isActive == false` (user keep-pile; never fires, never pruned).
 * - RETIRED:  active with `votesDown >= RETIRE_DOWNVOTES` (unfireable; deleted by
 *             `LibraryPruner` after the next successful Generate unless loved).
 * - ACTIVE:   everything else.
 *
 * The threshold is `GenerationLimits.RETIRE_DOWNVOTES`, shared with the watch's
 * eligibility query and the pruner, so this classification cannot drift from what
 * actually fires.
 */
object LibraryFilters {
    fun stateOf(m: Message): LibraryFilter = when {
        !m.isActive -> LibraryFilter.ARCHIVED
        m.votesDown >= RETIRE_DOWNVOTES -> LibraryFilter.RETIRED
        else -> LibraryFilter.ACTIVE
    }

    fun apply(messages: List<Message>, filter: LibraryFilter): List<Message> =
        messages.filter { stateOf(it) == filter }

    fun counts(messages: List<Message>): Map<LibraryFilter, Int> {
        val byState = messages.groupingBy { stateOf(it) }.eachCount()
        return LibraryFilter.entries.associateWith { byState[it] ?: 0 }
    }

    /** Ids the "Delete all retired" action removes: every RETIRED row, loved-but-retired included. */
    fun retiredIds(messages: List<Message>): List<Long> =
        apply(messages, LibraryFilter.RETIRED).map { it.id }
}
