package com.meatsack.motivator.mobile.sync

import android.util.Log
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService
import com.meatsack.motivator.mobile.MeatsackMobileApp
import com.meatsack.shared.sync.DiagnosticsSerializer
import com.meatsack.shared.sync.SyncChannel
import kotlinx.coroutines.launch

/**
 * Receives the watch's temporary `/diagnostics` pushes and overlap-merges each window into
 * the phone's persistent log (read by the Debug screen). Mirrors [PhoneVoteReceiver].
 *
 * Temporary — delete with the rest of the diagnostics pipe
 * (docs/debug/triggering-investigation.md).
 */
class PhoneDiagnosticsReceiver : WearableListenerService() {

    companion object {
        private const val TAG = "PhoneDiagnosticsRecv"
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        dataEvents.forEach { event ->
            if (event.dataItem.uri.path != SyncChannel.PATH_DIAGNOSTICS) return@forEach
            val sourceNode = event.dataItem.uri.host
            val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
            val serialized = dataMap.getString(SyncChannel.KEY_DIAG_DATA)
            if (serialized == null) {
                Log.w(TAG, "Missing ${SyncChannel.KEY_DIAG_DATA} from node=$sourceNode")
                return@forEach
            }

            val lines = DiagnosticsSerializer.deserialize(serialized)
            if (lines.isEmpty()) return@forEach

            val scope = (applicationContext as MeatsackMobileApp).applicationScope
            scope.launch {
                PhoneDiagnostics.store(applicationContext).appendMerging(lines)
                Log.d(TAG, "Merged ${lines.size} diagnostic lines from node=$sourceNode")
            }
        }
    }
}
