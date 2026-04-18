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

class StepTracker(context: Context) {
    private val client = HealthServices.getClient(context).passiveMonitoringClient

    private val _totalStepsToday = MutableStateFlow(0)
    val totalStepsToday: StateFlow<Int> = _totalStepsToday

    private var lastMovementTimestamp: Long = System.currentTimeMillis()
    private var stepsInCurrentWindow: Int = 0
    private var windowStartTimestamp: Long = System.currentTimeMillis()

    companion object {
        private const val TAG = "StepTracker"
        private const val MOVEMENT_WINDOW_MS = 5 * 60 * 1000L // 5 minutes
        private const val MOVEMENT_THRESHOLD_STEPS = 50
    }

    private val callback = object : PassiveListenerCallback {
        override fun onNewDataPointsReceived(dataPoints: DataPointContainer) {
            val steps = dataPoints.getData(DataType.STEPS_DAILY).lastOrNull()
            if (steps != null) {
                val stepCount = steps.value
                _totalStepsToday.value = stepCount.toInt()
                trackMovement(stepCount.toInt())
                Log.d(TAG, "Steps today: $stepCount")
            }
        }
    }

    fun startTracking() {
        val config = PassiveListenerConfig.builder()
            .setDataTypes(setOf(DataType.STEPS_DAILY))
            .build()
        client.setPassiveListenerCallback(config, callback)
        Log.d(TAG, "Step tracking started")
    }

    fun stopTracking() {
        client.clearPassiveListenerCallbackAsync()
        Log.d(TAG, "Step tracking stopped")
    }

    fun getMinutesSinceLastMovement(): Int {
        val elapsed = System.currentTimeMillis() - lastMovementTimestamp
        return (elapsed / 60_000).toInt()
    }

    fun hasSignificantMovement(): Boolean {
        return stepsInCurrentWindow >= MOVEMENT_THRESHOLD_STEPS
    }

    private fun trackMovement(currentTotal: Int) {
        val now = System.currentTimeMillis()

        // Reset window if it's been more than 5 minutes
        if (now - windowStartTimestamp > MOVEMENT_WINDOW_MS) {
            stepsInCurrentWindow = 0
            windowStartTimestamp = now
        }

        stepsInCurrentWindow++

        if (stepsInCurrentWindow >= MOVEMENT_THRESHOLD_STEPS) {
            lastMovementTimestamp = now
            stepsInCurrentWindow = 0
            windowStartTimestamp = now
        }
    }
}
