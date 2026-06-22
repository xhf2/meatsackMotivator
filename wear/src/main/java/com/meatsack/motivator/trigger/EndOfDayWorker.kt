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

        // Reschedule for tomorrow first so any early-return below doesn't kill
        // the chain. Wrapped so a thrown enqueue (WorkManager init race,
        // negative delay from clock jump, OEM SecurityException) returns
        // Result.retry() instead of silently zombie-ing the daily reckoning.
        try {
            TriggerScheduler(ctx).scheduleEndOfDay(settings.endOfDayHour)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to reschedule EndOfDayWorker; retry queued", t)
            return Result.retry()
        }

        if (!settings.endOfDayEnabled) {
            Log.d(TAG, "End-of-day messages disabled; skipping")
            return Result.success()
        }

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
            settings.contextAwareStart,
            settings.contextAwareEnd,
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
