package com.meatsack.motivator.mobile.ui.library

import com.meatsack.shared.constants.EscalationLevel
import com.meatsack.shared.constants.MessageSource
import com.meatsack.shared.constants.MessageTone
import com.meatsack.shared.constants.TriggerType
import com.meatsack.shared.model.Message
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryFiltersTest {

    private fun msg(id: Long, down: Int = 0, active: Boolean = true, up: Int = 0) = Message(
        id = id, text = "m$id", level = EscalationLevel.SAVAGE, triggerType = TriggerType.INACTIVITY,
        tone = MessageTone.FULL_SEND, source = MessageSource.AI_GENERATED,
        votesUp = up, votesDown = down, lastShownTimestamp = 0, isActive = active,
    )

    @Test fun stateOf_activeUnderThreeDownvotes_isActive() {
        assertEquals(LibraryFilter.ACTIVE, LibraryFilters.stateOf(msg(1, down = 0)))
        assertEquals(LibraryFilter.ACTIVE, LibraryFilters.stateOf(msg(1, down = 2)))
    }

    @Test fun stateOf_activeWithThreeOrMoreDownvotes_isRetired() {
        assertEquals(LibraryFilter.RETIRED, LibraryFilters.stateOf(msg(1, down = 3)))
        assertEquals(LibraryFilter.RETIRED, LibraryFilters.stateOf(msg(1, down = 9, up = 20)))
    }

    @Test fun stateOf_inactive_isArchivedRegardlessOfVotes() {
        assertEquals(LibraryFilter.ARCHIVED, LibraryFilters.stateOf(msg(1, down = 0, active = false)))
        assertEquals(LibraryFilter.ARCHIVED, LibraryFilters.stateOf(msg(1, down = 5, active = false)))
    }

    @Test fun apply_returnsOnlyMatchingRows_inInputOrder() {
        val rows = listOf(msg(3, down = 3), msg(1), msg(2, active = false), msg(4))
        assertEquals(listOf(1L, 4L), LibraryFilters.apply(rows, LibraryFilter.ACTIVE).map { it.id })
        assertEquals(listOf(2L), LibraryFilters.apply(rows, LibraryFilter.ARCHIVED).map { it.id })
        assertEquals(listOf(3L), LibraryFilters.apply(rows, LibraryFilter.RETIRED).map { it.id })
    }

    @Test fun counts_hasAllThreeKeys_andSumsToInputSize() {
        val rows = listOf(msg(1), msg(2), msg(3, down = 3), msg(4, active = false))
        val counts = LibraryFilters.counts(rows)
        assertEquals(mapOf(LibraryFilter.ACTIVE to 2, LibraryFilter.ARCHIVED to 1, LibraryFilter.RETIRED to 1), counts)
    }

    @Test fun counts_onEmptyInput_isAllZeros() {
        assertEquals(
            mapOf(LibraryFilter.ACTIVE to 0, LibraryFilter.ARCHIVED to 0, LibraryFilter.RETIRED to 0),
            LibraryFilters.counts(emptyList()),
        )
    }

    @Test fun retiredIds_includesLovedButRetired_excludesArchivedAndActive() {
        // The dialog promises to delete every "3+ downvotes" row, loved ones included;
        // archived rows with 3+ downvotes are the user's keep-pile and must stay.
        val rows = listOf(msg(1), msg(2, down = 3), msg(3, down = 9, up = 20), msg(4, down = 5, active = false))
        assertEquals(listOf(2L, 3L), LibraryFilters.retiredIds(rows))
    }
}
