package com.meatsack.motivator.mobile.ui.library

import android.util.Log
import com.meatsack.motivator.mobile.sync.SyncResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Applies phone-side Library mutations — votes, archive/unarchive, delete — and
 * pushes the library to the watch after a debounce.
 *
 * Why auto-sync is mandatory: the watch → phone `/votes` channel sends
 * *absolute* counts, and the phone applies them verbatim. A phone vote left
 * unsynced would be overwritten by the next watch vote on the same message.
 * Pushing the phone's counts promptly (the `/messages` payload carries votes
 * and the watch applies them via `MessageDao.upsertPreservingShown`, which
 * overwrites every phone-owned field including votes) keeps both copies
 * converged.
 *
 * Debounce semantics: every successful store write (re)starts a single timer.
 * When it elapses, [sync] runs once. A mutation that arrives while a sync is
 * already *in flight* starts a fresh timer rather than cancelling that sync.
 * [delete] is the one exception: it *flushes* a pending timer (runs the sync
 * now) before deleting, because a third downvote or an archive still waiting
 * on the timer must reach the watch while the row is still in the payload —
 * the watch never deletes rows, so a retire that never synced would leave its
 * stale copy firing forever.
 *
 * Consequently two `sync()` calls can overlap if a fresh timer elapses while
 * an earlier sync is still in flight (needs a sync slower than [debounceMs];
 * `putDataItem` resolves on local commit, so this is rare). Both carry the
 * full table, so the later snapshot wins; no serialisation is attempted here.
 *
 * Callers must invoke the mutators ([voteUp], [voteDown], [archive], [unarchive], [delete])
 * from a single-threaded [scope] (e.g. `viewModelScope` on Main, or a test dispatcher);
 * [pendingSync] is not synchronised.
 */
class LibraryEditor(
    private val store: LibraryStore,
    private val sync: suspend () -> SyncResult,
    private val scope: CoroutineScope,
    private val debounceMs: Long = DEFAULT_DEBOUNCE_MS,
    private val onSyncResult: (SyncResult) -> Unit,
) {
    private var pendingSync: Job? = null

    fun voteUp(messageId: Long) = mutate("voteUp", "id=$messageId") { store.voteUp(messageId) }

    fun voteDown(messageId: Long) = mutate("voteDown", "id=$messageId") { store.voteDown(messageId) }

    /** Keep-pile: the row stops firing on the watch after the next sync and is never pruned. */
    fun archive(messageId: Long) = mutate("archive", "id=$messageId") { store.setActive(messageId, false) }

    fun unarchive(messageId: Long) = mutate("unarchive", "id=$messageId") { store.setActive(messageId, true) }

    /**
     * Phone-only hard delete; the watch keeps its copy (see README Known limitations).
     * Flushes any pending debounced sync first (see class doc), then goes through the
     * same write-then-debounced-sync path as every other mutation; the resulting push
     * simply omits the rows.
     */
    fun delete(messageIds: List<Long>) {
        if (messageIds.isEmpty()) return
        mutate("delete", "${messageIds.size} id(s)=$messageIds", flushPendingFirst = true) {
            store.deleteByIds(messageIds)
        }
    }

    private fun mutate(
        action: String,
        target: String,
        flushPendingFirst: Boolean = false,
        write: suspend () -> Unit,
    ) {
        scope.launch {
            if (flushPendingFirst) flushPendingSync()
            try {
                write()
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                // The tap simply doesn't take effect; nothing to sync for it.
                Log.e(TAG, "$action failed for $target", e)
                return@launch
            }
            scheduleSync()
        }
    }

    /** Runs a pending debounced sync right now instead of when its timer elapses. */
    private suspend fun flushPendingSync() {
        val pending = pendingSync ?: return
        pending.cancel(Superseded())
        pendingSync = null
        runSync()
    }

    private fun scheduleSync() {
        pendingSync?.cancel(Superseded())
        pendingSync = scope.launch {
            delay(debounceMs)
            // Clear before syncing so a mutation landing mid-sync schedules a new
            // timer instead of cancelling this in-flight push.
            pendingSync = null
            runSync()
        }.also { job ->
            job.invokeOnCompletion { cause ->
                // A timer cancelled by scope teardown (user backed out of the Library
                // within the debounce window) never syncs, and nothing else would log it.
                if (cause is CancellationException && cause !is Superseded) {
                    Log.w(TAG, "Pending auto-sync cancelled before it ran; watch may be stale until the next sync")
                }
            }
        }
    }

    private suspend fun runSync() {
        val result = try {
            sync()
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            Log.e(TAG, "Auto-sync after Library mutation threw", e)
            SyncResult.Failed(e)
        }
        onSyncResult(result)
    }

    /** Marks a timer cancellation as intentional (restarted or flushed), not scope teardown. */
    private class Superseded : CancellationException("superseded by a newer mutation")

    companion object {
        private const val TAG = "LibraryEditor"

        /** Long enough to batch a burst of taps across several cards into one push. */
        const val DEFAULT_DEBOUNCE_MS = 2_000L
    }
}
