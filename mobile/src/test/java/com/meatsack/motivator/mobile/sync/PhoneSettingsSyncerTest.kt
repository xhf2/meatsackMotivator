package com.meatsack.motivator.mobile.sync

import com.google.android.gms.wearable.DataMap
import com.meatsack.shared.sync.SettingsKeys
import com.meatsack.shared.sync.SettingsSnapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneSettingsSyncerTest {

    private class FakeSink : SettingsSink {
        var lastDataMap: DataMap? = null
        var callCount = 0
        var failNextWith: Throwable? = null
        override suspend fun put(path: String, dataMap: DataMap) {
            callCount++
            failNextWith?.let {
                failNextWith = null
                throw it
            }
            assertEquals(SettingsKeys.PATH, path)
            lastDataMap = dataMap
        }
    }

    private class FakeSettingsSource(initial: SettingsSnapshot) : SettingsSource {
        override val snapshots = MutableStateFlow(initial)
        override suspend fun current(): SettingsSnapshot = snapshots.value
    }

    @Test
    fun syncNow_writesAllFieldsAtSettingsPath() = runTest {
        val custom = SettingsSnapshot.defaults.copy(
            dailyStepGoal = 12345,
            inactivityThreshold = 45,
            behindPaceCheckHour = 14,
        )
        val sink = FakeSink()
        val syncer = PhoneSettingsSyncer(FakeSettingsSource(custom), sink)

        val result = syncer.syncNow()

        val dm = sink.lastDataMap
        assertNotNull("expected sink to receive a DataMap", dm)
        val parsed = SettingsSnapshot.fromDataMap(dm!!)
        assertEquals(SettingsSyncResult.Success, result)
        assertEquals(custom, parsed)
        assertEquals(1, sink.callCount)
    }

    @Test
    fun syncNow_propagatesCancellation() = runTest {
        val sink = FakeSink().apply { failNextWith = kotlinx.coroutines.CancellationException("test") }
        val syncer = PhoneSettingsSyncer(FakeSettingsSource(SettingsSnapshot.defaults), sink)
        var threw = false
        try {
            syncer.syncNow()
        } catch (_: kotlinx.coroutines.CancellationException) {
            threw = true
        }
        assertTrue("syncNow should rethrow CancellationException", threw)
    }

    @Test
    fun syncNow_swallowsAndLogsOtherExceptions() = runTest {
        val sink = FakeSink().apply { failNextWith = RuntimeException("network down") }
        val syncer = PhoneSettingsSyncer(FakeSettingsSource(SettingsSnapshot.defaults), sink)
        val result = syncer.syncNow()
        assertTrue("expected Failed", result is SettingsSyncResult.Failed)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun start_debouncesRapidEmissionsToSingleWrite() = runTest {
        val source = FakeSettingsSource(SettingsSnapshot.defaults)
        val sink = FakeSink()
        val syncer = PhoneSettingsSyncer(source, sink)

        val job = launch { syncer.start(this) }

        // Three rapid edits within the 500ms debounce window — should collapse to one write.
        source.snapshots.value = SettingsSnapshot.defaults.copy(dailyStepGoal = 11000)
        advanceTimeBy(100)
        source.snapshots.value = SettingsSnapshot.defaults.copy(dailyStepGoal = 12000)
        advanceTimeBy(100)
        source.snapshots.value = SettingsSnapshot.defaults.copy(dailyStepGoal = 13000)
        advanceTimeBy(600) // > debounce window

        assertEquals("expected only the trailing emission to be written", 1, sink.callCount)
        val parsed = SettingsSnapshot.fromDataMap(sink.lastDataMap!!)
        assertEquals(13000, parsed.dailyStepGoal)

        job.cancel()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun start_distinctUntilChanged_suppressesIdenticalEmissions() = runTest {
        val source = FakeSettingsSource(SettingsSnapshot.defaults)
        val sink = FakeSink()
        val syncer = PhoneSettingsSyncer(source, sink)

        val job = launch { syncer.start(this) }

        source.snapshots.value = SettingsSnapshot.defaults.copy(dailyStepGoal = 11000)
        advanceTimeBy(600)
        source.snapshots.value = SettingsSnapshot.defaults.copy(dailyStepGoal = 11000) // identical
        advanceTimeBy(600)

        assertEquals(1, sink.callCount)
        job.cancel()
    }
}
