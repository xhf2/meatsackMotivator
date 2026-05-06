package com.meatsack.motivator.trigger

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class BehindPaceWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = Result.success() // filled in Task 5
}
