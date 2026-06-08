package com.meatsack.motivator.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class VoteReceiverTest {

    @Test
    fun from_upVote_returnsUp() {
        assertEquals(VoteReceiver.VoteAction.Up(5L), VoteReceiver.VoteAction.from(5L, isUp = true))
    }

    @Test
    fun from_downVote_returnsDown() {
        assertEquals(VoteReceiver.VoteAction.Down(5L), VoteReceiver.VoteAction.from(5L, isUp = false))
    }

    @Test
    fun from_invalidId_returnsIgnore() {
        assertEquals(VoteReceiver.VoteAction.Ignore, VoteReceiver.VoteAction.from(0L, isUp = true))
        assertEquals(VoteReceiver.VoteAction.Ignore, VoteReceiver.VoteAction.from(-1L, isUp = false))
    }

    @Test
    fun requestCode_upAndDownDiffer_forSameMessage() {
        assertNotEquals(
            VoteReceiver.requestCode(7L, isUp = true),
            VoteReceiver.requestCode(7L, isUp = false),
        )
    }

    @Test
    fun requestCode_differsAcrossMessages() {
        assertNotEquals(
            VoteReceiver.requestCode(7L, isUp = true),
            VoteReceiver.requestCode(8L, isUp = true),
        )
    }
}
