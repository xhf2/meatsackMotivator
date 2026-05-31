package com.meatsack.motivator.mobile.sync

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.meatsack.motivator.mobile.data.SettingsRepository
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
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

/**
 * Production SettingsSource backed by SettingsRepository. Combines the 7
 * watch-relevant Flows into a single SettingsSnapshot.
 */
class RepositorySettingsSource(private val repo: SettingsRepository) : SettingsSource {
    override val snapshots: Flow<SettingsSnapshot> = kotlinx.coroutines.flow.combine(
        repo.dailyStepGoal,
        repo.inactivityThreshold,
        repo.activeHoursStart,
        repo.activeHoursEnd,
        repo.contextAwareEnabled,
        repo.endOfDayHour,
        repo.behindPaceCheckHour,
    ) { values ->
        SettingsSnapshot(
            dailyStepGoal = values[0] as Int,
            inactivityThreshold = values[1] as Int,
            activeHoursStart = values[2] as Int,
            activeHoursEnd = values[3] as Int,
            contextAwareEnabled = values[4] as Boolean,
            endOfDayHour = values[5] as Int,
            behindPaceCheckHour = values[6] as Int,
        )
    }

    override suspend fun current(): SettingsSnapshot =
        SettingsSnapshot(
            dailyStepGoal = repo.dailyStepGoal.first(),
            inactivityThreshold = repo.inactivityThreshold.first(),
            activeHoursStart = repo.activeHoursStart.first(),
            activeHoursEnd = repo.activeHoursEnd.first(),
            contextAwareEnabled = repo.contextAwareEnabled.first(),
            endOfDayHour = repo.endOfDayHour.first(),
            behindPaceCheckHour = repo.behindPaceCheckHour.first(),
        )
}

/** Production sink that writes via Wearable.getDataClient. */
class WearableSettingsSink(private val context: Context) : SettingsSink {
    override suspend fun put(path: String, dataMap: DataMap) {
        val req = PutDataMapRequest.create(path).apply {
            this.dataMap.putAll(dataMap)
        }.asPutDataRequest().setUrgent()
        Wearable.getDataClient(context).putDataItem(req).await()
    }
}
