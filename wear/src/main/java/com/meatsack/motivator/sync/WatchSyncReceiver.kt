package com.meatsack.motivator.sync

import android.util.Log
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService
import com.meatsack.shared.db.AppDatabase
import com.meatsack.shared.sync.MessageSerializer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class WatchSyncReceiver : WearableListenerService() {

    companion object {
        private const val TAG = "WatchSyncReceiver"
        private const val PATH_MESSAGES = "/messages"
        private const val KEY_MESSAGE_DATA = "message_data"
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        dataEvents.forEach { event ->
            val path = event.dataItem.uri.path
            if (path == PATH_MESSAGES) {
                val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
                val serialized = dataMap.getString(KEY_MESSAGE_DATA) ?: return@forEach
                val messages = MessageSerializer.deserialize(serialized)
                CoroutineScope(Dispatchers.IO).launch {
                    val db = AppDatabase.getDatabase(applicationContext)
                    db.messageDao().insertAll(messages)
                    Log.d(TAG, "Received and stored ${messages.size} messages from phone")
                }
            }
        }
    }
}
