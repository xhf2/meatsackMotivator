package com.meatsack.motivator.sync

import android.util.Log
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService
import com.meatsack.shared.constants.EscalationLevel
import com.meatsack.shared.constants.MessageSource
import com.meatsack.shared.constants.MessageTone
import com.meatsack.shared.constants.TriggerType
import com.meatsack.shared.db.AppDatabase
import com.meatsack.shared.model.Message
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

                val messages = deserializeMessages(serialized)
                CoroutineScope(Dispatchers.IO).launch {
                    val db = AppDatabase.getDatabase(applicationContext)
                    db.messageDao().insertAll(messages)
                    Log.d(TAG, "Received and stored ${messages.size} messages from phone")
                }
            }
        }
    }

    private fun deserializeMessages(data: String): List<Message> {
        return data.split("\n").mapNotNull { line ->
            val parts = line.split("|")
            if (parts.size < 8) return@mapNotNull null
            try {
                Message(
                    id = parts[0].toLong(),
                    text = parts[1],
                    level = EscalationLevel.fromValue(parts[2].toInt()),
                    triggerType = TriggerType.valueOf(parts[3]),
                    tone = MessageTone.valueOf(parts[4]),
                    source = MessageSource.valueOf(parts[5]),
                    votesUp = parts[6].toInt(),
                    votesDown = parts[7].toInt(),
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse message: $line", e)
                null
            }
        }
    }
}
