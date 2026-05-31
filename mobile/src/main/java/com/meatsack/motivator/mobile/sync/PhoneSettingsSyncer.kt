package com.meatsack.motivator.mobile.sync

import android.util.Log
import com.google.android.gms.wearable.DataMap
import com.meatsack.shared.sync.SettingsKeys
import com.meatsack.shared.sync.SettingsSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow

/**
 * Source of SettingsSnapshot updates. Production wires this to combine() over
 * SettingsRepository flows; tests use an in-memory MutableStateFlow.
 */
interface SettingsSource {
    val snapshots: Flow<SettingsSnapshot>
    suspend fun current(): SettingsSnapshot
}

/**
 * Single-method abstraction over the Wear DataClient. Production wraps
 * Wearable.getDataClient(context).putDataItem(...); tests fake it.
 */
interface SettingsSink {
    suspend fun put(path: String, dataMap: DataMap)
}

class PhoneSettingsSyncer(
    private val settings: SettingsSource,
    private val sink: SettingsSink,
) {

    companion object {
        private const val TAG = "PhoneSettingsSyncer"
    }

    suspend fun syncNow() {
        val snap = settings.current()
        writeSnapshot(snap)
    }

    private suspend fun writeSnapshot(snap: SettingsSnapshot) {
        try {
            val dm = DataMap()
            snap.toDataMap(dm)
            sink.put(SettingsKeys.PATH, dm)
            Log.d(TAG, "Synced settings: $snap")
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            Log.e(TAG, "Settings sync failed", e)
        }
    }
}
