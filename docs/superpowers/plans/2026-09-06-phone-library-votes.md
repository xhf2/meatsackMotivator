# Phone Library Voting Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the user upvote/downvote any message from the phone Library screen, with the vote pushed to the watch automatically (debounced) so the two copies stay converged.

**Architecture:** A new `LibraryEditor` (mobile, `ui/library`) owns "vote → debounced sync". It writes through a narrow `VoteStore` interface (Room-backed in production, fake in tests) and calls the existing `PhoneSyncSender.syncMessagesToWatch()` through an injected lambda. `LibraryViewModel` delegates to it and exposes a `SharedFlow<SyncResult>` for auto-sync failures. `LibraryScreen` turns each card's read-only vote text into two tappable controls. `PhoneSyncSender` stops filtering out rows with three or more downvotes so the watch learns about phone-side rejections.

**Tech Stack:** Kotlin, Jetpack Compose (Material3), Room (via `shared`), kotlinx-coroutines (+ `kotlinx-coroutines-test` with `StandardTestDispatcher`/`advanceTimeBy`), Wear Data Layer (`PhoneSyncSender`, unchanged API), JUnit4, ktlint via Spotless.

**Spec:** `docs/superpowers/specs/2026-09-06-phone-library-votes-design.md`

## Global Constraints

- Branch: `feature/phone-library-votes` (already exists, spec committed on it). Never commit to `main`.
- Pre-commit hook runs `./gradlew spotlessCheck` + unit tests. Run `./gradlew spotlessApply` before committing if ktlint complains; `JAVA_HOME` must point at Android Studio's JBR (`/c/Program Files/Android/Android Studio/jbr`).
- Debounce default: **2 000 ms** (`LibraryEditor.DEFAULT_DEBOUNCE_MS`).
- Votes are unbounded (no cap, no confirmation dialog), matching the watch.
- Phone votes use the existing `MessageDao.voteUp` / `voteDown` increment queries — do not add new DAO queries.
- `CancellationException` is always rethrown, never absorbed (project-wide rule).
- Accessibility labels: `"Vote up"` and `"Vote down"` (exact copy) on the tappable controls.
- Auto-sync feedback: only `SyncResult.Failed` is surfaced (snackbar text `"Sync failed: <message or 'unknown error'>"`); `Success`/`NoMessages` are silent. The manual Sync bar keeps its existing three messages.
- Both themes (Vitals `InsultPanel`, Bubblegum `BubblegumPanel`) get the controls. Bubblegum keeps its 💕/💔 glyphs; Vitals keeps ▲/▼.
- No delete, no import/export, no watch-side changes.

---

## File map

| File | Action | Responsibility |
|------|--------|----------------|
| `mobile/src/main/java/com/meatsack/motivator/mobile/ui/library/VoteStore.kt` | Create | `VoteStore` interface + `RoomVoteStore` |
| `mobile/src/main/java/com/meatsack/motivator/mobile/ui/library/LibraryEditor.kt` | Create | vote → debounced sync orchestration |
| `mobile/src/test/java/com/meatsack/motivator/mobile/ui/library/LibraryEditorTest.kt` | Create | JVM tests for the editor |
| `mobile/src/main/java/com/meatsack/motivator/mobile/ui/library/LibraryViewModel.kt` | Modify | wire editor, expose `voteUp`/`voteDown`/`autoSyncResults` |
| `mobile/src/main/java/com/meatsack/motivator/mobile/ui/library/LibraryScreen.kt` | Modify | tappable vote controls in both panels; collect auto-sync failures |
| `mobile/src/main/java/com/meatsack/motivator/mobile/sync/PhoneSyncSender.kt` | Modify | drop `votesDown < 3` from the sync filter |

---

### Task 1: `VoteStore` + `LibraryEditor` with debounced auto-sync (TDD)

**Files:**
- Create: `mobile/src/main/java/com/meatsack/motivator/mobile/ui/library/VoteStore.kt`
- Create: `mobile/src/main/java/com/meatsack/motivator/mobile/ui/library/LibraryEditor.kt`
- Test: `mobile/src/test/java/com/meatsack/motivator/mobile/ui/library/LibraryEditorTest.kt`

**Interfaces:**
- Consumes: `com.meatsack.shared.db.MessageDao.voteUp(messageId: Long)` / `voteDown(messageId: Long)` (existing `suspend` DAO queries); `com.meatsack.motivator.mobile.sync.SyncResult` (existing sealed class: `Success(count)`, `NoMessages`, `Failed(error)`).
- Produces:
  - `interface VoteStore { suspend fun voteUp(messageId: Long); suspend fun voteDown(messageId: Long) }`
  - `class RoomVoteStore(dao: MessageDao) : VoteStore`
  - `class LibraryEditor(store: VoteStore, sync: suspend () -> SyncResult, scope: CoroutineScope, debounceMs: Long = 2_000, onSyncResult: (SyncResult) -> Unit)` with `fun voteUp(messageId: Long)` and `fun voteDown(messageId: Long)` (non-suspending; fire-and-forget on `scope`).
  - `LibraryEditor.DEFAULT_DEBOUNCE_MS = 2_000L`

- [ ] **Step 1: Write the failing tests**

Create `mobile/src/test/java/com/meatsack/motivator/mobile/ui/library/LibraryEditorTest.kt`:

```kotlin
package com.meatsack.motivator.mobile.ui.library

import com.meatsack.motivator.mobile.sync.SyncResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryEditorTest {

    private class FakeStore : VoteStore {
        val ups = mutableListOf<Long>()
        val downs = mutableListOf<Long>()
        var failNextWith: Throwable? = null
        override suspend fun voteUp(messageId: Long) {
            failNextWith?.let { failNextWith = null; throw it }
            ups += messageId
        }
        override suspend fun voteDown(messageId: Long) {
            failNextWith?.let { failNextWith = null; throw it }
            downs += messageId
        }
    }

    private class CountingSync(private val result: () -> SyncResult = { SyncResult.Success(1) }) {
        var calls = 0
        val fn: suspend () -> SyncResult = {
            calls++
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
```

- [ ] **Step 2: Run the tests to verify they fail to compile**

Run:
```bash
./gradlew :mobile:testDebugUnitTest --tests "com.meatsack.motivator.mobile.ui.library.LibraryEditorTest"
```
Expected: BUILD FAILED with `Unresolved reference: VoteStore` / `LibraryEditor`.

- [ ] **Step 3: Create `VoteStore.kt`**

`mobile/src/main/java/com/meatsack/motivator/mobile/ui/library/VoteStore.kt`:

```kotlin
package com.meatsack.motivator.mobile.ui.library

import com.meatsack.shared.db.MessageDao

/**
 * Narrow persistence surface for phone-side voting, so [LibraryEditor] can be
 * unit-tested with a fake instead of a Room database. Mirrors the
 * `GenerationStore` seam used by the AI generator.
 *
 * Both methods increment the same columns the watch's InsultActivity votes
 * bump, so a phone vote and a watch vote are the same operation.
 */
interface VoteStore {
    suspend fun voteUp(messageId: Long)
    suspend fun voteDown(messageId: Long)
}

/** Production implementation backed by the Room [MessageDao]. */
class RoomVoteStore(private val dao: MessageDao) : VoteStore {
    override suspend fun voteUp(messageId: Long) = dao.voteUp(messageId)
    override suspend fun voteDown(messageId: Long) = dao.voteDown(messageId)
}
```

- [ ] **Step 4: Create `LibraryEditor.kt`**

`mobile/src/main/java/com/meatsack/motivator/mobile/ui/library/LibraryEditor.kt`:

```kotlin
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
```

- [ ] **Step 5: Run the tests to verify they pass**

Run:
```bash
./gradlew :mobile:testDebugUnitTest --tests "com.meatsack.motivator.mobile.ui.library.LibraryEditorTest"
```
Expected: BUILD SUCCESSFUL, 8 tests passed. (`android.util.Log` is stubbed because `unitTests.isReturnDefaultValues = true` in `mobile/build.gradle.kts`.)

- [ ] **Step 6: Spotless + commit**

```bash
./gradlew spotlessApply
git add mobile/src/main/java/com/meatsack/motivator/mobile/ui/library/VoteStore.kt \
        mobile/src/main/java/com/meatsack/motivator/mobile/ui/library/LibraryEditor.kt \
        mobile/src/test/java/com/meatsack/motivator/mobile/ui/library/LibraryEditorTest.kt
git commit -m "feat(mobile): LibraryEditor applies phone votes and debounces auto-sync to watch"
```

---

### Task 2: `PhoneSyncSender` sends rejected rows so the watch learns phone-side downvotes

**Files:**
- Modify: `mobile/src/main/java/com/meatsack/motivator/mobile/sync/PhoneSyncSender.kt:44-47` (the `.filter { ... }` inside `syncMessagesToWatch`)

**Interfaces:**
- Consumes: nothing new.
- Produces: no API change. Behavioural change only: the `/messages` payload now includes active rows with `votesDown >= 3`.

Why: the watch's `MessageDao.getEligibleMessages` already requires `votesDown < 3`, so sending rejected rows never makes them fire. But if the phone *omits* them, a phone downvote that reaches 3 never reaches the watch, whose copy stays at 2 and keeps firing. Capacity is fine: `getAllMessages()` orders by net score DESC, so rejected rows sort last and are the first dropped by `take(CACHE_SIZE)`; the watch accepts up to 500.

There is no JVM test for `PhoneSyncSender` (it touches Room + the Data Layer). Coverage is the on-device check in Task 5.

- [ ] **Step 1: Change the filter**

In `PhoneSyncSender.syncMessagesToWatch()`, replace:

```kotlin
        val messages = db.messageDao().getAllMessages()
            .filter { it.isActive && it.votesDown < 3 }
            .take(CACHE_SIZE)
```

with:

```kotlin
        // Send every active row, *including* ones with votesDown >= 3. The watch's
        // getEligibleMessages() already hides rejected rows, so sending them can't
        // make them fire — but omitting them means a phone-side downvote that
        // reaches 3 never reaches the watch, whose stale copy would keep firing.
        // Rejected rows sort last (getAllMessages orders by net score DESC), so
        // they are the first to fall off CACHE_SIZE.
        val messages = db.messageDao().getAllMessages()
            .filter { it.isActive }
            .take(CACHE_SIZE)
```

- [ ] **Step 2: Compile**

Run:
```bash
./gradlew :mobile:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add mobile/src/main/java/com/meatsack/motivator/mobile/sync/PhoneSyncSender.kt
git commit -m "fix(mobile): sync rejected rows to watch so phone downvotes hide them there"
```

---

### Task 3: Wire `LibraryEditor` into `LibraryViewModel`

**Files:**
- Modify: `mobile/src/main/java/com/meatsack/motivator/mobile/ui/library/LibraryViewModel.kt` (whole file, currently 17 lines)

**Interfaces:**
- Consumes: `LibraryEditor`, `RoomVoteStore` (Task 1); `PhoneSyncSender(context).syncMessagesToWatch(): SyncResult` (existing).
- Produces (used by Task 4):
  - `fun voteUp(messageId: Long)`
  - `fun voteDown(messageId: Long)`
  - `val autoSyncResults: SharedFlow<SyncResult>` — emits one value per completed auto-sync.

`AndroidViewModel` holds a Room DAO, so this class has no JVM test; its only logic is delegation. Everything testable lives in `LibraryEditor` (Task 1).

- [ ] **Step 1: Replace the ViewModel body**

Replace the full contents of `LibraryViewModel.kt` with:

```kotlin
package com.meatsack.motivator.mobile.ui.library

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.meatsack.motivator.mobile.sync.PhoneSyncSender
import com.meatsack.motivator.mobile.sync.SyncResult
import com.meatsack.shared.db.AppDatabase
import com.meatsack.shared.model.Message
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class LibraryViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = AppDatabase.getDatabase(application).messageDao()

    val messages: StateFlow<List<Message>> = dao.getAllMessagesFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * One emission per completed debounced auto-sync after a phone vote. The screen
     * surfaces only [SyncResult.Failed]; successes are silent.
     *
     * extraBufferCapacity = 1 so tryEmit never drops a result if the collector is
     * momentarily suspended; a second result overwrites the first, which is fine —
     * the latest outcome is the one worth showing.
     */
    private val _autoSyncResults = MutableSharedFlow<SyncResult>(extraBufferCapacity = 1)
    val autoSyncResults: SharedFlow<SyncResult> = _autoSyncResults

    private val editor = LibraryEditor(
        store = RoomVoteStore(dao),
        sync = { PhoneSyncSender(application).syncMessagesToWatch() },
        scope = viewModelScope,
        onSyncResult = { _autoSyncResults.tryEmit(it) },
    )

    fun voteUp(messageId: Long) = editor.voteUp(messageId)

    fun voteDown(messageId: Long) = editor.voteDown(messageId)
}
```

- [ ] **Step 2: Compile**

Run:
```bash
./gradlew :mobile:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
./gradlew spotlessApply
git add mobile/src/main/java/com/meatsack/motivator/mobile/ui/library/LibraryViewModel.kt
git commit -m "feat(mobile): LibraryViewModel exposes voteUp/voteDown and auto-sync results"
```

---

### Task 4: Tappable vote controls in `LibraryScreen` (both themes) + auto-sync failure snackbar

**Files:**
- Modify: `mobile/src/main/java/com/meatsack/motivator/mobile/ui/library/LibraryScreen.kt`
  - `LibraryScreen` composable (lines ~64-111): collect `autoSyncResults`, pass vote callbacks to panels
  - `InsultPanel` (lines ~244-289): replace vote text with `VoteControls`
  - `BubblegumPanel` (lines ~386-414): replace vote text with `VoteControls`
  - Add new private `VoteControls` composable

**Interfaces:**
- Consumes: `LibraryViewModel.voteUp(Long)`, `voteDown(Long)`, `autoSyncResults: SharedFlow<SyncResult>` (Task 3).
- Produces: UI only.

Design notes (follow the existing file's conventions, not Material `IconButton`):
- The file already uses `Modifier.clickable(onClick, role = Role.Button, onClickLabel = ...)` for the Sync bars; reuse that pattern so no new icon dependencies are needed and the ▲/▼ and 💕/💔 glyphs stay.
- `onClickLabel` doubles as the TalkBack action label; the exact strings are `"Vote up"` / `"Vote down"`.
- Padding of 8dp around each glyph+count gives a comfortable tap target without changing the card's visual density much.

- [ ] **Step 1: Add the `VoteControls` composable**

Add these imports to the import block of `LibraryScreen.kt` (keep the block alphabetised; Spotless will complain otherwise):

```kotlin
import androidx.compose.runtime.LaunchedEffect
```

Add this composable just above the `// ============================ Vitals theme ============================` comment:

```kotlin
/**
 * Two tappable vote controls. Glyph strings are theme-supplied (▲/▼ for Vitals,
 * 💕/💔 for Bubblegum); labels are fixed so TalkBack reads "Vote up" / "Vote down"
 * in both themes.
 */
@Composable
private fun VoteControls(
    upGlyph: String,
    downGlyph: String,
    votesUp: Int,
    votesDown: Int,
    color: Color,
    onVoteUp: () -> Unit,
    onVoteDown: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "$upGlyph $votesUp",
            style = MaterialTheme.typography.bodySmall,
            color = color,
            modifier = Modifier
                .clickable(onClick = onVoteUp, role = Role.Button, onClickLabel = "Vote up")
                .padding(8.dp),
        )
        Text(
            text = "$downGlyph $votesDown",
            style = MaterialTheme.typography.bodySmall,
            color = color,
            modifier = Modifier
                .clickable(onClick = onVoteDown, role = Role.Button, onClickLabel = "Vote down")
                .padding(8.dp),
        )
    }
}
```

- [ ] **Step 2: Update `InsultPanel` (Vitals)**

Change the signature and replace the trailing vote `Text` in the `Row`:

```kotlin
@Composable
private fun InsultPanel(
    message: Message,
    onVoteUp: () -> Unit,
    onVoteDown: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline,
                shape = RoundedCornerShape(8.dp),
            ),
    ) {
        Column(modifier = Modifier.padding(13.dp)) {
            Text(
                text = message.text,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(11.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "LVL ${message.level.value}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.size(10.dp))
                SeverityBar(level = message.level.value)
                Spacer(Modifier.size(12.dp))
                Text(
                    text = message.triggerType.name,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                VoteControls(
                    upGlyph = "▲",
                    downGlyph = "▼",
                    votesUp = message.votesUp,
                    votesDown = message.votesDown,
                    color = MaterialTheme.colorScheme.secondary,
                    onVoteUp = onVoteUp,
                    onVoteDown = onVoteDown,
                )
            }
        }
    }
}
```

(The only changes from the current body: the two new parameters, and the final `Text(text = "▲${message.votesUp} ▼${message.votesDown}", …)` replaced by the `VoteControls(...)` call.)

- [ ] **Step 3: Update `BubblegumPanel`**

Change the signature and replace the trailing vote `Text`:

```kotlin
@Composable
private fun BubblegumPanel(
    message: Message,
    onVoteUp: () -> Unit,
    onVoteDown: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(22.dp),
        shadowElevation = 3.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 15.dp)) {
            Text(
                text = message.text,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                HeartSeverity(level = message.level.value)
                Spacer(Modifier.size(10.dp))
                TriggerChip(message.triggerType.name)
                Spacer(Modifier.weight(1f))
                VoteControls(
                    upGlyph = "💕",
                    downGlyph = "💔",
                    votesUp = message.votesUp,
                    votesDown = message.votesDown,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    onVoteUp = onVoteUp,
                    onVoteDown = onVoteDown,
                )
            }
        }
    }
}
```

(Changes from current: two new parameters; the final `Text(text = "💕 ${message.votesUp} · 💔 ${message.votesDown}", …)` replaced by `VoteControls(...)`.)

- [ ] **Step 4: Wire the screen**

In `LibraryScreen`, add the auto-sync collector right after `val onSync: () -> Unit = { … }` block (before `Box(...)`):

```kotlin
    LaunchedEffect(viewModel) {
        viewModel.autoSyncResults.collect { result ->
            if (result is SyncResult.Failed) {
                snackbarHostState.showSnackbar(
                    "Sync failed: ${result.error.message ?: "unknown error"}",
                )
            }
        }
    }
```

and replace the `items(messages) { … }` body:

```kotlin
                items(messages, key = { it.id }) { message ->
                    if (bubblegum) {
                        BubblegumPanel(
                            message = message,
                            onVoteUp = { viewModel.voteUp(message.id) },
                            onVoteDown = { viewModel.voteDown(message.id) },
                        )
                    } else {
                        InsultPanel(
                            message = message,
                            onVoteUp = { viewModel.voteUp(message.id) },
                            onVoteDown = { viewModel.voteDown(message.id) },
                        )
                    }
                }
```

Note `key = { it.id }`: the list is ordered by net score, so a vote can reorder cards. A stable key keeps Compose from re-creating every card on reorder and makes the row the user just tapped animate to its new slot rather than flicker.

- [ ] **Step 5: Compile and run the full mobile unit suite**

Run:
```bash
./gradlew :mobile:compileDebugKotlin :mobile:testDebugUnitTest
```
Expected: BUILD SUCCESSFUL; all existing tests plus the 8 from Task 1 pass.

- [ ] **Step 6: Spotless + commit**

```bash
./gradlew spotlessApply
git add mobile/src/main/java/com/meatsack/motivator/mobile/ui/library/LibraryScreen.kt
git commit -m "feat(mobile): tappable up/down vote controls on Library cards in both themes"
```

---

### Task 5: On-device verification on the paired emulators

**Files:** none (verification only).

**Interfaces:** none.

Prerequisites (from `CLAUDE.md`): phone emulator (`emulator-5556`) and Wear emulator (`emulator-5554`) running and paired; `adb -s emulator-5554 shell dumpsys activity service com.google.android.gms/.wearable.service.WearableService | grep "connected out of"` reports `1 connected out of 1`.

- [ ] **Step 1: Build and install both APKs, targeting each device explicitly**

```bash
./gradlew :mobile:assembleDebug :wear:assembleDebug
adb -s emulator-5556 install -r mobile/build/outputs/apk/debug/mobile-debug.apk
adb -s emulator-5554 install -r wear/build/outputs/apk/debug/wear-debug.apk
```

- [ ] **Step 2: Start watching the watch's sync log**

In a second terminal:
```bash
adb -s emulator-5554 logcat -s WatchSyncReceiver:D
```

- [ ] **Step 3: Vote on the phone**

Open the phone app → Library. Pick the first card and note its text. Tap ▼ three times quickly (within 2 s). Expected on the phone: the card's down count goes 0 → 1 → 2 → 3 live, and the card moves toward the bottom of the list. Expected in the watch log, roughly 2 s after the last tap, **one** line like:

```
D/WatchSyncReceiver: Stored N messages from node=…
```

Not three lines: that would mean the debounce isn't collapsing.

- [ ] **Step 4: Confirm the watch's copy carries the phone's count**

The Room database is named `meatsack_database` (see `AppDatabase.getDatabase`). Room runs in WAL mode, so checkpoint before pulling or the newest votes may still be in the `-wal` file:

```bash
adb -s emulator-5554 shell run-as com.meatsack.motivator sqlite3 databases/meatsack_database "PRAGMA wal_checkpoint(FULL);" 2>/dev/null
adb -s emulator-5554 exec-out run-as com.meatsack.motivator cat databases/meatsack_database > watch.db
sqlite3 watch.db "SELECT id, votesUp, votesDown, text FROM messages ORDER BY votesDown DESC LIMIT 3;"
```

(If the emulator image lacks `sqlite3`, skip the checkpoint line and also pull `databases/meatsack_database-wal` alongside; local `sqlite3` will replay it.) Expected: the message you downvoted shows `votesDown = 3` on the **watch**. This proves the Task 2 filter change: before it, a `votesDown = 3` row was never sent.

- [ ] **Step 5: Confirm the failure snackbar path**

Break the pairing: `adb -s emulator-5556 forward --remove tcp:5601`. Tap ▲ on any card. Expected: after ~2 s the phone shows a `Sync failed: …` snackbar (the Data Layer put may instead succeed locally and queue; if no snackbar appears, that is the documented "watch not connected" behaviour and is also acceptable). Restore with `adb -s emulator-5556 forward tcp:5601 tcp:5601`.

- [ ] **Step 6: Switch to Bubblegum and repeat one vote**

Settings → theme → Bubblegum → Library. Tap 💕 on a card. Expected: count increments, one `Stored …` line on the watch.

- [ ] **Step 7: Record results**

Nothing to commit. Note the outcome of Steps 3–6 in the PR description (Task 6).

---

### Task 6: Update docs and open the PR

**Files:**
- Modify: `CLAUDE.md` — the "Known v1 Limitations" bullet that says sync is one-way and votes don't propagate back is stale (vote back-sync shipped in v2.0.0); replace it with an accurate note that covers this feature.
- Modify: `README.md` — the "Two-way vote sync" feature bullet (line ~29) and the Library screen description (line ~75) both describe voting as watch-only; extend them.

**Interfaces:** none.

- [ ] **Step 1: Fix the stale CLAUDE.md limitation**

In `CLAUDE.md`, under `## Known v1 Limitations`, replace:

```
- Sync is one-way (phone → watch). Votes recorded on the watch don't propagate back. A back-sync path will need a new DataItem path and a phone-side `WearableListenerService`.
```

with:

```
- Message sync is phone → watch (`/messages`, insert-or-replace) and vote sync is watch → phone (`/votes`, absolute counts). Phone-side votes (Library ▲/▼) are pushed to the watch by a debounced auto-sync so the watch's next absolute snapshot can't overwrite them. The watch never *deletes* rows: messages pruned on the phone stay on the watch until independently downvoted there.
```

- [ ] **Step 2: README**

Directly after the `- **Two-way vote sync.** …` bullet (around line 29), add a sibling bullet:

```
- **Vote from the phone.** ▲/▼ on every Library card, so you can rate the whole library in one sitting instead of waiting for each message to fire on your wrist. Phone votes push to the watch automatically (debounced) and feed the AI generator's loved/avoid examples.
```

In the Library screen description (around line 75), replace the sentence `Vote tallies stay live: thumbs you tap on the watch sync back here automatically.` with `Vote tallies stay live and tappable: thumbs you tap on the watch sync back here automatically, and ▲/▼ taps here push to the watch automatically.`

- [ ] **Step 3: Commit docs**

```bash
git add CLAUDE.md README.md
git commit -m "docs: describe phone-side voting and two-way sync semantics"
```

- [ ] **Step 4: Push and open the PR**

```bash
git push -u origin feature/phone-library-votes
gh pr create --base main --head feature/phone-library-votes \
  --title "feat(mobile): upvote/downvote from the phone Library with debounced auto-sync" \
  --body "$(cat <<'EOF'
## Summary
- ▲/▼ controls on every Library card (Vitals and Bubblegum themes); a tap runs the same DAO increment the watch uses.
- New `LibraryEditor` debounces (2 s) an automatic Sync-to-Watch after votes, so the watch's absolute `/votes` snapshot can't overwrite phone votes. JVM-tested with a fake store.
- `PhoneSyncSender` now sends active rows with `votesDown >= 3` so a phone downvote to 3 hides the message on the watch (the watch's own query already filters them).
- Docs: corrected the stale "one-way sync" limitation.

Spec: `docs/superpowers/specs/2026-09-06-phone-library-votes-design.md`
Plan: `docs/superpowers/plans/2026-09-06-phone-library-votes.md`

## Verification
- `./gradlew :mobile:testDebugUnitTest` — LibraryEditorTest (8) + existing suites pass.
- Paired emulators: 3 rapid ▼ taps → one `WatchSyncReceiver: Stored …` line; watch DB row shows `votesDown = 3`. (Fill in actual observations.)

## Out of scope
Delete-from-phone, watch-side reconciliation of pruned rows, import/export.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

(`gh` must be authenticated; if `gh auth status` fails, open the PR from the GitHub web UI with the same body.)

- [ ] **Step 5: Review the PR**

Per the project's standing rule, run the PR review toolkit on the new PR before declaring done:

```
/pr-review-toolkit:review-pr <PR number>
```

Address any findings on the branch, push, and re-run.
