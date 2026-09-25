package com.meatsack.shared.sync

import com.meatsack.shared.constants.EscalationLevel
import com.meatsack.shared.constants.MessageSource
import com.meatsack.shared.constants.MessageTone
import com.meatsack.shared.constants.TriggerType
import com.meatsack.shared.model.Message
import org.junit.Assert.assertEquals
import org.junit.Test

class ShownTimestampMergerTest {

    // A row as the phone sends it: lastShownTimestamp is always 0 because the phone never delivers.
    private fun incoming(id: Long, text: String = "m$id", up: Int = 0, down: Int = 0, active: Boolean = true) =
        Message(
            id = id, text = text, level = EscalationLevel.SAVAGE, triggerType = TriggerType.INACTIVITY,
            tone = MessageTone.FULL_SEND, source = MessageSource.AI_GENERATED,
            votesUp = up, votesDown = down, lastShownTimestamp = 0, isActive = active,
        )

    @Test fun existingRowKeepsItsShownTimestamp() {
        val merged = ShownTimestampMerger.merge(listOf(incoming(1)), existingShownById = mapOf(1L to 1_700_000_000L))
        assertEquals(1_700_000_000L, merged.single().lastShownTimestamp)
    }

    @Test fun newRowKeepsIncomingZero() {
        val merged = ShownTimestampMerger.merge(listOf(incoming(2)), existingShownById = mapOf(1L to 1_700_000_000L))
        assertEquals(0L, merged.single().lastShownTimestamp)
    }

    @Test fun phoneOwnedFieldsAlwaysComeFromIncoming() {
        val merged = ShownTimestampMerger.merge(
            listOf(incoming(1, text = "edited", up = 4, down = 1, active = false)),
            existingShownById = mapOf(1L to 42L),
        )
        val row = merged.single()
        assertEquals("edited", row.text)
        assertEquals(4, row.votesUp)
        assertEquals(1, row.votesDown)
        assertEquals(false, row.isActive)
        assertEquals(42L, row.lastShownTimestamp)
    }

    @Test fun preservesIncomingOrderAndSize() {
        val merged = ShownTimestampMerger.merge(
            listOf(incoming(3), incoming(1), incoming(2)),
            existingShownById = mapOf(1L to 10L, 2L to 20L),
        )
        assertEquals(listOf(3L, 1L, 2L), merged.map { it.id })
        assertEquals(listOf(0L, 10L, 20L), merged.map { it.lastShownTimestamp })
    }
}
