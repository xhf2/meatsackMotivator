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
    }

    suspend fun syncMessagesToWatch(): SyncResult {
        // Everything — the Room read included — sits inside the try so that every failure
        // becomes SyncResult.Failed for the UI to report rather than an app crash. The
        // manual Sync button launches this from rememberCoroutineScope() with no catch of
        // its own, and MessageSerializer.require()s text within limits and free of the
        // '|'/newline separators, throwing IllegalArgumentException on a bad message.
        return try {
            val all = AppDatabase.getDatabase(context).messageDao().getAllMessages()
            val (messages, dropped) = SyncPayload.select(all)

            if (messages.isEmpty()) {
                Log.d(TAG, "No messages to sync")
                return SyncResult.NoMessages
            }
            if (dropped > 0) {
                Log.w(
                    TAG,
                    "Library has ${all.size} rows; only ${SyncPayload.CACHE_SIZE} sent. " +
                        "$dropped lowest-scored fireable rows will not reach the watch until the library shrinks",
                )
            }

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
