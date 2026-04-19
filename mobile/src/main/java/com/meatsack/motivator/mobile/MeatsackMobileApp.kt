package com.meatsack.motivator.mobile

import android.app.Application
import android.util.Log
import com.meatsack.motivator.mobile.data.SeedData
import com.meatsack.shared.db.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MeatsackMobileApp : Application() {

    /**
     * Outlives any Activity so background work (DB seeding, future sync jobs)
     * isn't cancelled by component destruction.
     */
    val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        seedDatabaseIfEmpty()
    }

    private fun seedDatabaseIfEmpty() {
        applicationScope.launch {
            try {
                val db = AppDatabase.getDatabase(this@MeatsackMobileApp)
                if (db.messageDao().getMessageCount() == 0) {
                    db.messageDao().insertAll(SeedData.getPreWrittenMessages())
                    Log.d(TAG, "Seeded database with pre-written messages")
                }
            } catch (t: Throwable) {
                // If this fails the Library tab will show empty; surfacing a user-visible
                // banner is a v2 concern, but logging loudly keeps the failure discoverable.
                Log.e(TAG, "Failed to seed database; Library may appear empty", t)
            }
        }
    }

    companion object {
        private const val TAG = "MeatsackMobileApp"
    }
}
