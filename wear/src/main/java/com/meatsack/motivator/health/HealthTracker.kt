package com.meatsack.motivator.health

import android.content.Context
import android.util.Log
import androidx.health.services.client.HealthServices
import androidx.health.services.client.PassiveListenerCallback
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.PassiveListenerConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class HealthTracker(
    private val context: Context,
    stepThresholdProvider: () -> Int,
    windowMinutesProvider: () -> Int,
    // Temporary triggering diagnostics (docs/debug/triggering-investigation.md); null in
    // any caller that doesn't want step-level logging.
    private val diagnostics: com.meatsack.motivator.diagnostics.WatchDiagnostics? = null,
) {
    private val client = HealthServices.getClient(context).passiveMonitoringClient

    // Android-free movement/idle logic; fed the user's step threshold and the
    // (unified) window = inactivity threshold via the provider lambdas above.
    private val detector = MovementDetector(stepThresholdProvider, windowMinutesProvider)

    private val _totalStepsToday = MutableStateFlow(0)
    val totalStepsToday: StateFlow<Int> = _totalStepsToday.asStateFlow()

    private val _floorsToday = MutableStateFlow(0)
    val floorsToday: StateFlow<Int> = _floorsToday.asStateFlow()

    private val _caloriesToday = MutableStateFlow(0)
    val caloriesToday: StateFlow<Int> = _caloriesToday.asStateFlow()

    // Heart rate intentionally not subscribed in v2: Wear OS 5+ Health Services
    // requires the Health Connect permission android.permission.health.READ_HEART_RATE
    // even in PASSIVE mode (the v2 plan's assumption that PASSIVE bypasses this on
    // API 33+ turned out to be wrong on real Wear OS 5 emulators). Re-add in v3
    // alongside proper Health Connect integration.

    companion object {
        private const val TAG = "HealthTracker"

        /** SharedPreferences file for the worker hand-off (steps + freshness). */
        const val WATCH_HEALTH_PREFS = "watch_health"
        const val KEY_STEPS_TODAY = "steps_today"
        const val KEY_LAST_UPDATED = "last_updated"

        /**
         * If a worker reads step data older than this, it should treat the
         * SharedPreferences mirror as untrustworthy (e.g., post-process-death,
         * post-day-rollover before the first datapoint arrives).
         */
        const val STALE_THRESHOLD_MS = 60 * 60 * 1000L // 1 hour
    }

    private val callback = object : PassiveListenerCallback {
        override fun onNewDataPointsReceived(dataPoints: DataPointContainer) {
            try {
                dataPoints.getData(DataType.STEPS_DAILY).lastOrNull()?.let {
                    val count = it.value.toInt()
                    _totalStepsToday.value = count
                    detector.onStepTotal(count)
                    diagnostics?.let { d ->
                        val s = detector.debugSnapshot()
                        d.log(
                            "STEP total=$count winSteps=${s.stepsInCurrentWindow} " +
                                "winAgeMin=${s.windowAgeMs / 60_000L} idleMin=${s.minutesSinceLastMovement}",
                        )
                    }
                    // Hand-off for WorkManager workers (BehindPaceWorker, EndOfDayWorker)
                    // that don't have direct access to HealthServices. The timestamp
                    // is what lets workers reject stale or never-written values
                    // (otherwise default-0 reads would falsely flag the user as
                    // behind pace on first launch / after process death).
                    context.getSharedPreferences(WATCH_HEALTH_PREFS, Context.MODE_PRIVATE)
                        .edit()
                        .putInt(KEY_STEPS_TODAY, count)
                        .putLong(KEY_LAST_UPDATED, System.currentTimeMillis())
                        .apply()
                }
                dataPoints.getData(DataType.FLOORS_DAILY).lastOrNull()?.let {
                    _floorsToday.value = it.value.toInt()
                }
                dataPoints.getData(DataType.CALORIES_DAILY).lastOrNull()?.let {
                    _caloriesToday.value = it.value.toInt()
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to process health data point", t)
            }
        }
    }

    fun startTracking() {
        val config = PassiveListenerConfig.builder()
            .setDataTypes(
                setOf(
                    DataType.STEPS_DAILY,
                    DataType.FLOORS_DAILY,
                    DataType.CALORIES_DAILY,
                ),
            )
            .build()
        // setPassiveListenerCallback is fire-and-forget (returns void) on
        // health-services-client 1.0.0-rc02; only setPassiveListenerServiceAsync
        // returns a ListenableFuture. runCatching surfaces any synchronous
        // registration error (e.g., Health Services unavailable on device).
        runCatching { client.setPassiveListenerCallback(config, callback) }
            .onSuccess { Log.d(TAG, "Health tracking started (steps, floors, calories)") }
            .onFailure { Log.e(TAG, "Failed to register passive listener", it) }
    }

    fun stopTracking() {
        val future = client.clearPassiveListenerCallbackAsync()
        future.addListener(
            {
                try {
                    future.get()
                    Log.d(TAG, "Health tracking stopped")
                } catch (ie: InterruptedException) {
                    // Restore interrupt flag so callers up the stack can observe it.
                    Thread.currentThread().interrupt()
                    Log.e(TAG, "Interrupted while clearing passive listener callback", ie)
                } catch (ce: java.util.concurrent.CancellationException) {
                    Log.w(TAG, "Clear passive listener callback was cancelled", ce)
                } catch (ee: java.util.concurrent.ExecutionException) {
                    Log.e(TAG, "Failed to clear passive listener callback", ee.cause ?: ee)
                }
            },
            // Direct executor: listener does only cheap log calls. future.get() returns
            // immediately because the listener contract guarantees completion. Do not
            // add blocking work here without switching to a real executor.
            Runnable::run,
        )
    }

    fun getMinutesSinceLastMovement(): Int = detector.minutesSinceLastMovement()

    fun hasSignificantMovement(): Boolean = detector.hasSignificantMovement()

    /** Diagnostics view of the underlying detector state (temporary). */
    fun debugSnapshot(): MovementSnapshot = detector.debugSnapshot()
}
