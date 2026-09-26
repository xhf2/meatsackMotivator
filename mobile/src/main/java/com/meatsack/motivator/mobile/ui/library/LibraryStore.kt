package com.meatsack.motivator.mobile.ui.library

import com.meatsack.shared.db.MessageDao

/**
 * Narrow persistence surface for phone-side Library mutations (votes, archive,
 * delete), so [LibraryEditor] can be unit-tested with a fake instead of a Room
 * database. Mirrors the `GenerationStore` seam used by the AI generator.
 *
 * The vote methods increment the same columns the watch's InsultActivity votes
 * bump, so a phone vote and a watch vote are the same operation.
 */
interface LibraryStore {
    suspend fun voteUp(messageId: Long)
    suspend fun voteDown(messageId: Long)

    /** Archive (`false`) / unarchive (`true`). See docs/superpowers/specs/2026-09-25-library-archive-design.md. */
    suspend fun setActive(messageId: Long, active: Boolean)

    /** Hard delete on the phone only; the watch never deletes rows. */
    suspend fun deleteByIds(ids: List<Long>)
}

/** Production implementation backed by the Room [MessageDao]. */
class RoomLibraryStore(private val dao: MessageDao) : LibraryStore {
    override suspend fun voteUp(messageId: Long) = dao.voteUp(messageId)
    override suspend fun voteDown(messageId: Long) = dao.voteDown(messageId)
    override suspend fun setActive(messageId: Long, active: Boolean) = dao.setActive(messageId, active)
    override suspend fun deleteByIds(ids: List<Long>) = dao.deleteByIds(ids)
}
