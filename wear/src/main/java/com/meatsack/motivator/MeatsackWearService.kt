package com.meatsack.motivator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.meatsack.motivator.escalation.EscalationManager
import com.meatsack.motivator.health.StepTracker
import com.meatsack.motivator.messages.MessageRepository
import com.meatsack.motivator.notification.InsultNotificationService
import com.meatsack.shared.constants.MessageTone
import com.meatsack.shared.constants.TriggerType
import com.meatsack.shared.db.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MeatsackWearService : Service() {

    private lateinit var stepTracker: StepTracker
    private lateinit var escalationManager: EscalationManager
    private lateinit var messageRepo: MessageRepository
    private lateinit var notificationService: InsultNotificationService

    private var pollingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default)

    companion object {
        private const val TAG = "MeatsackWearService"
        private const val FOREGROUND_CHANNEL_ID = "meatsack_service"
        private const val FOREGROUND_NOTIFICATION_ID = 2
        private const val POLL_INTERVAL_MS = 60_000L
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val db = AppDatabase.getDatabase(applicationContext)
        stepTracker = StepTracker(applicationContext)
        escalationManager = EscalationManager()
        messageRepo = MessageRepository(db.messageDao())
        notificationService = InsultNotificationService(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(FOREGROUND_NOTIFICATION_ID, createForegroundNotification())
        stepTracker.startTracking()
        startPolling()
        Log.d(TAG, "meatsackMotivator service started. Watching you.")
        return START_STICKY
    }

    override fun onDestroy() {
        pollingJob?.cancel()
        stepTracker.stopTracking()
        super.onDestroy()
    }

    private fun startPolling() {
        pollingJob = scope.launch {
            while (true) {
                delay(POLL_INTERVAL_MS)
                checkInactivity()
            }
        }
    }

    private suspend fun checkInactivity() {
        val minutesIdle = stepTracker.getMinutesSinceLastMovement()

        if (stepTracker.hasSignificantMovement()) {
            escalationManager.onMovementDetected()
            return
        }

        if (!escalationManager.shouldTrigger(minutesIdle)) return

        escalationManager.onInactivityDetected(minutesIdle)
        val level = escalationManager.currentLevel.value
        val tone = MessageTone.FULL_SEND

        val message = messageRepo.selectMessage(level, TriggerType.INACTIVITY, tone) ?: return

        val steps = stepTracker.totalStepsToday.value
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val ampm = if (hour < 12) "am" else "pm"
        val displayHour = if (hour == 0) 12 else if (hour > 12) hour - 12 else hour
        val statsText = "$steps steps. It's $displayHour$ampm. Pathetic."

        Log.d(TAG, "Firing insult: Level ${level.value}, idle ${minutesIdle}min")
        notificationService.deliverInsult(message, statsText)
    }

    private fun createForegroundNotification(): Notification {
        val channel = NotificationChannel(
            FOREGROUND_CHANNEL_ID,
            "meatsackMotivator Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps meatsackMotivator running"
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)

        return NotificationCompat.Builder(this, FOREGROUND_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("meatsackMotivator")
            .setContentText("Watching you, you lazy meatsack.")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }
}
