package com.meatsack.motivator.mobile.ui.library

import com.meatsack.shared.constants.EscalationLevel
import com.meatsack.shared.constants.MessageSource
import com.meatsack.shared.constants.MessageTone
import com.meatsack.shared.constants.TriggerType
import com.meatsack.shared.model.Message
import org.junit.Assert.assertEquals
import org.junit.Test

class FrozenOrderTest {

    private fun message(id: Long) = Message(
        id = id,
        text = "msg $id",
        level = EscalationLevel.AGGRESSIVE,
        triggerType = TriggerType.INACTIVITY,
        tone = MessageTone.FULL_SEND,
        source = MessageSource.PRE_WRITTEN,
        votesUp = 0,
        votesDown = 0,
    )

    private fun ids(messages: List<Message>) = messages.map { it.id }

    @Test
    fun firstNonEmptyEmission_setsOrder() {
        val order = FrozenOrder()
        val a = message(1)
        val b = message(2)
        val c = message(3)

        val result = order.apply(listOf(a, b, c))

        assertEquals(listOf(1L, 2L, 3L), ids(result))
    }

    @Test
    fun laterEmissionWithChangedScores_keepsFirstOrder() {
        val order = FrozenOrder()
        val a = message(1)
        val b = message(2)
        val c = message(3)

        order.apply(listOf(a, b, c))
        val result = order.apply(listOf(c, a, b))

        assertEquals(listOf(1L, 2L, 3L), ids(result))
    }

    @Test
    fun newIds_appendAfterKnownInIncomingOrder() {
        val order = FrozenOrder()
        val a = message(1)
        val b = message(2)
        val c = message(3)
        val d = message(4)

        order.apply(listOf(a, b))
        val result = order.apply(listOf(d, a, c, b))

        assertEquals(listOf(1L, 2L, 4L, 3L), ids(result))
    }

    @Test
    fun removedIds_dropOut() {
        val order = FrozenOrder()
        val a = message(1)
        val b = message(2)
        val c = message(3)

        order.apply(listOf(a, b, c))
        val result = order.apply(listOf(c, a))

        assertEquals(listOf(1L, 3L), ids(result))
    }

    @Test
    fun emptyFirstEmission_doesNotFreeze() {
        val order = FrozenOrder()
        val a = message(1)
        val b = message(2)

        val firstResult = order.apply(emptyList())
        assertEquals(emptyList<Long>(), ids(firstResult))

        val secondResult = order.apply(listOf(b, a))
        assertEquals(listOf(2L, 1L), ids(secondResult))
    }
}
