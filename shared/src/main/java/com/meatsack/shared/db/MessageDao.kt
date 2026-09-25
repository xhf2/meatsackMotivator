package com.meatsack.shared.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.meatsack.shared.constants.EscalationLevel
import com.meatsack.shared.constants.MessageTone
import com.meatsack.shared.constants.TriggerType
import com.meatsack.shared.model.Message
import com.meatsack.shared.sync.ShownTimestampMerger
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Query(
        """
        SELECT * FROM messages
        WHERE level = :level
        AND triggerType = :triggerType
        AND tone = :tone
        AND isActive = 1
        AND votesDown < 3
        AND lastShownTimestamp <= :cutoffTimestamp
        ORDER BY
            CASE WHEN votesUp = 0 AND votesDown = 0 THEN 0 ELSE 1 END,
            (votesUp - votesDown) DESC
    """,
    )
    suspend fun getEligibleMessages(
        level: EscalationLevel,
        triggerType: TriggerType,
        tone: MessageTone,
        cutoffTimestamp: Long,
    ): List<Message>

    @Query("UPDATE messages SET votesUp = votesUp + 1 WHERE id = :messageId")
    suspend fun voteUp(messageId: Long)

    @Query("UPDATE messages SET votesDown = votesDown + 1 WHERE id = :messageId")
    suspend fun voteDown(messageId: Long)

    @Query("SELECT * FROM messages WHERE votesUp > 0 OR votesDown > 0")
    suspend fun getVotedMessages(): List<Message>

    // Absolute set (back-sync applies the watch's authoritative counts),
    // unlike voteUp/voteDown which increment.
    @Query("UPDATE messages SET votesUp = :votesUp, votesDown = :votesDown WHERE id = :messageId")
    suspend fun setVotes(messageId: Long, votesUp: Int, votesDown: Int)

    @Query("UPDATE messages SET lastShownTimestamp = :timestamp WHERE id = :messageId")
    suspend fun markShown(messageId: Long, timestamp: Long)

    /**
     * Archive (`false`) / unarchive (`true`). An inactive row never fires on the
     * watch (getEligibleMessages requires isActive = 1), is never pruned, and is
     * excluded from the AI example queries. See spec 2026-09-25-library-archive.
     */
    @Query("UPDATE messages SET isActive = :active WHERE id = :messageId")
    suspend fun setActive(messageId: Long, active: Boolean)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(messages: List<Message>)

    @Query("SELECT id, lastShownTimestamp FROM messages WHERE id IN (:ids)")
    suspend fun getShownTimestamps(ids: List<Long>): List<ShownTimestamp>

    /**
     * Watch-side apply of a phone `/messages` payload. Like [insertAll] (REPLACE)
     * for every phone-owned field, but keeps this device's `lastShownTimestamp`
     * for rows it already has, so a phone sync can't reset the 24 h cooldown.
     * See [ShownTimestampMerger]. Transactional: the read and the write see one
     * consistent snapshot. Intended for the watch only (the phone applies
     * `/votes` via [setVotes]). [getShownTimestamps] binds one SQL variable per
     * id, so callers must keep the batch under SQLite's variable limit (999 on
     * API 30); the wear receiver's 500-row ceiling does.
     */
    @Transaction
    suspend fun upsertPreservingShown(messages: List<Message>) {
        val existing = getShownTimestamps(messages.map { it.id })
            .associate { it.id to it.lastShownTimestamp }
        insertAll(ShownTimestampMerger.merge(messages, existing))
    }

    @Query("SELECT * FROM messages ORDER BY (votesUp - votesDown) DESC")
    suspend fun getAllMessages(): List<Message>

    /**
     * Reactive variant of [getAllMessages]. Room emits a new list every time
     * the messages table changes, so observers (e.g. the Library UI) update
     * live after inserts/votes without needing manual reload.
     */
    @Query("SELECT * FROM messages ORDER BY (votesUp - votesDown) DESC")
    fun getAllMessagesFlow(): Flow<List<Message>>

    @Query("SELECT COUNT(*) FROM messages")
    suspend fun getMessageCount(): Int

    @Query(
        """
        SELECT text FROM messages
        WHERE isActive = 1 AND votesUp > votesDown
        ORDER BY (votesUp - votesDown) DESC
        LIMIT :limit
    """,
    )
    suspend fun getLovedTexts(limit: Int): List<String>

    @Query(
        """
        SELECT text FROM messages
        WHERE isActive = 1 AND votesDown > votesUp
        ORDER BY (votesUp - votesDown) ASC
        LIMIT :limit
    """,
    )
    suspend fun getHatedTexts(limit: Int): List<String>

    @Query("DELETE FROM messages WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)
}

/** Projection for [MessageDao.getShownTimestamps]. */
data class ShownTimestamp(val id: Long, val lastShownTimestamp: Long)
