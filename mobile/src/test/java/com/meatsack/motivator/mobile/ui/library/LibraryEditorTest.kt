package com.meatsack.motivator.mobile.ui.library

import com.meatsack.motivator.mobile.sync.SyncResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryEditorTest {

    private class FakeStore : VoteStore {
        val ups = mutableListOf<Long>()
        val downs = mutableListOf<Long>()
        var failNextWith: Throwable? = null
        override suspend fun voteUp(messageId: Long) {
            failNextWith?.let {
                failNextWith = null
                throw it
            }
            ups += messageId
        }
        override suspend fun voteDown(messageId: Long) {
            failNextWith?.let {
                failNextWith = null
                throw it
            }
            downs += messageId
        }
    }

    private class CountingSync(
        private val delayMs: Long = 0,
        private val result: () -> SyncResult = { SyncResult.Success(1) },
    ) {
        var calls = 0
        val fn: suspend () -> SyncResult = {
            calls++
            if (delayMs > 0) delay(delayMs)
            result()
        }
    }

    private val debounce = 2_000L

    @Test
    fun voteUp_writesOnceWithGivenId() = runTest {
        val store = FakeStore()
        val sync = CountingSync()
        val editor = LibraryEditor(store, sync.fn, this, debounce) {}

        editor.voteUp(42L)
        advanceUntilIdle()

        assertEquals(listOf(42L), store.ups)
        assertTrue(store.downs.isEmpty())
    }

    @Test
    fun voteDown_writesOnceWithGivenId() = runTest {
        val store = FakeStore()
        val sync = CountingSync()
        val editor = LibraryEditor(store, sync.fn, this, debounce) {}

        editor.voteDown(7L)
        advanceUntilIdle()

        assertEquals(listOf(7L), store.downs)
        assertTrue(store.ups.isEmpty())
    }

    @Test
    fun votesInsideDebounceWindow_collapseToOneSync() = runTest {
        val store = FakeStore()
        val sync = CountingSync()
        val results = mutableListOf<SyncResult>()
        val editor = LibraryEditor(store, sync.fn, this, debounce) { results += it }

        editor.voteUp(1L)
        advanceTimeBy(500)
        editor.voteDown(2L)
        advanceTimeBy(500)
        editor.voteUp(3L)
        advanceTimeBy(1_000) // 1.0 s after the last vote: still inside the 2 s window
        assertEquals("sync must not fire before the window elapses", 0, sync.calls)

        advanceUntilIdle()

        assertEquals(1, sync.calls)
        assertEquals(listOf(SyncResult.Success(1)), results)
        assertEquals(listOf(1L, 3L), store.ups)
        assertEquals(listOf(2L), store.downs)
    }

    @Test
    fun voteAfterWindowElapsed_triggersSecondSync() = runTest {
        val store = FakeStore()
        val sync = CountingSync()
        val editor = LibraryEditor(store, sync.fn, this, debounce) {}

        editor.voteUp(1L)
        advanceUntilIdle()
        assertEquals(1, sync.calls)

        editor.voteUp(1L)
        advanceUntilIdle()
        assertEquals(2, sync.calls)
    }

    @Test
    fun syncFailure_isReportedNotThrown() = runTest {
        val store = FakeStore()
        val boom = IllegalStateException("watch unreachable")
        val sync = CountingSync { SyncResult.Failed(boom) }
        val results = mutableListOf<SyncResult>()
        val editor = LibraryEditor(store, sync.fn, this, debounce) { results += it }

        editor.voteUp(1L)
        advanceUntilIdle()

        assertEquals(1, results.size)
        val failed = results.single() as SyncResult.Failed
        assertEquals(boom, failed.error)
    }

    @Test
    fun syncThrowingNonCancellation_isWrappedAsFailed() = runTest {
        val store = FakeStore()
        val boom = RuntimeException("serializer blew up")
        val results = mutableListOf<SyncResult>()
        val editor = LibraryEditor(store, { throw boom }, this, debounce) { results += it }

        editor.voteUp(1L)
        advanceUntilIdle()

        val failed = results.single() as SyncResult.Failed
        assertEquals(boom, failed.error)
    }

    @Test
    fun syncThrowingCancellation_isNotSwallowedIntoFailed() = runTest {
        val store = FakeStore()
        val results = mutableListOf<SyncResult>()
        val editor = LibraryEditor(store, { throw CancellationException("cancelled") }, this, debounce) { results += it }

        editor.voteUp(1L)
        advanceUntilIdle()

        // A CancellationException cancels the launched job; it must never be reported as Failed.
        assertTrue(results.isEmpty())
    }

    @Test
    fun voteDuringInFlightSync_doesNotCancelThatSync_andSchedulesAnother() = runTest {
        val store = FakeStore()
        val sync = CountingSync(delayMs = 1_000)
        val results = mutableListOf<SyncResult>()
        val editor = LibraryEditor(store, sync.fn, this, debounce) { results += it }

        editor.voteUp(1L)
        // Past the 2s debounce window: the first sync has started and is now
        // suspended inside its own 1s delay, i.e. genuinely in flight.
        advanceTimeBy(2_500)
        runCurrent()

        editor.voteUp(2L) // arrives while the first sync is in flight
        runCurrent()

        advanceUntilIdle()

        assertEquals(
            "the in-flight sync must run to completion, not be cancelled by the second vote",
            2,
            sync.calls,
        )
        assertEquals(listOf(SyncResult.Success(1), SyncResult.Success(1)), results)
        assertEquals(listOf(1L, 2L), store.ups)
    }

    @Test
    fun storeWriteFailure_skipsSyncForThatVote() = runTest {
        val store = FakeStore().apply { failNextWith = IllegalStateException("disk full") }
        val sync = CountingSync()
        val editor = LibraryEditor(store, sync.fn, this, debounce) {}

        editor.voteUp(1L)
        advanceUntilIdle()

        assertEquals(0, sync.calls)
        assertTrue(store.ups.isEmpty())
    }
}
