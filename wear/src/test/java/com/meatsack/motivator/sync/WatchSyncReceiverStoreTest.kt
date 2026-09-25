package com.meatsack.motivator.sync

import com.meatsack.shared.constants.EscalationLevel
import com.meatsack.shared.constants.MessageSource
import com.meatsack.shared.constants.MessageTone
import com.meatsack.shared.constants.TriggerType
import com.meatsack.shared.db.MessageDao
import com.meatsack.shared.db.ShownTimestamp
import com.meatsack.shared.model.Message
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM regression guard for the cooldown-wipe bug, so it runs in CI and the
 * pre-commit hook (the Room-backed twin in androidTest needs an emulator).
 *
 * The fake implements only the two DAO calls the preserving upsert makes;
 * everything else is unreachable from [WatchSyncReceiver.store] and throws.
 * Because `upsertPreservingShown` is a Kotlin default body on the interface,
 * this exercises the real merge path, not a stub of it.
 */
class WatchSyncReceiverStoreTest {

    private class FakeDao(private val shownById: Map<Long, Long>) : MessageDao {
        val inserted = mutableListOf<Message>()

        override suspend fun getShownTimestamps(ids: List<Long>): List<ShownTimestamp> =
            ids.mapNotNull { id -> shownById[id]?.let { ShownTimestamp(id, it) } }

        override suspend fun insertAll(messages: List<Message>) {
            inserted += messages
        }

        override suspend fun getEligibleMessages(
            level: EscalationLevel,
            triggerType: TriggerType,
            tone: MessageTone,
            cutoffTimestamp: Long,
        ): List<Message> = unreachable()
        override suspend fun voteUp(messageId: Long) = unreachable()
        override suspend fun voteDown(messageId: Long) = unreachable()
        override suspend fun getVotedMessages(): List<Message> = unreachable()
        override suspend fun setVotes(messageId: Long, votesUp: Int, votesDown: Int) = unreachable()
        override suspend fun markShown(messageId: Long, timestamp: Long) = unreachable()
        override suspend fun setActive(messageId: Long, active: Boolean) = unreachable()
        override suspend fun getAllMessages(): List<Message> = unreachable()
        override fun getAllMessagesFlow(): Flow<List<Message>> = unreachable()
        override suspend fun getMessageCount(): Int = unreachable()
        override suspend fun getLovedTexts(limit: Int): List<String> = unreachable()
        override suspend fun getHatedTexts(limit: Int): List<String> = unreachable()
        override suspend fun deleteByIds(ids: List<Long>) = unreachable()

        private fun unreachable(): Nothing = error("store() must not call this DAO method")
    }

    private fun phoneRow(id: Long, up: Int = 0) = Message(
        id = id,
        text = "m$id",
        level = EscalationLevel.AGGRESSIVE,
        triggerType = TriggerType.INACTIVITY,
        tone = MessageTone.FULL_SEND,
        source = MessageSource.PRE_WRITTEN,
        votesUp = up,
        lastShownTimestamp = 0,
    )

    @Test fun store_keepsWatchShownTimestamp_andAppliesPhoneVotes() = runBlocking {
        val dao = FakeDao(shownById = mapOf(1L to 999L))

        WatchSyncReceiver.store(listOf(phoneRow(1, up = 2), phoneRow(2)), dao)

        val rows = dao.inserted.associateBy { it.id }
        assertEquals(2, rows.size)
        assertEquals(999L, rows.getValue(1).lastShownTimestamp)
        assertEquals(2, rows.getValue(1).votesUp)
        assertEquals(0L, rows.getValue(2).lastShownTimestamp)
    }
}
