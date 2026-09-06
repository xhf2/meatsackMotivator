package com.meatsack.motivator.mobile.ui.library

import com.meatsack.shared.db.MessageDao

/**
 * Narrow persistence surface for phone-side voting, so [LibraryEditor] can be
 * unit-tested with a fake instead of a Room database. Mirrors the
 * `GenerationStore` seam used by the AI generator.
 *
 * Both methods increment the same columns the watch's InsultActivity votes
 * bump, so a phone vote and a watch vote are the same operation.
 */
interface VoteStore {
    suspend fun voteUp(messageId: Long)
    suspend fun voteDown(messageId: Long)
}

/** Production implementation backed by the Room [MessageDao]. */
class RoomVoteStore(private val dao: MessageDao) : VoteStore {
    override suspend fun voteUp(messageId: Long) = dao.voteUp(messageId)
    override suspend fun voteDown(messageId: Long) = dao.voteDown(messageId)
}
