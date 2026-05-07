package com.meatsack.motivator.trigger

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.meatsack.motivator.health.HealthTracker
import com.meatsack.motivator.messages.MessageRepository
import com.meatsack.motivator.messages.ToneResolver
import com.meatsack.motivator.notification.InsultNotificationService
import com.meatsack.motivator.settings.WatchSettingsCache
import com.meatsack.shared.constants.EscalationLevel
import com.meatsack.shared.constants.TriggerType
import com.meatsack.shared.db.AppDatabase

class EndOfDayWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val settings = WatchSettingsCache(ctx)

        // Always reschedule for tomorrow first — if any path below early-returns
        // (goal hit, no message, stale data) the chain must not die.
        TriggerScheduler(ctx).scheduleEndOfDay(settings.endOfDayHour)

        val db = AppDatabase.getDatabase(ctx)
        val repo = MessageRepository(db.messageDao())
        val notifier = InsultNotificationService(ctx)

        val healthPrefs = ctx.getSharedPreferences(HealthTracker.WATCH_HEALTH_PREFS, Context.MODE_PRIVATE)
        val lastUpdated = healthPrefs.getLong(HealthTracker.KEY_LAST_UPDATED, 0L)
        val ageMs = System.currentTimeMillis() - lastUpdated
        if (lastUpdated == 0L || ageMs > HealthTracker.STALE_THRESHOLD_MS) {
            Log.w(TAG, "Step data missing or stale (age=${ageMs}ms); skipping end-of-day insult")
            return Result.success()
        }
        val currentSteps = healthPrefs.getInt(HealthTracker.KEY_STEPS_TODAY, 0)
        val goal = settings.dailyStepGoal

        // Level depends on outcome. Missed by a lot -> existential. Near miss -> savage.
        val level = when {
            currentSteps >= goal -> return Result.success() // hit the goal; no reckoning
            currentSteps >= goal * 0.75 -> EscalationLevel.SAVAGE
            currentSteps >= goal * 0.5 -> EscalationLevel.NUCLEAR
            else -> EscalationLevel.EXISTENTIAL
        }

        val tone = ToneResolver.resolve(
            settings.contextAwareEnabled,
            settings.activeHoursStart,
            settings.activeHoursEnd,
        )
        val message = repo.selectMessage(level, TriggerType.END_OF_DAY, tone)
            ?: repo.selectMessage(level, TriggerType.INACTIVITY, tone)
            ?: run {
                Log.w(TAG, "No END_OF_DAY or fallback message for level=$level tone=$tone")
                return Result.success()
            }

        val stats = "$currentSteps / $goal. Day's over."
        notifier.deliverInsult(message, stats)
        return Result.success()
    }

    companion object {
        private const val TAG = "EndOfDayWorker"
    }
}
