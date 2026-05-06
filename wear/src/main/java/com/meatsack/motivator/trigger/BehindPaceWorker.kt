package com.meatsack.motivator.trigger

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
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
        val db = AppDatabase.getDatabase(ctx)
        val repo = MessageRepository(db.messageDao())
        val notifier = InsultNotificationService(ctx)

        // HealthTracker (foreground service) writes the latest count here;
        // this hand-off lets WorkManager-driven workers read it without
        // re-subscribing to Health Services on a separate channel.
        val currentSteps = ctx.getSharedPreferences("watch_health", Context.MODE_PRIVATE)
            .getInt("steps_today", 0)
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
            Log.w(TAG, "No message for level=$level tone=$tone — falling back to inactivity pool")
            repo.selectMessage(level, TriggerType.INACTIVITY, tone)
        } ?: return Result.success()

        val stats = "$currentSteps / $expected steps. Behind pace."
        notifier.deliverInsult(message, stats)
        return Result.success()
    }

    companion object {
        private const val TAG = "BehindPaceWorker"
    }
}
