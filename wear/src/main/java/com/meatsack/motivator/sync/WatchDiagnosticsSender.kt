package com.meatsack.motivator.sync

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.meatsack.motivator.diagnostics.WatchDiagnostics
import com.meatsack.shared.sync.DiagnosticsSerializer
import com.meatsack.shared.sync.SyncChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await

/** Outcome of a diagnostics push. Mirrors [WatchVoteSender.VoteSyncResult]'s shape. */
sealed class DiagnosticsSyncResult {
    data class Success(val count: Int) : DiagnosticsSyncResult()
    data object NoData : DiagnosticsSyncResult()
    data class Failed(val error: Throwable) : DiagnosticsSyncResult()
}

/**
 * Pushes a recent window of the watch's diagnostic log to the phone over the
 * `/diagnostics` Data Layer channel. Modelled on [WatchVoteSender]. A fresh timestamp on
 * every send guarantees the DataItem changes (so onDataChanged fires) even if the tail
 * lines happen to repeat.
 *
 * Retained on-device dev/diagnostics tool (see docs/debug/triggering-investigation.md).
 */
class WatchDiagnosticsSender(
    private val context: Context,
    private val diagnostics: WatchDiagnostics,
) {

    companion object {
        private const val TAG = "WatchDiagnosticsSender"
    }

    suspend fun syncToPhone(): DiagnosticsSyncResult = try {
        val lines = diagnostics.recent().takeLast(SyncChannel.MAX_DIAG_ROWS)
        if (lines.isEmpty()) {
            DiagnosticsSyncResult.NoData
        } else {
            val request = PutDataMapRequest.create(SyncChannel.PATH_DIAGNOSTICS).apply {
                dataMap.putString(SyncChannel.KEY_DIAG_DATA, DiagnosticsSerializer.serialize(lines))
                dataMap.putLong(SyncChannel.KEY_TIMESTAMP, System.currentTimeMillis())
            }.asPutDataRequest().setUrgent()

            Wearable.getDataClient(context).putDataItem(request).await()
            DiagnosticsSyncResult.Success(lines.size)
        }
    } catch (ce: CancellationException) {
        throw ce
    } catch (e: Exception) {
        Log.e(TAG, "Failed to sync diagnostics", e)
        DiagnosticsSyncResult.Failed(e)
    }
}
