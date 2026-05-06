package com.meatsack.motivator.health

import android.content.Context
import android.util.Log
import androidx.health.services.client.HealthServices
import androidx.health.services.client.PassiveListenerCallback
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.PassiveListenerConfig
import com.meatsack.shared.constants.EscalationLevel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class HealthTracker(private val context: Context) {
    private val client = HealthServices.getClient(context).passiveMonitoringClient

    private val _totalStepsToday = MutableStateFlow(0)
    val totalStepsToday: StateFlow<Int> = _totalStepsToday.asStateFlow()

    private val _floorsToday = MutableStateFlow(0)
    val floorsToday: StateFlow<Int> = _floorsToday.asStateFlow()

    private val _caloriesToday = MutableStateFlow(0)
    val caloriesToday: StateFlow<Int> = _caloriesToday.asStateFlow()

    private val _lastHeartRate = MutableStateFlow<Int?>(null)
    val lastHeartRate: StateFlow<Int?> = _lastHeartRate.asStateFlow()

    private var lastMovementTimestamp: Long = System.currentTimeMillis()
    private var stepsInCurrentWindow: Int = 0
    private var windowStartTimestamp: Long = System.currentTimeMillis()

    companion object {
        private const val TAG = "HealthTracker"
        private val MOVEMENT_WINDOW_MS: Long =
            EscalationLevel.MOVEMENT_RESET_WINDOW_MINUTES.toLong() * 60 * 1000L
    }

    private val callback = object : PassiveListenerCallback {
        override fun onNewDataPointsReceived(dataPoints: DataPointContainer) {
            try {
                dataPoints.getData(DataType.STEPS_DAILY).lastOrNull()?.let {
                    val count = it.value.toInt()
                    _totalStepsToday.value = count
                    trackMovement(count)
                    // Hand-off for WorkManager workers (BehindPaceWorker, EndOfDayWorker)
                    // that don't have direct access to HealthServices.
                    context.getSharedPreferences("watch_health", Context.MODE_PRIVATE)
                        .edit().putInt("steps_today", count).apply()
                }
                dataPoints.getData(DataType.FLOORS_DAILY).lastOrNull()?.let {
                    _floorsToday.value = it.value.toInt()
                }
                dataPoints.getData(DataType.CALORIES_DAILY).lastOrNull()?.let {
                    _caloriesToday.value = it.value.toInt()
                }
                dataPoints.getData(DataType.HEART_RATE_BPM).lastOrNull()?.let {
                    _lastHeartRate.value = it.value.toInt()
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
                    DataType.HEART_RATE_BPM,
                ),
            )
            .build()
        // setPassiveListenerCallback is fire-and-forget (returns void) on
        // health-services-client 1.0.0-rc02; only setPassiveListenerServiceAsync
        // returns a ListenableFuture. runCatching surfaces any synchronous
        // registration error (e.g., Health Services unavailable on device).
        runCatching { client.setPassiveListenerCallback(config, callback) }
            .onSuccess { Log.d(TAG, "Health tracking started (steps, floors, calories, HR)") }
            .onFailure { Log.e(TAG, "Failed to register passive listener", it) }
    }

    fun stopTracking() {
        client.clearPassiveListenerCallbackAsync()
        Log.d(TAG, "Health tracking stopped")
    }

    fun getMinutesSinceLastMovement(): Int {
        synchronized(this) {
            val elapsed = System.currentTimeMillis() - lastMovementTimestamp
            return (elapsed / 60_000).toInt()
        }
    }

    fun hasSignificantMovement(): Boolean {
        synchronized(this) {
            return stepsInCurrentWindow >= EscalationLevel.MOVEMENT_RESET_STEPS
        }
    }

    private fun trackMovement(currentTotal: Int) {
        synchronized(this) {
            val now = System.currentTimeMillis()

            // Reset window if it's been more than 5 minutes
            if (now - windowStartTimestamp > MOVEMENT_WINDOW_MS) {
                stepsInCurrentWindow = 0
                windowStartTimestamp = now
            }

            stepsInCurrentWindow++

            if (stepsInCurrentWindow >= EscalationLevel.MOVEMENT_RESET_STEPS) {
                lastMovementTimestamp = now
                stepsInCurrentWindow = 0
                windowStartTimestamp = now
            }
        }
    }
}
