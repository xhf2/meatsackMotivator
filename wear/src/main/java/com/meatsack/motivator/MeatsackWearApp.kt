package com.meatsack.motivator

import android.app.Application
import android.util.Log
import com.meatsack.shared.data.SeedData
import com.meatsack.shared.db.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MeatsackWearApp : Application() {
    /**
     * Outlives any single Activity or Service so fire-and-forget work (DB writes
     * on vote tap, sync inserts) can't be cancelled by component destruction.
     * SupervisorJob so one failing child doesn't cancel siblings.
     */
    val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        seedDatabaseIfEmpty()
    }

    /**
     * Mirrors MeatsackMobileApp's seeder so the watch has a usable message pool
     * out of the box, without requiring the user to tap "Sync to Watch" first.
     * Sync becomes useful for delta updates (AI-generated additions, curation)
     * rather than a prerequisite for any insults firing.
     */
    private fun seedDatabaseIfEmpty() {
        applicationScope.launch {
            try {
                val db = AppDatabase.getDatabase(this@MeatsackWearApp)
                if (db.messageDao().getMessageCount() == 0) {
                    db.messageDao().insertAll(SeedData.getPreWrittenMessages())
                    Log.d(TAG, "Seeded watch database with pre-written messages")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to seed watch database; insults will not fire", t)
            }
        }
    }

    companion object {
        private const val TAG = "MeatsackWearApp"
    }
}
