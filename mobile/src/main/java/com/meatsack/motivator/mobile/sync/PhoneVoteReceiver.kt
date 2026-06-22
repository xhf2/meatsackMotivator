package com.meatsack.motivator.mobile.sync

import android.util.Log
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService
import com.meatsack.motivator.mobile.MeatsackMobileApp
import com.meatsack.shared.db.AppDatabase
import com.meatsack.shared.sync.SyncChannel
import com.meatsack.shared.sync.VoteSyncSerializer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Applies vote counts pushed from the watch over the /votes channel to the phone
 * Room DB (absolute set, keyed by message id). The reverse of WatchSyncReceiver,
 * which receives the forward (/messages) pipe.
 */
class PhoneVoteReceiver : WearableListenerService() {

    companion object {
        private const val TAG = "PhoneVoteReceiver"
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        dataEvents.forEach { event ->
            val path = event.dataItem.uri.path
            if (path != SyncChannel.PATH_VOTES) return@forEach
            val sourceNode = event.dataItem.uri.host
            val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
            val serialized = dataMap.getString(SyncChannel.KEY_VOTE_DATA)
            if (serialized == null) {
                Log.w(
                    TAG,
                    "Missing ${SyncChannel.KEY_VOTE_DATA} in ${SyncChannel.PATH_VOTES} from node=$sourceNode",
                )
                return@forEach
            }

            val votes = VoteSyncSerializer.deserialize(serialized)
            if (votes.isEmpty()) {
                Log.w(TAG, "Empty or fully-malformed vote payload from node=$sourceNode")
                return@forEach
            }
            if (votes.size > SyncChannel.MAX_VOTE_ROWS) {
                Log.w(TAG, "Dropping oversized vote sync from node=$sourceNode size=${votes.size}")
                return@forEach
            }

            val scope = (applicationContext as MeatsackMobileApp).applicationScope
            scope.launch {
                val dao = AppDatabase.getDatabase(applicationContext).messageDao()
                // Per-row boundary so one bad row doesn't abort the rest of the batch.
                var applied = 0
                votes.forEach { vote ->
                    try {
                        dao.setVotes(vote.id, vote.votesUp, vote.votesDown)
                        applied++
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (t: Throwable) {
                        Log.e(TAG, "Failed to apply vote row id=${vote.id} from node=$sourceNode", t)
                    }
                }
                Log.d(TAG, "Applied $applied/${votes.size} vote rows from node=$sourceNode")
            }
        }
    }
}
