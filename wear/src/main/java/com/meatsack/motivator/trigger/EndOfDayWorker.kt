package com.meatsack.motivator.trigger

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
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
        val db = AppDatabase.getDatabase(ctx)
        val repo = MessageRepository(db.messageDao())
        val notifier = InsultNotificationService(ctx)

        val currentSteps = ctx.getSharedPreferences("watch_health", Context.MODE_PRIVATE)
            .getInt("steps_today", 0)
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
            ?: return Result.success()

        val stats = "$currentSteps / $goal. Day's over."
        notifier.deliverInsult(message, stats)

        // Reschedule for tomorrow
        TriggerScheduler(ctx).scheduleEndOfDay(settings.endOfDayHour)
        return Result.success()
    }
}
