package com.meatsack.motivator.mobile.sync

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.meatsack.shared.db.AppDatabase
import com.meatsack.shared.sync.MessageSerializer
import kotlinx.coroutines.tasks.await

class PhoneSyncSender(private val context: Context) {

    companion object {
        private const val TAG = "PhoneSyncSender"
        private const val PATH_MESSAGES = "/messages"
        private const val KEY_MESSAGE_DATA = "message_data"
        private const val CACHE_SIZE = 50
    }

    suspend fun syncMessagesToWatch(): Int {
        val db = AppDatabase.getDatabase(context)
        val messages = db.messageDao().getAllMessages()
            .filter { it.isActive && it.votesDown < 3 }
            .take(CACHE_SIZE)

        if (messages.isEmpty()) {
            Log.w(TAG, "No messages to sync")
            return 0
        }

        val request = PutDataMapRequest.create(PATH_MESSAGES).apply {
            dataMap.putString(KEY_MESSAGE_DATA, MessageSerializer.serialize(messages))
            dataMap.putLong("timestamp", System.currentTimeMillis())
        }.asPutDataRequest().setUrgent()

        return try {
            Wearable.getDataClient(context).putDataItem(request).await()
            Log.d(TAG, "Synced ${messages.size} messages to watch")
            messages.size
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sync messages", e)
            0
        }
    }
}
