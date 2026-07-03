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
import com.meatsack.motivator.diagnostics.WatchDiagnostics
import com.meatsack.motivator.escalation.EscalationManager
import com.meatsack.motivator.health.HealthTracker
import com.meatsack.motivator.messages.ActiveWindow
import com.meatsack.motivator.messages.MessageRepository
import com.meatsack.motivator.messages.ToneResolver
import com.meatsack.motivator.notification.InsultNotificationService
import com.meatsack.motivator.settings.WatchSettingsCache
import com.meatsack.motivator.sync.WatchDiagnosticsSender
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

    // Temporary triggering diagnostics (docs/debug/triggering-investigation.md).
    private lateinit var diagnostics: WatchDiagnostics
    private lateinit var diagnosticsSender: WatchDiagnosticsSender

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
        settings = WatchSettingsCache(applicationContext)
        diagnostics = WatchDiagnostics.create(applicationContext)
        diagnosticsSender = WatchDiagnosticsSender(applicationContext, diagnostics)
        healthTracker = HealthTracker(
            applicationContext,
            stepThresholdProvider = { settings.movementStepThreshold },
            windowMinutesProvider = { settings.inactivityThreshold },
            diagnostics = diagnostics,
        )
        escalationManager = EscalationManager(thresholdProvider = { settings.inactivityThreshold })
        messageRepo = MessageRepository(db.messageDao())
        notificationService = InsultNotificationService(applicationContext)
        // The most important line for hypothesis H2: an onCreate with no preceding onDestroy
        // (or a burst of them) is the fingerprint of the OS killing and restarting the service,
        // which resets all in-memory idle/escalation state.
        diagnostics.log(
            "LIFECYCLE onCreate thr=${settings.inactivityThreshold}min " +
                "moveSteps=${settings.movementStepThreshold} " +
                "active=${settings.activeHoursStart}-${settings.activeHoursEnd}",
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(FOREGROUND_NOTIFICATION_ID, createForegroundNotification())
        healthTracker.startTracking()
        val scheduler = TriggerScheduler(this)
        scheduler.scheduleBehindPaceCheck(settings.behindPaceCheckHour)
        scheduler.scheduleEndOfDay(settings.activeHoursEnd)
        startPolling()
        diagnostics.log("LIFECYCLE onStartCommand flags=$flags startId=$startId")
        Log.d(TAG, "meatsackMotivator service started. Watching you.")
        return START_STICKY
    }

    override fun onDestroy() {
        // Log before tearing down the scope so the kill is recorded even on a clean shutdown.
        diagnostics.log("LIFECYCLE onDestroy")
        pollingJob?.cancel()
        scope.cancel()
        healthTracker.stopTracking()
        // Deliberately *not* cancelling WorkManager jobs here: foreground
        // services can be killed by the OS at any time (memory pressure,
        // reboot, battery optimizer), and cancelling on every kill would mean
        // the daily pace + end-of-day workers stop firing until the user
        // manually relaunches the watch app. WorkManager outliving the
        // service is the intended shape; re-enqueue on the next service
        // start is safe (REPLACE policy in TriggerScheduler, plus each
        // worker self-reschedules at the top of doWork).
        super.onDestroy()
    }

    private fun startPolling() {
        // Idempotent restart: cancel any existing loop first. onStartCommand runs on every
        // launcher-icon tap (the OS routes it to the already-alive service, no fresh onCreate),
        // so without this each reopen would spawn an additional concurrent poll loop — all
        // sharing one EscalationManager/HealthTracker — and fire duplicate insults per interval.
        pollingJob?.cancel()
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
        // NOTE: this method's *triggering behavior* is deliberately unchanged — the diagnostics
        // build is evidence-first. The only additions are reads for logging and the per-poll
        // record + push. The branch order and side effects match the original exactly.
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val inWindow = ActiveWindow.contains(hour, settings.activeHoursStart, settings.activeHoursEnd)
        val minutesIdle = healthTracker.getMinutesSinceLastMovement()
        val snap = healthTracker.debugSnapshot()
        val total = healthTracker.totalStepsToday.value
        val hasMove = healthTracker.hasSignificantMovement()
        val prefix = "POLL hour=$hour inWindow=$inWindow idleMin=$minutesIdle " +
            "winSteps=${snap.stepsInCurrentWindow} total=$total move=$hasMove"

        val outcome = when {
            // outside active hours — skip delivery; escalation state is left frozen (not advanced, not reset)
            !inWindow -> "SKIP(outside-active-hours)"
            hasMove -> {
                escalationManager.onMovementDetected()
                "SKIP(movement-reset)"
            }
            !escalationManager.shouldTrigger(minutesIdle) ->
                "SKIP(no-trigger thr=${settings.inactivityThreshold})"
            else -> fireInsult(minutesIdle, hour)
        }

        diagnostics.log("$prefix -> $outcome")
        diagnosticsSender.syncToPhone()
    }

    /** Runs the original fire path and returns a one-line diagnostics outcome. */
    private suspend fun fireInsult(minutesIdle: Int, hour: Int): String {
        escalationManager.onInactivityDetected(minutesIdle)
        val level = escalationManager.currentLevel.value
        val tone = ToneResolver.resolve(
            contextAwareEnabled = settings.contextAwareEnabled,
            workSafeStart = settings.contextAwareStart,
            workSafeEnd = settings.contextAwareEnd,
        )

        val message = messageRepo.selectMessage(level, TriggerType.INACTIVITY, tone)
            ?: return "SKIP(no-message level=${level.value})"

        val steps = healthTracker.totalStepsToday.value
        val ampm = if (hour < 12) "am" else "pm"
        val displayHour = when {
            hour == 0 -> 12
            hour > 12 -> hour - 12
            else -> hour
        }
        val statsText = "$steps steps. It's $displayHour$ampm. Pathetic."

        Log.d(TAG, "Firing insult: Level ${level.value}, idle ${minutesIdle}min")
        notificationService.deliverInsult(message, statsText)
        return "FIRE level=${level.value}"
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
