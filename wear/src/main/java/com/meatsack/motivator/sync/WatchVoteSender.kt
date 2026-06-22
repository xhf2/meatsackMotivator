package com.meatsack.motivator.sync

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.meatsack.shared.db.AppDatabase
import com.meatsack.shared.sync.SyncChannel
import com.meatsack.shared.sync.VoteSnapshot
import com.meatsack.shared.sync.VoteSyncSerializer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await

/**
 * Outcome of a vote back-sync. Mirrors PhoneSyncSender.SyncResult so callers can
 * distinguish "nothing to send" from a real failure.
 */
sealed class VoteSyncResult {
    data class Success(val count: Int) : VoteSyncResult()
    data object NoVotes : VoteSyncResult()
    data class Failed(val error: Throwable) : VoteSyncResult()
}

/**
 * Pushes the watch's current vote counts to the phone over the /votes Data Layer
 * channel. The reverse of PhoneSyncSender's forward (/messages) pipe. Absolute
 * counts keyed by message id, with a timestamp so every vote propagates.
 */
class WatchVoteSender(private val context: Context) {

    companion object {
        private const val TAG = "WatchVoteSender"
    }

    suspend fun syncVotesToPhone(): VoteSyncResult {
        val snapshots = AppDatabase.getDatabase(context).messageDao().getVotedMessages()
            .take(SyncChannel.MAX_VOTE_ROWS)
            .map { VoteSnapshot(it.id, it.votesUp, it.votesDown) }

        if (snapshots.isEmpty()) {
            Log.d(TAG, "No votes to sync")
            return VoteSyncResult.NoVotes
        }

        val request = PutDataMapRequest.create(SyncChannel.PATH_VOTES).apply {
            dataMap.putString(SyncChannel.KEY_VOTE_DATA, VoteSyncSerializer.serialize(snapshots))
            dataMap.putLong(SyncChannel.KEY_TIMESTAMP, System.currentTimeMillis())
        }.asPutDataRequest().setUrgent()

        return try {
            Wearable.getDataClient(context).putDataItem(request).await()
            Log.d(TAG, "Synced ${snapshots.size} vote rows to phone")
            VoteSyncResult.Success(snapshots.size)
        } catch (ce: CancellationException) {
            // Don't absorb cancellation — propagating it keeps structured concurrency honest.
            throw ce
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sync votes", e)
            VoteSyncResult.Failed(e)
        }
    }
}
