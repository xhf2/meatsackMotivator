package com.meatsack.motivator.mobile.ui.library

import android.util.Log
import com.meatsack.motivator.mobile.sync.SyncResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Applies phone-side votes and pushes them to the watch after a debounce.
 *
 * Why auto-sync is mandatory: the watch → phone `/votes` channel sends
 * *absolute* counts, and the phone applies them verbatim. A phone vote left
 * unsynced would be overwritten by the next watch vote on the same message.
 * Pushing the phone's counts promptly (the `/messages` payload carries votes
 * and the watch inserts with REPLACE) keeps both copies converged.
 *
 * Debounce semantics: every successful store write (re)starts a single timer.
 * When it elapses, [sync] runs once. A vote that arrives while a sync is
 * already *in flight* starts a fresh timer rather than cancelling that sync.
 *
 * Callers must invoke [voteUp]/[voteDown] from a single-threaded [scope]
 * (e.g. `viewModelScope` on Main, or a test dispatcher); [pendingSync] is not
 * synchronised.
 */
class LibraryEditor(
    private val store: VoteStore,
    private val sync: suspend () -> SyncResult,
    private val scope: CoroutineScope,
    private val debounceMs: Long = DEFAULT_DEBOUNCE_MS,
    private val onSyncResult: (SyncResult) -> Unit,
) {
    private var pendingSync: Job? = null

    fun voteUp(messageId: Long) = vote("up", messageId) { store.voteUp(messageId) }

    fun voteDown(messageId: Long) = vote("down", messageId) { store.voteDown(messageId) }

    private fun vote(direction: String, messageId: Long, write: suspend () -> Unit) {
        scope.launch {
            try {
                write()
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                // The tap simply doesn't take effect; nothing to sync for it.
                Log.e(TAG, "Vote $direction failed for id=$messageId", t)
                return@launch
            }
            scheduleSync()
        }
    }

    private fun scheduleSync() {
        pendingSync?.cancel()
        pendingSync = scope.launch {
            delay(debounceMs)
            // Clear before syncing so a vote landing mid-sync schedules a new
            // timer instead of cancelling this in-flight push.
            pendingSync = null
            val result = try {
                sync()
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Log.e(TAG, "Auto-sync after vote threw", t)
                SyncResult.Failed(t)
            }
            onSyncResult(result)
        }
    }

    companion object {
        private const val TAG = "LibraryEditor"

        /** Long enough to batch a burst of taps across several cards into one push. */
        const val DEFAULT_DEBOUNCE_MS = 2_000L
    }
}
