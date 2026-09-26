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
        // The phone sends at most SyncPayload.CACHE_SIZE (200) rows per DataItem;
        // 500 is 2.5x headroom. Must stay below 999: MessageDao.getShownTimestamps binds
        // one SQL variable per id, and that is SQLite's limit on API 30 watches.
        private const val MAX_INCOMING_MESSAGES = 500

        /**
         * Applies a decoded phone payload via [MessageDao.upsertPreservingShown]
         * (see [com.meatsack.shared.sync.ShownTimestampMerger] for why plain REPLACE
         * is wrong). Extracted so it can be exercised against a DAO without a
         * DataEventBuffer.
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
                    Log.e(TAG, "Failed to store ${messages.size} messages from $sourceNode", t)
                }
            }
        }
    }
}
