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
import com.meatsack.shared.constants.TriggerType
import com.meatsack.shared.db.AppDatabase
import java.util.Calendar

class BehindPaceWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val settings = WatchSettingsCache(ctx)

        // Reschedule for tomorrow first so any early-return below doesn't kill
        // the chain. Wrapped so a thrown enqueue (WorkManager init race,
        // negative delay from clock jump, OEM SecurityException) returns
        // Result.retry() instead of silently zombie-ing the daily insult.
        try {
            TriggerScheduler(ctx).scheduleBehindPaceCheck(settings.behindPaceCheckHour)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to reschedule BehindPaceWorker; retry queued", t)
            return Result.retry()
        }

        val db = AppDatabase.getDatabase(ctx)
        val repo = MessageRepository(db.messageDao())
        val notifier = InsultNotificationService(ctx)

        // HealthTracker (foreground service) writes both step count and a
        // last-updated timestamp. Reject stale or never-written values so we
        // don't fire EXISTENTIAL based on a phantom 0 on first launch /
        // post-process-death / post-midnight before the first datapoint.
        val healthPrefs = ctx.getSharedPreferences(HealthTracker.WATCH_HEALTH_PREFS, Context.MODE_PRIVATE)
        val lastUpdated = healthPrefs.getLong(HealthTracker.KEY_LAST_UPDATED, 0L)
        val ageMs = System.currentTimeMillis() - lastUpdated
        if (lastUpdated == 0L || ageMs > HealthTracker.STALE_THRESHOLD_MS) {
            Log.w(TAG, "Step data missing or stale (age=${ageMs}ms); skipping pace check")
            return Result.success()
        }
        val currentSteps = healthPrefs.getInt(HealthTracker.KEY_STEPS_TODAY, 0)
        val goal = settings.dailyStepGoal
        val now = Calendar.getInstance()

        val expected = PaceCalculator.expectedStepsByHour(
            now.get(Calendar.HOUR_OF_DAY),
            now.get(Calendar.MINUTE),
            goal,
            settings.activeHoursStart,
            settings.activeHoursEnd,
        )
        val level = PaceCalculator.escalationIfBehind(currentSteps, expected) ?: run {
            Log.d(TAG, "On pace ($currentSteps / $expected). Skipping.")
            return Result.success()
        }

        val tone = ToneResolver.resolve(
            settings.contextAwareEnabled,
            settings.activeHoursStart,
            settings.activeHoursEnd,
        )
        val message = repo.selectMessage(level, TriggerType.BEHIND_PACE, tone) ?: run {
            Log.w(TAG, "No BEHIND_PACE message for level=$level tone=$tone — falling back to inactivity pool")
            repo.selectMessage(level, TriggerType.INACTIVITY, tone)
        } ?: run {
            Log.w(TAG, "No fallback message either; skipping insult")
            return Result.success()
        }

        val stats = "$currentSteps / $expected steps. Behind pace."
        notifier.deliverInsult(message, stats)
        return Result.success()
    }

    companion object {
        private const val TAG = "BehindPaceWorker"
    }
}
