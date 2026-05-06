package com.meatsack.shared.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.meatsack.shared.constants.EscalationLevel
import com.meatsack.shared.constants.MessageTone
import com.meatsack.shared.constants.TriggerType
import com.meatsack.shared.model.Message
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

    @Query("UPDATE messages SET lastShownTimestamp = :timestamp WHERE id = :messageId")
    suspend fun markShown(messageId: Long, timestamp: Long)

    @Query("UPDATE messages SET isActive = 0 WHERE id = :messageId")
    suspend fun deactivate(messageId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(messages: List<Message>)

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
        WHERE isActive = 1
        ORDER BY (votesUp - votesDown) DESC
        LIMIT :limit
    """,
    )
    suspend fun getTopUpvotedTexts(limit: Int): List<String>
}
