package com.meatsack.motivator.sync

import android.util.Log
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService
import com.meatsack.motivator.MeatsackWearApp
import com.meatsack.shared.db.AppDatabase
import com.meatsack.shared.db.MessageDao
import com.meatsack.shared.model.Message
import com.meatsack.shared.sync.MessageSerializer
import com.meatsack.shared.sync.SyncChannel
import kotlinx.coroutines.launch

class WatchSyncReceiver : WearableListenerService() {

    companion object {
        private const val TAG = "WatchSyncReceiver"

        // Hard ceiling to bound the blast radius of a malformed or hostile payload.
        // v1 syncs at most 50 messages at a time; 500 is 10x headroom.
        private const val MAX_INCOMING_MESSAGES = 500

        /**
         * Applies a decoded phone payload to the watch DB. Phone rows always carry
         * lastShownTimestamp = 0 (the phone never delivers), so a plain REPLACE
         * would wipe this watch's 24 h cooldown on every sync — including the
         * debounced auto-sync after each phone vote. The preserving upsert keeps
         * the watch's timestamp for rows it already has. Extracted so it can be
         * exercised against a real Room DB without a DataEventBuffer.
         */
        suspend fun store(messages: List<Message>, dao: MessageDao) {
            dao.upsertPreservingShown(messages)
        }
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        dataEvents.forEach { event ->
            val path = event.dataItem.uri.path
            if (path != SyncChannel.PATH_MESSAGES) return@forEach
            val sourceNode = event.dataItem.uri.host
            val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
            val serialized = dataMap.getString(SyncChannel.KEY_MESSAGE_DATA)
            if (serialized == null) {
                Log.w(
                    TAG,
                    "Missing ${SyncChannel.KEY_MESSAGE_DATA} in ${SyncChannel.PATH_MESSAGES} from node=$sourceNode",
                )
                return@forEach
            }

            val messages = MessageSerializer.deserialize(serialized)
            if (messages.isEmpty()) {
                Log.w(TAG, "Empty or fully-malformed payload from node=$sourceNode")
                return@forEach
            }
            if (messages.size > MAX_INCOMING_MESSAGES) {
                Log.w(
                    TAG,
                    "Dropping oversized sync from node=$sourceNode size=${messages.size}",
                )
                return@forEach
            }

            val scope = (applicationContext as MeatsackWearApp).applicationScope
            scope.launch {
                try {
                    store(messages, AppDatabase.getDatabase(applicationContext).messageDao())
                    Log.d(TAG, "Stored ${messages.size} messages from node=$sourceNode")
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed to insert ${messages.size} messages from $sourceNode", t)
                }
            }
        }
    }
}
