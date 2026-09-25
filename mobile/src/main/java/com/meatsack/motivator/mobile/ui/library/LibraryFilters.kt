package com.meatsack.motivator.mobile.ui.library

import com.meatsack.shared.model.Message

/** The three mutually exclusive Library states. See spec §Behaviour/States. */
enum class LibraryFilter { ACTIVE, ARCHIVED, RETIRED }

/**
 * Single source of truth for which state a [Message] is in. Pure (no Android
 * deps) so the ViewModel and screen stay thin and this is JVM-testable.
 *
 * - ARCHIVED: `isActive == false` (user keep-pile; never fires, never pruned).
 * - RETIRED:  active with `votesDown >= 3` (unfireable; deleted on next Generate unless loved).
 * - ACTIVE:   everything else.
 */
object LibraryFilters {
    private const val RETIRE_DOWNVOTES = 3

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
}
