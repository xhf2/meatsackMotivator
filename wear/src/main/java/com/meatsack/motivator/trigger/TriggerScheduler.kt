package com.meatsack.motivator.trigger

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Owns the WorkManager scheduling for the two time-driven insult
 * triggers added in v2: a once-daily pace-vs-goal check (default noon)
 * and a once-daily end-of-day reckoning. Both use OneTimeWorkRequest +
 * a self-reschedule from inside the worker so a single OS-kill cannot
 * break the daily chain.
 */
class TriggerScheduler(context: Context) {
    private val wm = WorkManager.getInstance(context)

    fun scheduleBehindPaceCheck(hour: Int) {
        val delayMs = millisUntilNextHour(hour)
        val request = OneTimeWorkRequestBuilder<BehindPaceWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .build()
        wm.enqueueUniqueWork(UNIQUE_PACE, ExistingWorkPolicy.REPLACE, request)
    }

    fun scheduleEndOfDay(hour: Int) {
        val delayMs = millisUntilNextHour(hour)
        val request = OneTimeWorkRequestBuilder<EndOfDayWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .build()
        wm.enqueueUniqueWork(UNIQUE_END_OF_DAY, ExistingWorkPolicy.REPLACE, request)
    }

    fun cancelAll() {
        wm.cancelUniqueWork(UNIQUE_PACE)
        wm.cancelUniqueWork(UNIQUE_END_OF_DAY)
    }

    internal fun millisUntilNextHour(hour: Int, now: Calendar = Calendar.getInstance()): Long {
        val target = (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (before(now)) add(Calendar.DAY_OF_MONTH, 1)
        }
        return target.timeInMillis - now.timeInMillis
    }

    companion object {
        const val UNIQUE_PACE = "meatsack_pace_check"
        const val UNIQUE_END_OF_DAY = "meatsack_end_of_day"
    }
}
