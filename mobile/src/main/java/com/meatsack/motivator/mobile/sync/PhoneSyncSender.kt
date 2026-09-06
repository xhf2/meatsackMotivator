package com.meatsack.motivator.mobile.sync

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.meatsack.shared.db.AppDatabase
import com.meatsack.shared.sync.MessageSerializer
import com.meatsack.shared.sync.SyncChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await

/**
 * Result of a push to the watch. Distinguishing [NoMessages] from [Failed] lets
 * the UI show accurate feedback: "synced 12" vs "nothing to send" vs "send failed".
 */
sealed class SyncResult {
    data class Success(val count: Int) : SyncResult()
    data object NoMessages : SyncResult()
    data class Failed(val error: Throwable) : SyncResult()
}

class PhoneSyncSender(private val context: Context) {

    companion object {
        private const val TAG = "PhoneSyncSender"

        /**
         * Maximum messages pushed in a single DataItem. Sized to comfortably hold
         * the bundled seed (the editable insults.json — a few dozen rows across
         * INACTIVITY + BEHIND_PACE + END_OF_DAY) plus headroom for AI-generated
         * growth, while staying well under Wear's ~100 KB DataItem limit
         * (200 × ~200 chars × 2 bytes ≈ 80 KB).
         *
         * Was 50 in v1 when the seed had only 49 INACTIVITY rows; that ceiling
         * silently truncated v2 seed rows on phones with no voted messages,
         * meaning watch-side workers couldn't find BEHIND_PACE/END_OF_DAY
         * messages and always fell back to the INACTIVITY pool.
         */
        private const val CACHE_SIZE = 200
    }

    suspend fun syncMessagesToWatch(): SyncResult {
        val db = AppDatabase.getDatabase(context)
        // Send every active row, *including* ones with votesDown >= 3. The watch's
        // getEligibleMessages() already hides rejected rows, so sending them can't
        // make them fire — but omitting them means a phone-side downvote that
        // reaches 3 never reaches the watch, whose stale copy would keep firing.
        // Rows the user has rated down tend to sort last (getAllMessages orders
        // by net score DESC), so they are the most likely to fall off CACHE_SIZE.
        val messages = db.messageDao().getAllMessages()
            .filter { it.isActive }
            .take(CACHE_SIZE)

        if (messages.isEmpty()) {
            Log.d(TAG, "No messages to sync")
            return SyncResult.NoMessages
        }

        // serialize() is inside the try: MessageSerializer.require()s text within limits
        // and free of the '|'/newline separators, throwing IllegalArgumentException on a
        // bad message. Building the request here (not before the try) means one malformed
        // message yields SyncResult.Failed for the UI to report — not an app crash.
        return try {
            val request = PutDataMapRequest.create(SyncChannel.PATH_MESSAGES).apply {
                dataMap.putString(SyncChannel.KEY_MESSAGE_DATA, MessageSerializer.serialize(messages))
                dataMap.putLong(SyncChannel.KEY_TIMESTAMP, System.currentTimeMillis())
            }.asPutDataRequest().setUrgent()
            Wearable.getDataClient(context).putDataItem(request).await()
            Log.d(TAG, "Synced ${messages.size} messages to watch")
            SyncResult.Success(messages.size)
        } catch (ce: CancellationException) {
            // Don't absorb cancellation — propagating it keeps structured concurrency honest.
            throw ce
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sync messages", e)
            SyncResult.Failed(e)
        }
    }
}
