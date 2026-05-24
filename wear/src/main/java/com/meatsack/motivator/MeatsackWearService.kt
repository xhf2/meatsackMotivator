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
import com.meatsack.motivator.health.HealthTracker
import com.meatsack.motivator.messages.MessageRepository
import com.meatsack.motivator.messages.ToneResolver
import com.meatsack.motivator.notification.InsultNotificationService
import com.meatsack.motivator.settings.WatchSettingsCache
import com.meatsack.motivator.trigger.TriggerScheduler
import com.meatsack.shared.constants.TriggerType
import com.meatsack.shared.db.AppDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MeatsackWearService : Service() {

    private lateinit var healthTracker: HealthTracker
    private lateinit var escalationManager: EscalationManager
    private lateinit var messageRepo: MessageRepository
    private lateinit var notificationService: InsultNotificationService
    private lateinit var settings: WatchSettingsCache

    private var pollingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

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
        healthTracker = HealthTracker(applicationContext)
        escalationManager = EscalationManager()
        messageRepo = MessageRepository(db.messageDao())
        notificationService = InsultNotificationService(applicationContext)
        settings = WatchSettingsCache(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(FOREGROUND_NOTIFICATION_ID, createForegroundNotification())
        healthTracker.startTracking()
        val scheduler = TriggerScheduler(this)
        scheduler.scheduleBehindPaceCheck(settings.behindPaceCheckHour)
        scheduler.scheduleEndOfDay(settings.endOfDayHour)
        startPolling()
        Log.d(TAG, "meatsackMotivator service started. Watching you.")
        return START_STICKY
    }

    override fun onDestroy() {
        pollingJob?.cancel()
        scope.cancel()
        healthTracker.stopTracking()
        // Deliberately *not* cancelling WorkManager jobs here: foreground
        // services can be killed by the OS at any time (memory pressure,
        // reboot, battery optimizer), and cancelling on every kill would mean
        // the hourly + end-of-day workers stop firing until the user manually
        // relaunches the watch app. WorkManager outliving the service is the
        // intended shape; re-enqueue on the next service start is idempotent
        // (KEEP / REPLACE policies in TriggerScheduler).
        super.onDestroy()
    }

    private fun startPolling() {
        pollingJob = scope.launch {
            while (true) {
                delay(POLL_INTERVAL_MS)
                // Never let a single bad tick kill the loop — the service keeps its
                // foreground notification alive either way, so a silent loop death
                // would look healthy while the app has become a zombie.
                try {
                    checkInactivity()
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    Log.e(TAG, "Poll tick failed", t)
                }
            }
        }
    }

    private suspend fun checkInactivity() {
        val minutesIdle = healthTracker.getMinutesSinceLastMovement()

        if (healthTracker.hasSignificantMovement()) {
            escalationManager.onMovementDetected()
            return
        }

        if (!escalationManager.shouldTrigger(minutesIdle)) return

        escalationManager.onInactivityDetected(minutesIdle)
        val level = escalationManager.currentLevel.value
        val tone = ToneResolver.resolve(
            contextAwareEnabled = settings.contextAwareEnabled,
            activeHoursStart = settings.activeHoursStart,
            activeHoursEnd = settings.activeHoursEnd,
        )

        val message = messageRepo.selectMessage(level, TriggerType.INACTIVITY, tone) ?: return

        val steps = healthTracker.totalStepsToday.value
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val ampm = if (hour < 12) "am" else "pm"
        val displayHour = if (hour == 0) {
            12
        } else if (hour > 12) {
            hour - 12
        } else {
            hour
        }
        val statsText = "$steps steps. It's $displayHour$ampm. Pathetic."

        Log.d(TAG, "Firing insult: Level ${level.value}, idle ${minutesIdle}min")
        notificationService.deliverInsult(message, statsText)
    }

    private fun createForegroundNotification(): Notification {
        val channel = NotificationChannel(
            FOREGROUND_CHANNEL_ID,
            "meatsackMotivator Service",
            NotificationManager.IMPORTANCE_LOW,
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
