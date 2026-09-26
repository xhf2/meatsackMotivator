package com.meatsack.motivator.mobile.sync

import com.meatsack.shared.constants.EscalationLevel
import com.meatsack.shared.constants.MessageSource
import com.meatsack.shared.constants.MessageTone
import com.meatsack.shared.constants.TriggerType
import com.meatsack.shared.model.Message
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncPayloadTest {

    private fun msg(id: Long, active: Boolean = true, down: Int = 0) = Message(
        id = id,
        text = "m$id",
        triggerType = TriggerType.INACTIVITY,
        level = EscalationLevel.SAVAGE,
        tone = MessageTone.FULL_SEND,
        source = MessageSource.AI_GENERATED,
        isActive = active,
        votesUp = 0,
        votesDown = down,
    )

    @Test
    fun select_includesArchivedAndRetiredRows() {
        // Regression guard for the archive feature: a re-added `.filter { it.isActive }`
        // would leave the watch firing a stale copy of every archived row.
        val rows = listOf(msg(1), msg(2, active = false), msg(3, down = 3), msg(4, active = false, down = 5))
        val selection = SyncPayload.select(rows)
        assertEquals(setOf(1L, 2L, 3L, 4L), selection.messages.map { it.id }.toSet())
        assertEquals(0, selection.dropped)
    }

    @Test
    fun select_putsHiddenRowsFirst_keepingInputOrderWithinEachGroup() {
        val rows = listOf(msg(1), msg(2, active = false), msg(3), msg(4, down = 3), msg(5))
        assertEquals(listOf(2L, 4L, 1L, 3L, 5L), SyncPayload.select(rows).messages.map { it.id })
    }

    @Test
    fun select_capsAtCacheSize_droppingFireableRowsNotHiddenOnes() {
        // 210 fireable rows followed by 5 hidden ones (net-score order puts hidden rows last).
        val fireable = (1L..210L).map { msg(it) }
        val hidden = (901L..905L).map { msg(it, active = false) }
        val selection = SyncPayload.select(fireable + hidden)

        assertEquals(SyncPayload.CACHE_SIZE, selection.messages.size)
        assertEquals(15, selection.dropped)
        assertEquals((901L..905L).toList(), selection.messages.take(5).map { it.id })
        assertEquals((1L..195L).toList(), selection.messages.drop(5).map { it.id })
    }

    @Test
    fun isHiddenOnWatch_matchesRetireThreshold() {
        assertEquals(false, SyncPayload.isHiddenOnWatch(msg(1, down = 2)))
        assertEquals(true, SyncPayload.isHiddenOnWatch(msg(1, down = 3)))
        assertEquals(true, SyncPayload.isHiddenOnWatch(msg(1, active = false)))
    }
}
