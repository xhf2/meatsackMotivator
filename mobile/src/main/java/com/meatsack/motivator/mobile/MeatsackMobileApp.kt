package com.meatsack.motivator.mobile

import android.app.Application
import com.meatsack.motivator.mobile.data.SeedData
import com.meatsack.shared.db.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MeatsackMobileApp : Application() {
    override fun onCreate() {
        super.onCreate()
        seedDatabaseIfEmpty()
    }

    private fun seedDatabaseIfEmpty() {
        CoroutineScope(Dispatchers.IO).launch {
            val db = AppDatabase.getDatabase(this@MeatsackMobileApp)
            if (db.messageDao().getMessageCount() == 0) {
                db.messageDao().insertAll(SeedData.getPreWrittenMessages())
            }
        }
    }
}
