package com.meatsack.motivator.mobile.sync

import android.util.Log
import com.google.android.gms.wearable.DataMap
import com.meatsack.shared.sync.SettingsKeys
import com.meatsack.shared.sync.SettingsSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

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
        private const val MAX_RETRY_ATTEMPTS = 3
    }

    @OptIn(kotlinx.coroutines.FlowPreview::class)
    fun start(scope: CoroutineScope) {
        scope.launch {
            settings.snapshots
                .debounce(500.milliseconds)
                .distinctUntilChanged()
                .retryWhen { cause, attempt ->
                    if (attempt < MAX_RETRY_ATTEMPTS) {
                        Log.w(TAG, "settings flow failure (attempt=$attempt), retrying", cause)
                        delay(((1L shl attempt.toInt()).coerceAtMost(8)).seconds)
                        true
                    } else {
                        Log.e(TAG, "settings flow gave up after $MAX_RETRY_ATTEMPTS retries", cause)
                        false
                    }
                }
                .catch { e -> Log.e(TAG, "settings flow terminated", e) }
                .collect { snap -> writeSnapshot(snap) }
        }
    }

    suspend fun syncNow() {
        writeSnapshot(settings.current())
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
