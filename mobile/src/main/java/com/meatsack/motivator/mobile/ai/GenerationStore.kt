package com.meatsack.motivator.mobile.ai

import com.meatsack.shared.db.MessageDao
import com.meatsack.shared.model.Message

/**
 * Narrow persistence surface the generator needs, so its multi-level loop can be unit-tested
 * with a fake instead of a real Room database.
 */
interface GenerationStore {
    suspend fun getLovedTexts(limit: Int): List<String>
    suspend fun getHatedTexts(limit: Int): List<String>
    suspend fun getAllMessages(): List<Message>
    suspend fun insertAll(messages: List<Message>)
    suspend fun deleteByIds(ids: List<Long>)
}

/** Production implementation backed by the Room [MessageDao]. */
class RoomGenerationStore(private val dao: MessageDao) : GenerationStore {
    override suspend fun getLovedTexts(limit: Int) = dao.getLovedTexts(limit)
    override suspend fun getHatedTexts(limit: Int) = dao.getHatedTexts(limit)
    override suspend fun getAllMessages() = dao.getAllMessages()
    override suspend fun insertAll(messages: List<Message>) = dao.insertAll(messages)
    override suspend fun deleteByIds(ids: List<Long>) = dao.deleteByIds(ids)
}
