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

class StepTracker(context: Context) {
    private val client = HealthServices.getClient(context).passiveMonitoringClient

    private val _totalStepsToday = MutableStateFlow(0)
    val totalStepsToday: StateFlow<Int> = _totalStepsToday.asStateFlow()

    private var lastMovementTimestamp: Long = System.currentTimeMillis()
    private var stepsInCurrentWindow: Int = 0
    private var windowStartTimestamp: Long = System.currentTimeMillis()

    companion object {
        private const val TAG = "StepTracker"
        private val MOVEMENT_WINDOW_MS: Long =
            EscalationLevel.MOVEMENT_RESET_WINDOW_MINUTES.toLong() * 60 * 1000L
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
