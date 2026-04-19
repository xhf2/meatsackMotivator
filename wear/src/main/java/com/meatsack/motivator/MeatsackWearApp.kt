package com.meatsack.motivator

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class MeatsackWearApp : Application() {
    /**
     * Outlives any single Activity or Service so fire-and-forget work (DB writes
     * on vote tap, sync inserts) can't be cancelled by component destruction.
     * SupervisorJob so one failing child doesn't cancel siblings.
     */
    val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}
