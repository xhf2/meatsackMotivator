package com.meatsack.motivator.sync

import android.util.Log
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService
import com.meatsack.motivator.settings.WatchSettingsCache
import com.meatsack.shared.sync.SettingsKeys
import com.meatsack.shared.sync.SettingsSnapshot

class WatchSettingsReceiver : WearableListenerService() {

    companion object {
        private const val TAG = "WatchSettingsReceiver"

        /**
         * Pure function. Apply the snapshot field-by-field to the sink.
         * Extracted so unit tests don't need a WearableListenerService instance.
         */
        fun applySnapshot(snap: SettingsSnapshot, sink: ApplySink) {
            sink.setDailyStepGoal(snap.dailyStepGoal)
            sink.setInactivityThreshold(snap.inactivityThreshold)
            sink.setActiveHoursStart(snap.activeHoursStart)
            sink.setActiveHoursEnd(snap.activeHoursEnd)
            sink.setContextAwareEnabled(snap.contextAwareEnabled)
            sink.setBehindPaceCheckHour(snap.behindPaceCheckHour)
            sink.setBehindPaceEnabled(snap.behindPaceEnabled)
            sink.setEndOfDayEnabled(snap.endOfDayEnabled)
            sink.setContextAwareStart(snap.contextAwareStart)
            sink.setContextAwareEnd(snap.contextAwareEnd)
        }
    }

    /** Minimal write surface that WatchSettingsCache satisfies via cacheAdapter(). */
    interface ApplySink {
        fun setDailyStepGoal(v: Int)
        fun setInactivityThreshold(v: Int)
        fun setActiveHoursStart(v: Int)
        fun setActiveHoursEnd(v: Int)
        fun setContextAwareEnabled(v: Boolean)
        fun setBehindPaceCheckHour(v: Int)
        fun setBehindPaceEnabled(v: Boolean)
        fun setEndOfDayEnabled(v: Boolean)
        fun setContextAwareStart(v: Int)
        fun setContextAwareEnd(v: Int)
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        dataEvents.forEach { event ->
            val path = event.dataItem.uri.path
            if (path != SettingsKeys.PATH) return@forEach
            val sourceNode = event.dataItem.uri.host
            try {
                val dm = DataMapItem.fromDataItem(event.dataItem).dataMap
                val snap = SettingsSnapshot.fromDataMap(dm)
                val cache = WatchSettingsCache(applicationContext)
                applySnapshot(snap, cacheAdapter(cache))
                Log.d(TAG, "Applied settings from node=$sourceNode: $snap")
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to apply settings from node=$sourceNode", t)
            }
        }
    }

    private fun cacheAdapter(cache: WatchSettingsCache): ApplySink = object : ApplySink {
        override fun setDailyStepGoal(v: Int) {
            cache.dailyStepGoal = v
        }
        override fun setInactivityThreshold(v: Int) {
            cache.inactivityThreshold = v
        }
        override fun setActiveHoursStart(v: Int) {
            cache.activeHoursStart = v
        }
        override fun setActiveHoursEnd(v: Int) {
            cache.activeHoursEnd = v
        }
        override fun setContextAwareEnabled(v: Boolean) {
            cache.contextAwareEnabled = v
        }
        override fun setBehindPaceCheckHour(v: Int) {
            cache.behindPaceCheckHour = v
        }
        override fun setBehindPaceEnabled(v: Boolean) {
            cache.behindPaceEnabled = v
        }
        override fun setEndOfDayEnabled(v: Boolean) {
            cache.endOfDayEnabled = v
        }
        override fun setContextAwareStart(v: Int) {
            cache.contextAwareStart = v
        }
        override fun setContextAwareEnd(v: Int) {
            cache.contextAwareEnd = v
        }
    }
}
