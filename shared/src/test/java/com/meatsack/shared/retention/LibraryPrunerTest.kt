package com.meatsack.shared.retention

import com.meatsack.shared.constants.EscalationLevel
import com.meatsack.shared.constants.MessageSource
import com.meatsack.shared.constants.MessageTone
import com.meatsack.shared.constants.TriggerType
import com.meatsack.shared.model.Message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryPrunerTest {

    // Helper: a message in the default bucket (SAVAGE/FULL_SEND/INACTIVITY) unless overridden.
    private fun msg(
        id: Long,
        up: Int = 0,
        down: Int = 0,
        source: MessageSource = MessageSource.AI_GENERATED,
        level: EscalationLevel = EscalationLevel.SAVAGE,
        tone: MessageTone = MessageTone.FULL_SEND,
        trigger: TriggerType = TriggerType.INACTIVITY,
    ) = Message(
        id = id, text = "m$id", level = level, triggerType = trigger, tone = tone,
        source = source, votesUp = up, votesDown = down, lastShownTimestamp = 0, isActive = true,
    )

    @Test fun deletesRejectedNonLovedRows() {
        // votesDown >= 3 and not loved -> dead weight, deleted. Padding keeps bucket above the floor.
        val padding = (1L..5L).map { msg(it) } // 5 fireable 0/0 rows hold the floor
        val rejected = msg(99, up = 0, down = 3)
        val ids = LibraryPruner.selectForDeletion(padding + rejected, cap = 50, floor = 5)
        assertEquals(listOf(99L), ids)
    }

    @Test fun neverDeletesLovedEvenIfHeavilyDownvoted() {
        // Loved = votesUp > votesDown; permanent even with votesDown >= 3.
        val padding = (1L..5L).map { msg(it) }
        val lovedButDownvoted = msg(99, up = 5, down = 3) // loved (5 > 3)
        val ids = LibraryPruner.selectForDeletion(padding + lovedButDownvoted, cap = 50, floor = 5)
        assertFalse(99L in ids)
    }

    @Test fun deletesSurplusLowestNetDownToCap() {
        // 4 NON-loved rows (net <= 0), cap 2, floor 0 => delete the 2 lowest-net.
        val rows = listOf(
            msg(1, up = 2, down = 2), // net 0  (kept)
            msg(2, up = 1, down = 1), // net 0  (kept)
            msg(3, up = 0, down = 1), // net -1 (deleted)
            msg(4, up = 0, down = 2), // net -2 (deleted)
        )
        val ids = LibraryPruner.selectForDeletion(rows, cap = 2, floor = 0).sorted()
        assertEquals(listOf(3L, 4L), ids)
    }

    @Test fun floorGuardKeepsFireableRowsEvenOverCap() {
        // 6 fireable 0/0 rows, cap 2, floor 5 => surplus wants to delete 4, but floor restores
        // the 2 highest-net so 5 fireable survive; net-tie => exactly one deleted.
        val rows = (1L..6L).map { msg(it) }
        val ids = LibraryPruner.selectForDeletion(rows, cap = 2, floor = 5)
        assertEquals(1, ids.size)
    }

    @Test fun rejectedBucketFullyDeletedDespiteFloor() {
        // Floor only protects fireable (votesDown < 3) rows. An all-rejected bucket has no
        // fireable rows to protect, so all are deleted (they could not fire anyway).
        val rows = (1L..5L).map { msg(it, up = 0, down = 3) }
        val ids = LibraryPruner.selectForDeletion(rows, cap = 50, floor = 5).sorted()
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), ids)
    }

    @Test fun bucketsAreIsolated() {
        // A surplus in one bucket must not delete rows from another bucket.
        val bucketA = (1L..4L).map { msg(it, level = EscalationLevel.SAVAGE) }
        val bucketB = (10L..11L).map { msg(it, level = EscalationLevel.NUCLEAR) }
        val ids = LibraryPruner.selectForDeletion(bucketA + bucketB, cap = 2, floor = 0)
        assertTrue(ids.all { it < 10L }) // only bucket A trimmed
        assertEquals(2, ids.size)
    }

    @Test fun seedsArePrunableLikeAnyOtherRow() {
        val seeds = (1L..3L).map { msg(it, source = MessageSource.PRE_WRITTEN) }
        val ids = LibraryPruner.selectForDeletion(seeds, cap = 1, floor = 0).sorted()
        assertEquals(listOf(2L, 3L), ids) // 2 lowest-net seeds pruned down to cap 1
    }
}
