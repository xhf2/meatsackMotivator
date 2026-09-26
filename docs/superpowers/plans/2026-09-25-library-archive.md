# Library Archive and Filters Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the user archive insults (kept, never fired, never pruned), filter the phone Library into Active / Archived / Retired, and delete retired insults without pressing Generate.

**Architecture:** No schema change — "archived" is the existing `Message.isActive = false`, which the watch's eligibility query already excludes. A pure `LibraryFilters` helper derives the three states; `LibraryEditor` gains archive/unarchive/delete on the same write-then-debounced-sync path as votes; `PhoneSyncSender` stops dropping inactive rows so the watch learns about archives; `LibraryPruner` ignores inactive rows. The Compose screen gets filter chips and per-chip card actions in both themes.

**Tech Stack:** Kotlin, Room (KSP), Jetpack Compose + Material3, kotlinx-coroutines (+ `kotlinx-coroutines-test`), JUnit4, Spotless/ktlint. Gradle wrapper.

**Spec:** `docs/superpowers/specs/2026-09-25-library-archive-design.md`

## Global Constraints

- Branch: `feature/library-archive` (already exists, spec committed on it). Never commit to `main`.
- Every Gradle command needs Android Studio's JDK: prefix with `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"` (Git Bash) or set `$env:JAVA_HOME` (PowerShell).
- The pre-commit hook runs `spotlessCheck` + all unit tests. Run `./gradlew spotlessApply` before each commit so ktlint formatting never fails the hook.
- No Room migration, no new columns, no wire-format change. `AppDatabase` version stays 1.
- Message text/cap rules are untouched (`MessageLimits.MAX_MESSAGE_TEXT_LENGTH = 100`).
- Existing behaviour that must not change: votes, `FrozenOrder`, the 2 s debounce (`LibraryEditor.DEFAULT_DEBOUNCE_MS`), the 200-row sync cap (`PhoneSyncSender.CACHE_SIZE`), the pruner's cap/floor numbers (`GenerationLimits.BUCKET_CAP = 50`, `BUCKET_FLOOR = 5`).
- Instrumented tests (`connectedAndroidTest`) need an emulator; CI does not run them. Compile them (`:shared:compileDebugAndroidTestKotlin`) in every task that touches them and run them when a device is available.
- Commit messages end with `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`.

---

## File Structure

| File | Responsibility |
|---|---|
| `mobile/.../ui/library/LibraryFilters.kt` (new) | Pure: `LibraryFilter` enum, `stateOf`, `apply`, `counts`. Single source of truth for the three states. |
| `mobile/src/test/.../ui/library/LibraryFiltersTest.kt` (new) | JVM tests for the above. |
| `shared/.../retention/LibraryPruner.kt` (modify) | Exclude inactive rows from pruning input. |
| `shared/src/test/.../retention/LibraryPrunerTest.kt` (modify) | Three new cases. |
| `shared/.../db/MessageDao.kt` (modify) | Add `setActive`, remove unused `deactivate`. |
| `shared/src/androidTest/.../db/MessageDaoTest.kt` (modify) | `setActive` hides/restores in `getEligibleMessages`. |
| `mobile/.../sync/PhoneSyncSender.kt` (modify) | Stop filtering out inactive rows. |
| `mobile/.../ui/library/LibraryStore.kt` (rename from `VoteStore.kt`) | `LibraryStore` interface + `RoomLibraryStore` with `setActive`, `deleteByIds`. |
| `mobile/.../ui/library/LibraryEditor.kt` (modify) | `archive`, `unarchive`, `delete`; shared `mutate` helper. |
| `mobile/src/test/.../ui/library/LibraryEditorTest.kt` (modify) | New action tests; fake store extended. |
| `mobile/.../ui/library/LibraryViewModel.kt` (modify) | `filter`, `counts`, filtered `messages`, action forwarding, `deleteAllRetired`. |
| `mobile/.../ui/library/LibraryFilterChips.kt` (new) | Chip row (both themes) + `DeleteAllRetiredBar` + confirmation dialog. Keeps `LibraryScreen.kt` from growing further. |
| `mobile/.../ui/library/LibraryScreen.kt` (modify) | Wire chips, per-chip card actions, read-only votes on archived cards. |
| `README.md`, `CLAUDE.md` (modify) | Document archive/filters/delete and the sender change. |

---

### Task 1: `LibraryFilters` pure helper

**Files:**
- Create: `mobile/src/main/java/com/meatsack/motivator/mobile/ui/library/LibraryFilters.kt`
- Test: `mobile/src/test/java/com/meatsack/motivator/mobile/ui/library/LibraryFiltersTest.kt`

**Interfaces:**
- Consumes: `com.meatsack.shared.model.Message` (fields `isActive: Boolean`, `votesDown: Int`).
- Produces: `enum class LibraryFilter { ACTIVE, ARCHIVED, RETIRED }` and `object LibraryFilters { fun stateOf(m: Message): LibraryFilter; fun apply(messages: List<Message>, filter: LibraryFilter): List<Message>; fun counts(messages: List<Message>): Map<LibraryFilter, Int> }`. `counts` always contains all three keys (0 when empty).

- [ ] **Step 1: Write the failing test**

```kotlin
package com.meatsack.motivator.mobile.ui.library

import com.meatsack.shared.constants.EscalationLevel
import com.meatsack.shared.constants.MessageSource
import com.meatsack.shared.constants.MessageTone
import com.meatsack.shared.constants.TriggerType
import com.meatsack.shared.model.Message
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryFiltersTest {

    private fun msg(id: Long, down: Int = 0, active: Boolean = true, up: Int = 0) = Message(
        id = id, text = "m$id", level = EscalationLevel.SAVAGE, triggerType = TriggerType.INACTIVITY,
        tone = MessageTone.FULL_SEND, source = MessageSource.AI_GENERATED,
        votesUp = up, votesDown = down, lastShownTimestamp = 0, isActive = active,
    )

    @Test fun stateOf_activeUnderThreeDownvotes_isActive() {
        assertEquals(LibraryFilter.ACTIVE, LibraryFilters.stateOf(msg(1, down = 0)))
        assertEquals(LibraryFilter.ACTIVE, LibraryFilters.stateOf(msg(1, down = 2)))
    }

    @Test fun stateOf_activeWithThreeOrMoreDownvotes_isRetired() {
        assertEquals(LibraryFilter.RETIRED, LibraryFilters.stateOf(msg(1, down = 3)))
        assertEquals(LibraryFilter.RETIRED, LibraryFilters.stateOf(msg(1, down = 9, up = 20)))
    }

    @Test fun stateOf_inactive_isArchivedRegardlessOfVotes() {
        assertEquals(LibraryFilter.ARCHIVED, LibraryFilters.stateOf(msg(1, down = 0, active = false)))
        assertEquals(LibraryFilter.ARCHIVED, LibraryFilters.stateOf(msg(1, down = 5, active = false)))
    }

    @Test fun apply_returnsOnlyMatchingRows_inInputOrder() {
        val rows = listOf(msg(3, down = 3), msg(1), msg(2, active = false), msg(4))
        assertEquals(listOf(1L, 4L), LibraryFilters.apply(rows, LibraryFilter.ACTIVE).map { it.id })
        assertEquals(listOf(2L), LibraryFilters.apply(rows, LibraryFilter.ARCHIVED).map { it.id })
        assertEquals(listOf(3L), LibraryFilters.apply(rows, LibraryFilter.RETIRED).map { it.id })
    }

    @Test fun counts_hasAllThreeKeys_andSumsToInputSize() {
        val rows = listOf(msg(1), msg(2), msg(3, down = 3), msg(4, active = false))
        val counts = LibraryFilters.counts(rows)
        assertEquals(mapOf(LibraryFilter.ACTIVE to 2, LibraryFilter.ARCHIVED to 1, LibraryFilter.RETIRED to 1), counts)
    }

    @Test fun counts_onEmptyInput_isAllZeros() {
        assertEquals(
            mapOf(LibraryFilter.ACTIVE to 0, LibraryFilter.ARCHIVED to 0, LibraryFilter.RETIRED to 0),
            LibraryFilters.counts(emptyList()),
        )
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :mobile:testDebugUnitTest --tests "com.meatsack.motivator.mobile.ui.library.LibraryFiltersTest" --console=plain -q`
Expected: compilation error `Unresolved reference 'LibraryFilter'`.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.meatsack.motivator.mobile.ui.library

import com.meatsack.shared.model.Message

/** The three mutually exclusive Library states. See spec §Behaviour/States. */
enum class LibraryFilter { ACTIVE, ARCHIVED, RETIRED }

/**
 * Single source of truth for which state a [Message] is in. Pure (no Android
 * deps) so the ViewModel and screen stay thin and this is JVM-testable.
 *
 * - ARCHIVED: `isActive == false` (user keep-pile; never fires, never pruned).
 * - RETIRED:  active with `votesDown >= 3` (unfireable; deleted on next Generate unless loved).
 * - ACTIVE:   everything else.
 */
object LibraryFilters {
    private const val RETIRE_DOWNVOTES = 3

    fun stateOf(m: Message): LibraryFilter = when {
        !m.isActive -> LibraryFilter.ARCHIVED
        m.votesDown >= RETIRE_DOWNVOTES -> LibraryFilter.RETIRED
        else -> LibraryFilter.ACTIVE
    }

    fun apply(messages: List<Message>, filter: LibraryFilter): List<Message> =
        messages.filter { stateOf(it) == filter }

    fun counts(messages: List<Message>): Map<LibraryFilter, Int> {
        val byState = messages.groupingBy { stateOf(it) }.eachCount()
        return LibraryFilter.entries.associateWith { byState[it] ?: 0 }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :mobile:testDebugUnitTest --tests "com.meatsack.motivator.mobile.ui.library.LibraryFiltersTest" --console=plain -q`
Expected: exit 0; `mobile/build/test-results/testDebugUnitTest/TEST-com.meatsack.motivator.mobile.ui.library.LibraryFiltersTest.xml` shows `tests="6" failures="0" errors="0"`.

- [ ] **Step 5: Commit**

```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew spotlessApply -q
git add mobile/src/main/java/com/meatsack/motivator/mobile/ui/library/LibraryFilters.kt mobile/src/test/java/com/meatsack/motivator/mobile/ui/library/LibraryFiltersTest.kt
git commit -m "feat(mobile): LibraryFilters — derive Active/Archived/Retired from isActive + votesDown

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 2: Pruner ignores archived rows

**Files:**
- Modify: `shared/src/main/java/com/meatsack/shared/retention/LibraryPruner.kt` (the `selectForDeletion` body, first line)
- Test: `shared/src/test/java/com/meatsack/shared/retention/LibraryPrunerTest.kt`

**Interfaces:**
- Consumes: existing `LibraryPruner.selectForDeletion(messages: List<Message>, cap: Int, floor: Int): List<Long>`. The test file already has a helper `msg(id, up, down, source, level, tone, trigger)` that builds a `Message` with `isActive = true`; you will add an `active` parameter to it.
- Produces: same signature; inactive rows are never returned and never influence cap/floor.

- [ ] **Step 1: Write the failing tests**

In `LibraryPrunerTest.kt`, change the helper to accept `active`:

```kotlin
    private fun msg(
        id: Long,
        up: Int = 0,
        down: Int = 0,
        source: MessageSource = MessageSource.AI_GENERATED,
        level: EscalationLevel = EscalationLevel.SAVAGE,
        tone: MessageTone = MessageTone.FULL_SEND,
        trigger: TriggerType = TriggerType.INACTIVITY,
        active: Boolean = true,
    ) = Message(
        id = id, text = "m$id", level = level, triggerType = trigger, tone = tone,
        source = source, votesUp = up, votesDown = down, lastShownTimestamp = 0, isActive = active,
    )
```

Add these tests at the end of the class:

```kotlin
    @Test fun archivedRejectedRow_isNeverDeleted() {
        // isActive=false with 3 downvotes and no upvotes: would be "rejected" if active. Archive exempts it.
        val padding = (1L..5L).map { msg(it) }
        val archived = msg(99, up = 0, down = 3, active = false)
        val ids = LibraryPruner.selectForDeletion(padding + archived, cap = 50, floor = 5)
        assertTrue(ids.isEmpty())
    }

    @Test fun archivedRows_doNotCountTowardCap() {
        // 50 active (exactly at cap) + 10 archived: nothing over cap, nothing deleted.
        val active = (1L..50L).map { msg(it) }
        val archived = (101L..110L).map { msg(it, active = false) }
        val ids = LibraryPruner.selectForDeletion(active + archived, cap = 50, floor = 5)
        assertTrue(ids.isEmpty())
    }

    @Test fun archivedRows_doNotSatisfyFloor() {
        // 5 archived cannot hold the floor; the 3 rejected active rows are not fireable so
        // the floor guard cannot restore them either — they are deleted.
        val archived = (1L..5L).map { msg(it, active = false) }
        val rejected = (11L..13L).map { msg(it, down = 3) }
        val ids = LibraryPruner.selectForDeletion(archived + rejected, cap = 50, floor = 5)
        assertEquals(setOf(11L, 12L, 13L), ids.toSet())
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :shared:testDebugUnitTest --tests "com.meatsack.shared.retention.LibraryPrunerTest" --console=plain -q`
Expected: `archivedRejectedRow_isNeverDeleted` FAILS (returns `[99]`) and `archivedRows_doNotCountTowardCap` FAILS (10 surplus deleted). `archivedRows_doNotSatisfyFloor` may already pass — that is fine; it pins behaviour.

- [ ] **Step 3: Write minimal implementation**

In `LibraryPruner.kt`, change the KDoc bullet list to add one line and the function's first statement:

```kotlin
 * Per (level, tone, trigger) bucket:
 *  - archived rows (isActive = false) are ignored entirely: never deleted, not counted toward
 *    the cap, not counted as fireable for the floor (spec 2026-09-25-library-archive).
 *  - loved (votesUp > votesDown) rows are permanent (never returned).
```

```kotlin
    fun selectForDeletion(messages: List<Message>, cap: Int, floor: Int): List<Long> {
        val toDelete = mutableListOf<Long>()

        // Archived rows are the user's keep-pile; they play no part in retention.
        val byBucket = messages.filter { it.isActive }.groupBy { Bucket(it.level, it.tone, it.triggerType) }
```

(Everything below that line is unchanged.)

- [ ] **Step 4: Run tests to verify they pass**

Run: `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :shared:testDebugUnitTest --tests "com.meatsack.shared.retention.LibraryPrunerTest" --console=plain -q`
Expected: exit 0; report shows all cases (existing + 3 new) with `failures="0"`.

- [ ] **Step 5: Commit**

```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew spotlessApply -q
git add shared/src/main/java/com/meatsack/shared/retention/LibraryPruner.kt shared/src/test/java/com/meatsack/shared/retention/LibraryPrunerTest.kt
git commit -m "feat(shared): LibraryPruner ignores archived (inactive) rows

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 3: `MessageDao.setActive` and sender sends inactive rows

**Files:**
- Modify: `shared/src/main/java/com/meatsack/shared/db/MessageDao.kt` (replace `deactivate`)
- Modify: `mobile/src/main/java/com/meatsack/motivator/mobile/sync/PhoneSyncSender.kt` (`syncMessagesToWatch`, the `messages` query)
- Test: `shared/src/androidTest/java/com/meatsack/shared/db/MessageDaoTest.kt`

**Interfaces:**
- Produces: `suspend fun setActive(messageId: Long, active: Boolean)` on `MessageDao`. `deactivate` is removed (verified: zero callers).
- `PhoneSyncSender.syncMessagesToWatch()` now includes rows with `isActive = false` in the payload (still capped at `CACHE_SIZE = 200`, still ordered by net votes desc via `getAllMessages`).

- [ ] **Step 1: Write the failing instrumented test**

Append to `MessageDaoTest.kt` (inside the class, before the final `}`):

```kotlin
    @Test
    fun setActive_false_hidesFromEligible_andTrueRestores() = runBlocking {
        dao.insertAll(listOf(testMessage("archive me").copy(id = 5)))
        val eligible = {
            runBlocking {
                dao.getEligibleMessages(
                    EscalationLevel.AGGRESSIVE,
                    TriggerType.INACTIVITY,
                    MessageTone.FULL_SEND,
                    cutoffTimestamp = 0,
                ).map { it.id }
            }
        }
        assertEquals(listOf(5L), eligible())

        dao.setActive(5, active = false)
        assertTrue(eligible().isEmpty())
        assertEquals(false, dao.getAllMessages().single().isActive)

        dao.setActive(5, active = true)
        assertEquals(listOf(5L), eligible())
    }
```

- [ ] **Step 2: Verify it fails to compile**

Run: `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :shared:compileDebugAndroidTestKotlin --console=plain -q`
Expected: `Unresolved reference 'setActive'`.

- [ ] **Step 3: Implement the DAO change**

In `MessageDao.kt` replace

```kotlin
    @Query("UPDATE messages SET isActive = 0 WHERE id = :messageId")
    suspend fun deactivate(messageId: Long)
```

with

```kotlin
    /**
     * Archive (`false`) / unarchive (`true`). An inactive row never fires on the
     * watch (getEligibleMessages requires isActive = 1), is never pruned, and is
     * excluded from the AI example queries. See spec 2026-09-25-library-archive.
     */
    @Query("UPDATE messages SET isActive = :active WHERE id = :messageId")
    suspend fun setActive(messageId: Long, active: Boolean)
```

- [ ] **Step 4: Implement the sender change**

In `PhoneSyncSender.syncMessagesToWatch()` replace the comment + query block

```kotlin
        // Send every active row, *including* ones with votesDown >= 3. The watch's
        // getEligibleMessages() already hides rejected rows, so sending them can't
        // make them fire — but omitting them means a phone-side downvote that
        // reaches 3 never reaches the watch, whose stale copy would keep firing.
        // Rows the user has rated down tend to sort last (getAllMessages orders
        // by net score DESC), so they are the most likely to fall off CACHE_SIZE.
        val messages = db.messageDao().getAllMessages()
            .filter { it.isActive }
            .take(CACHE_SIZE)
```

with

```kotlin
        // Send every row, including votesDown >= 3 (retired) and isActive = 0
        // (archived). The watch's getEligibleMessages() hides both, so sending
        // them can't make them fire — but omitting them means a phone-side
        // retire/archive never reaches the watch, whose stale copy would keep
        // firing. Rows the user has rated down tend to sort last (getAllMessages
        // orders by net score DESC), so they are the most likely to fall off
        // CACHE_SIZE.
        val messages = db.messageDao().getAllMessages()
            .take(CACHE_SIZE)
```

- [ ] **Step 5: Compile everything and run unit suites**

Run: `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :shared:compileDebugAndroidTestKotlin :mobile:compileDebugKotlin :wear:compileDebugKotlin :shared:testDebugUnitTest :mobile:testDebugUnitTest :wear:testDebugUnitTest --console=plain -q`
Expected: exit 0, no `e:` lines.

- [ ] **Step 6: Run the instrumented test if a device is attached**

Run: `adb devices` — if a booted emulator/watch is listed:
`JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ANDROID_SERIAL=<serial> ./gradlew :shared:connectedDebugAndroidTest --console=plain -q`
Expected: `shared/build/outputs/androidTest-results/connected/debug/*.xml` contains `setActive_false_hidesFromEligible_andTrueRestores` and `failures="0"`. If no device: note "instrumented test compiled, not run" in the commit body.

- [ ] **Step 7: Commit**

```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew spotlessApply -q
git add shared/src/main/java/com/meatsack/shared/db/MessageDao.kt shared/src/androidTest/java/com/meatsack/shared/db/MessageDaoTest.kt mobile/src/main/java/com/meatsack/motivator/mobile/sync/PhoneSyncSender.kt
git commit -m "feat: MessageDao.setActive; PhoneSyncSender sends archived rows so the watch hides them

Replaces the unused deactivate(). The sender previously dropped isActive=0
rows, which would have left the watch firing a stale active copy of an
archived insult.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 4: `LibraryStore` and `LibraryEditor` archive / unarchive / delete

**Files:**
- Rename: `mobile/src/main/java/com/meatsack/motivator/mobile/ui/library/VoteStore.kt` → `LibraryStore.kt`
- Modify: `mobile/src/main/java/com/meatsack/motivator/mobile/ui/library/LibraryEditor.kt`
- Modify: `mobile/src/main/java/com/meatsack/motivator/mobile/ui/library/LibraryViewModel.kt` (only the `RoomVoteStore` → `RoomLibraryStore` reference, so it compiles; the rest of the ViewModel changes are Task 5)
- Test: `mobile/src/test/java/com/meatsack/motivator/mobile/ui/library/LibraryEditorTest.kt`

**Interfaces:**
- Consumes: `MessageDao.setActive(messageId, active)` and `MessageDao.deleteByIds(ids)` from Task 3.
- Produces:
  ```kotlin
  interface LibraryStore {
      suspend fun voteUp(messageId: Long)
      suspend fun voteDown(messageId: Long)
      suspend fun setActive(messageId: Long, active: Boolean)
      suspend fun deleteByIds(ids: List<Long>)
  }
  class RoomLibraryStore(dao: MessageDao) : LibraryStore
  ```
  and on `LibraryEditor`: `fun archive(messageId: Long)`, `fun unarchive(messageId: Long)`, `fun delete(messageIds: List<Long>)`. Constructor parameter renamed `store: LibraryStore`.

- [ ] **Step 1: Write the failing tests**

In `LibraryEditorTest.kt`, replace the `FakeStore` class with:

```kotlin
    private class FakeStore : LibraryStore {
        val ups = mutableListOf<Long>()
        val downs = mutableListOf<Long>()
        val actives = mutableListOf<Pair<Long, Boolean>>()
        val deletes = mutableListOf<List<Long>>()
        var failNextWith: Throwable? = null

        private fun maybeFail() {
            failNextWith?.let {
                failNextWith = null
                throw it
            }
        }
        override suspend fun voteUp(messageId: Long) {
            maybeFail()
            ups += messageId
        }
        override suspend fun voteDown(messageId: Long) {
            maybeFail()
            downs += messageId
        }
        override suspend fun setActive(messageId: Long, active: Boolean) {
            maybeFail()
            actives += messageId to active
        }
        override suspend fun deleteByIds(ids: List<Long>) {
            maybeFail()
            deletes += ids
        }
    }
```

Add these tests before `defaultDebounce_isTwoSeconds`:

```kotlin
    @Test
    fun archive_writesSetActiveFalse_andSchedulesOneSync() = runTest {
        val store = FakeStore()
        val sync = CountingSync()
        val editor = LibraryEditor(store, sync.fn, this, debounce) {}

        editor.archive(9L)
        advanceUntilIdle()

        assertEquals(listOf(9L to false), store.actives)
        assertEquals(1, sync.calls)
    }

    @Test
    fun unarchive_writesSetActiveTrue_andSchedulesOneSync() = runTest {
        val store = FakeStore()
        val sync = CountingSync()
        val editor = LibraryEditor(store, sync.fn, this, debounce) {}

        editor.unarchive(9L)
        advanceUntilIdle()

        assertEquals(listOf(9L to true), store.actives)
        assertEquals(1, sync.calls)
    }

    @Test
    fun delete_writesDeleteByIds_andSchedulesOneSync() = runTest {
        val store = FakeStore()
        val sync = CountingSync()
        val editor = LibraryEditor(store, sync.fn, this, debounce) {}

        editor.delete(listOf(3L, 4L))
        advanceUntilIdle()

        assertEquals(listOf(listOf(3L, 4L)), store.deletes)
        assertEquals(1, sync.calls)
    }

    @Test
    fun delete_withEmptyList_writesNothing_andSchedulesNoSync() = runTest {
        val store = FakeStore()
        val sync = CountingSync()
        val editor = LibraryEditor(store, sync.fn, this, debounce) {}

        editor.delete(emptyList())
        advanceUntilIdle()

        assertTrue(store.deletes.isEmpty())
        assertEquals(0, sync.calls)
    }

    @Test
    fun archiveWriteFailure_skipsSync() = runTest {
        val store = FakeStore().apply { failNextWith = IllegalStateException("disk full") }
        val sync = CountingSync()
        val editor = LibraryEditor(store, sync.fn, this, debounce) {}

        editor.archive(1L)
        advanceUntilIdle()

        assertTrue(store.actives.isEmpty())
        assertEquals(0, sync.calls)
    }

    @Test
    fun voteAndArchiveInsideWindow_collapseToOneSync() = runTest {
        val store = FakeStore()
        val sync = CountingSync()
        val editor = LibraryEditor(store, sync.fn, this, debounce) {}

        editor.voteUp(1L)
        advanceTimeBy(500)
        editor.archive(2L)
        advanceUntilIdle()

        assertEquals(listOf(1L), store.ups)
        assertEquals(listOf(2L to false), store.actives)
        assertEquals(1, sync.calls)
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :mobile:testDebugUnitTest --tests "com.meatsack.motivator.mobile.ui.library.LibraryEditorTest" --console=plain -q`
Expected: compilation errors `Unresolved reference 'LibraryStore'`, `'archive'`, `'unarchive'`, `'delete'`.

- [ ] **Step 3: Rename the store**

`git mv mobile/src/main/java/com/meatsack/motivator/mobile/ui/library/VoteStore.kt mobile/src/main/java/com/meatsack/motivator/mobile/ui/library/LibraryStore.kt` and replace its contents with:

```kotlin
package com.meatsack.motivator.mobile.ui.library

import com.meatsack.shared.db.MessageDao

/**
 * Narrow persistence surface for phone-side Library mutations (votes, archive,
 * delete), so [LibraryEditor] can be unit-tested with a fake instead of a Room
 * database. Mirrors the `GenerationStore` seam used by the AI generator.
 *
 * The vote methods increment the same columns the watch's InsultActivity votes
 * bump, so a phone vote and a watch vote are the same operation.
 */
interface LibraryStore {
    suspend fun voteUp(messageId: Long)
    suspend fun voteDown(messageId: Long)

    /** Archive (`false`) / unarchive (`true`). See spec 2026-09-25-library-archive. */
    suspend fun setActive(messageId: Long, active: Boolean)

    /** Hard delete on the phone only; the watch never deletes rows. */
    suspend fun deleteByIds(ids: List<Long>)
}

/** Production implementation backed by the Room [MessageDao]. */
class RoomLibraryStore(private val dao: MessageDao) : LibraryStore {
    override suspend fun voteUp(messageId: Long) = dao.voteUp(messageId)
    override suspend fun voteDown(messageId: Long) = dao.voteDown(messageId)
    override suspend fun setActive(messageId: Long, active: Boolean) = dao.setActive(messageId, active)
    override suspend fun deleteByIds(ids: List<Long>) = dao.deleteByIds(ids)
}
```

- [ ] **Step 4: Extend the editor**

In `LibraryEditor.kt`:

Change the constructor parameter type `private val store: VoteStore,` → `private val store: LibraryStore,`.

Update the class KDoc's first paragraph to:

```kotlin
/**
 * Applies phone-side Library mutations — votes, archive/unarchive, delete — and
 * pushes the library to the watch after a debounce.
```

Replace the two `voteUp`/`voteDown` functions and the private `vote` function with:

```kotlin
    fun voteUp(messageId: Long) = mutate("voteUp", messageId) { store.voteUp(messageId) }

    fun voteDown(messageId: Long) = mutate("voteDown", messageId) { store.voteDown(messageId) }

    /** Keep-pile: the row stops firing on the watch after the next sync and is never pruned. */
    fun archive(messageId: Long) = mutate("archive", messageId) { store.setActive(messageId, false) }

    fun unarchive(messageId: Long) = mutate("unarchive", messageId) { store.setActive(messageId, true) }

    /**
     * Phone-only hard delete. Still schedules a sync so a pending vote/archive on
     * the same timer is not delayed by special-casing; the payload simply omits the rows.
     */
    fun delete(messageIds: List<Long>) {
        if (messageIds.isEmpty()) return
        mutate("delete", messageIds) { store.deleteByIds(messageIds) }
    }

    private fun mutate(action: String, target: Any, write: suspend () -> Unit) {
        scope.launch {
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
```

In `LibraryViewModel.kt` change `store = RoomVoteStore(dao),` → `store = RoomLibraryStore(dao),`.

- [ ] **Step 5: Run tests to verify they pass**

Run: `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :mobile:testDebugUnitTest --tests "com.meatsack.motivator.mobile.ui.library.LibraryEditorTest" --console=plain -q`
Expected: exit 0; report shows the existing 11 tests + 6 new, `failures="0"`.

- [ ] **Step 6: Commit**

```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew spotlessApply -q
git add -A mobile/src/main/java/com/meatsack/motivator/mobile/ui/library/ mobile/src/test/java/com/meatsack/motivator/mobile/ui/library/LibraryEditorTest.kt
git commit -m "feat(mobile): LibraryEditor archive/unarchive/delete on the debounced sync path

VoteStore becomes LibraryStore (setActive, deleteByIds). Mutations share one
mutate() helper: write, log-and-drop on failure, else schedule the 2 s sync.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 5: `LibraryViewModel` filter, counts and actions

**Files:**
- Modify: `mobile/src/main/java/com/meatsack/motivator/mobile/ui/library/LibraryViewModel.kt`

**Interfaces:**
- Consumes: `LibraryFilter`, `LibraryFilters` (Task 1); `LibraryEditor.archive/unarchive/delete` (Task 4); existing `FrozenOrder`.
- Produces (used by Task 6):
  ```kotlin
  val filter: StateFlow<LibraryFilter>          // default ACTIVE
  val counts: StateFlow<Map<LibraryFilter, Int>> // all three keys always present
  val messages: StateFlow<List<Message>>        // frozen-ordered, then filtered by `filter`
  fun setFilter(f: LibraryFilter)
  fun archive(messageId: Long); fun unarchive(messageId: Long)
  fun delete(messageId: Long); fun deleteAllRetired()
  ```
  `voteUp`/`voteDown`/`autoSyncResults`/`consumeAutoSyncResult` unchanged.

The ViewModel has no unit test today (it is an `AndroidViewModel` needing an `Application`); the logic it composes — `FrozenOrder.apply`, `LibraryFilters.apply/counts`, the editor — is each tested on its own. Keep it thin.

- [ ] **Step 1: Rewrite the ViewModel**

Replace the whole file with:

```kotlin
package com.meatsack.motivator.mobile.ui.library

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.meatsack.motivator.mobile.sync.PhoneSyncSender
import com.meatsack.motivator.mobile.sync.SyncResult
import com.meatsack.shared.db.AppDatabase
import com.meatsack.shared.model.Message
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class LibraryViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = AppDatabase.getDatabase(application).messageDao()
    private val order = FrozenOrder()

    /** Every row, in frozen display order. Single Room observer; everything else derives from it. */
    private val allMessages: StateFlow<List<Message>> = dao.getAllMessagesFlow()
        .map { order.apply(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _filter = MutableStateFlow(LibraryFilter.ACTIVE)

    /** Selected chip. Screen state only: resets to ACTIVE when the ViewModel is recreated. */
    val filter: StateFlow<LibraryFilter> = _filter

    val counts: StateFlow<Map<LibraryFilter, Int>> = allMessages
        .map { LibraryFilters.counts(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryFilters.counts(emptyList()))

    /** Rows for the selected chip. Filter is applied *after* FrozenOrder so chips never reorder. */
    val messages: StateFlow<List<Message>> = combine(allMessages, _filter) { all, f ->
        LibraryFilters.apply(all, f)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setFilter(f: LibraryFilter) {
        _filter.value = f
    }

    /**
     * One emission per completed debounced auto-sync after a phone mutation. The screen
     * surfaces only [SyncResult.Failed]; successes are silent.
     *
     * replay = 1 + DROP_OLDEST so the latest result survives until a collector sees
     * it — including a collector that attaches after the fact when the user returns
     * to this tab — and a newer result replaces an unseen older one. The screen
     * calls [consumeAutoSyncResult] after showing a result so it isn't replayed on
     * re-entry.
     */
    private val _autoSyncResults = MutableSharedFlow<SyncResult>(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val autoSyncResults: SharedFlow<SyncResult> = _autoSyncResults

    /** Called by the screen once it has shown a result, so a re-entering screen doesn't replay it. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun consumeAutoSyncResult() = _autoSyncResults.resetReplayCache()

    private val editor = LibraryEditor(
        store = RoomLibraryStore(dao),
        sync = { PhoneSyncSender(application).syncMessagesToWatch() },
        scope = viewModelScope,
        onSyncResult = { _autoSyncResults.tryEmit(it) },
    )

    fun voteUp(messageId: Long) = editor.voteUp(messageId)

    fun voteDown(messageId: Long) = editor.voteDown(messageId)

    fun archive(messageId: Long) = editor.archive(messageId)

    fun unarchive(messageId: Long) = editor.unarchive(messageId)

    fun delete(messageId: Long) = editor.delete(listOf(messageId))

    /** Bulk delete of every currently-retired row. The screen confirms before calling this. */
    fun deleteAllRetired() =
        editor.delete(LibraryFilters.apply(allMessages.value, LibraryFilter.RETIRED).map { it.id })
}
```

- [ ] **Step 2: Compile**

Run: `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :mobile:compileDebugKotlin --console=plain -q`
Expected: exit 0. (`LibraryScreen` still compiles because `messages`, `voteUp`, `voteDown`, `autoSyncResults`, `consumeAutoSyncResult` keep their names.)

- [ ] **Step 3: Run the mobile unit suite**

Run: `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :mobile:testDebugUnitTest --console=plain -q`
Expected: exit 0, no failures.

- [ ] **Step 4: Commit**

```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew spotlessApply -q
git add mobile/src/main/java/com/meatsack/motivator/mobile/ui/library/LibraryViewModel.kt
git commit -m "feat(mobile): LibraryViewModel exposes filter, per-state counts, and archive/delete actions

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 6: Library screen — chips, per-chip card actions, delete-all

**Files:**
- Create: `mobile/src/main/java/com/meatsack/motivator/mobile/ui/library/LibraryFilterChips.kt`
- Modify: `mobile/src/main/java/com/meatsack/motivator/mobile/ui/library/LibraryScreen.kt`

**Interfaces:**
- Consumes: `LibraryViewModel.filter/counts/messages/setFilter/archive/unarchive/delete/deleteAllRetired` (Task 5); `LibraryFilter` (Task 1); `LocalThemeChoice`/`ThemeChoice.BUBBLEGUM` (existing).
- Produces (all `internal`, same package):
  ```kotlin
  @Composable fun LibraryFilterChips(selected: LibraryFilter, counts: Map<LibraryFilter, Int>, bubblegum: Boolean, onSelect: (LibraryFilter) -> Unit, modifier: Modifier = Modifier)
  @Composable fun DeleteAllRetiredBar(count: Int, bubblegum: Boolean, onConfirmed: () -> Unit, modifier: Modifier = Modifier)
  ```

Compose UI has no unit tests in this project (no `createComposeRule` dependency); verification is compile + manual on the emulator in Task 7. Keep logic out of the composables: the only decisions here are "which actions to show for `LibraryFilters.stateOf(message)`".

- [ ] **Step 1: Create `LibraryFilterChips.kt`**

```kotlin
package com.meatsack.motivator.mobile.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Display labels shared by both themes; Vitals upper-cases them. */
private fun LibraryFilter.label(): String = when (this) {
    LibraryFilter.ACTIVE -> "Active"
    LibraryFilter.ARCHIVED -> "Archived"
    LibraryFilter.RETIRED -> "Retired"
}

/**
 * Three selectable chips with counts. `selected` semantics + Role.Tab so TalkBack
 * reads "Active, 74, selected, tab".
 */
@Composable
internal fun LibraryFilterChips(
    selected: LibraryFilter,
    counts: Map<LibraryFilter, Int>,
    bubblegum: Boolean,
    onSelect: (LibraryFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LibraryFilter.entries.forEach { f ->
            val isSelected = f == selected
            val count = counts[f] ?: 0
            val text = if (bubblegum) "${f.label()} $count" else "[ ${f.label().uppercase()} $count ]"
            val shape = if (bubblegum) RoundedCornerShape(percent = 50) else RoundedCornerShape(6.dp)
            val fg = when {
                isSelected && bubblegum -> MaterialTheme.colorScheme.onPrimary
                isSelected -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            val bg = if (isSelected && bubblegum) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface
            val outline = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 40.dp)
                    .background(bg, shape)
                    .border(1.dp, outline, shape)
                    .semantics { this.selected = isSelected }
                    .clickable(onClick = { onSelect(f) }, role = Role.Tab, onClickLabel = "Show ${f.label().lowercase()}")
                    .padding(horizontal = 8.dp, vertical = 8.dp),
            ) {
                Text(text = text, style = MaterialTheme.typography.labelMedium, color = fg, maxLines = 1)
            }
        }
    }
}

/**
 * "Delete all retired (N)" bar shown only on the Retired chip when N > 0. Bulk and
 * permanent, so it confirms via AlertDialog before calling [onConfirmed].
 */
@Composable
internal fun DeleteAllRetiredBar(
    count: Int,
    bubblegum: Boolean,
    onConfirmed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirming by remember { mutableStateOf(false) }
    val shape = if (bubblegum) RoundedCornerShape(percent = 50) else RoundedCornerShape(8.dp)
    val color = MaterialTheme.colorScheme.error
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .border(1.dp, color, shape)
            .clickable(onClick = { confirming = true }, role = Role.Button, onClickLabel = "Delete all retired")
            .padding(horizontal = 14.dp, vertical = 13.dp),
    ) {
        Text(
            text = if (bubblegum) "Delete all $count retired 🗑" else "> DELETE ALL RETIRED ($count)",
            style = MaterialTheme.typography.labelLarge,
            color = color,
        )
    }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Delete $count retired insults?") },
            text = { Text("They can never fire again (3+ downvotes). This removes them from the phone permanently. Archive any you want to keep first.") },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    onConfirmed()
                }) { Text("Delete", color = color) }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("Cancel") } },
        )
    }
}
```

- [ ] **Step 2: Wire the screen**

In `LibraryScreen.kt`:

(a) Add imports (alphabetical among the existing ones):

```kotlin
import androidx.compose.foundation.layout.width
```

(b) In `LibraryScreen`, after `val messages by viewModel.messages.collectAsState()` add:

```kotlin
    val filter by viewModel.filter.collectAsState()
    val counts by viewModel.counts.collectAsState()
    val shownCount = counts[filter] ?: 0
```

(c) Replace the header line `if (bubblegum) BubblegumHeader(messages.size) else VitalsHeader(messages.size)` with:

```kotlin
            if (bubblegum) BubblegumHeader(shownCount) else VitalsHeader(shownCount)
```

(d) Immediately after the sync bar block (the `if (bubblegum) { BubblegumSyncBar(...) } else { SyncBar(...) }`), add:

```kotlin
            LibraryFilterChips(
                selected = filter,
                counts = counts,
                bubblegum = bubblegum,
                onSelect = viewModel::setFilter,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Spacer(Modifier.height(12.dp))
            if (filter == LibraryFilter.RETIRED && shownCount > 0) {
                DeleteAllRetiredBar(
                    count = shownCount,
                    bubblegum = bubblegum,
                    onConfirmed = viewModel::deleteAllRetired,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                Spacer(Modifier.height(12.dp))
            }
```

(e) Replace the `items(...)` block body with:

```kotlin
                items(messages, key = { it.id }) { message ->
                    val actions = CardActions(
                        state = LibraryFilters.stateOf(message),
                        onVoteUp = { viewModel.voteUp(message.id) },
                        onVoteDown = { viewModel.voteDown(message.id) },
                        onArchive = { viewModel.archive(message.id) },
                        onUnarchive = { viewModel.unarchive(message.id) },
                        onDelete = { viewModel.delete(message.id) },
                    )
                    if (bubblegum) BubblegumPanel(message, actions) else InsultPanel(message, actions)
                }
```

(f) Add, just above the `VoteControls` composable, the action model and a shared secondary-action control:

```kotlin
/** Everything a card can do; which controls render depends on [state] (spec §Filter chips). */
internal class CardActions(
    val state: LibraryFilter,
    val onVoteUp: () -> Unit,
    val onVoteDown: () -> Unit,
    val onArchive: () -> Unit,
    val onUnarchive: () -> Unit,
    val onDelete: () -> Unit,
)

/** A tappable text action (Archive / Restore / Delete) with a 48 dp touch target. */
@Composable
private fun SecondaryAction(text: String, onClickLabel: String, color: Color, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .clickable(onClick = onClick, role = Role.Button, onClickLabel = onClickLabel)
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .padding(horizontal = 4.dp),
    ) {
        Text(text = text, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

/**
 * Per-state action row appended to a card's meta line:
 *  ACTIVE   → votes + Archive
 *  RETIRED  → Archive + Delete (votes shown read-only: the third 👎 already retired it)
 *  ARCHIVED → Restore (votes shown read-only)
 */
@Composable
private fun CardActionRow(
    message: Message,
    actions: CardActions,
    upGlyph: String,
    downGlyph: String,
    voteColor: Color,
    archiveLabel: String,
    restoreLabel: String,
    deleteLabel: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        VoteControls(
            upGlyph = upGlyph,
            downGlyph = downGlyph,
            votesUp = message.votesUp,
            votesDown = message.votesDown,
            color = voteColor,
            enabled = actions.state == LibraryFilter.ACTIVE,
            onVoteUp = actions.onVoteUp,
            onVoteDown = actions.onVoteDown,
        )
        Spacer(Modifier.width(4.dp))
        when (actions.state) {
            LibraryFilter.ACTIVE ->
                SecondaryAction(archiveLabel, "Archive", MaterialTheme.colorScheme.onSurfaceVariant, actions.onArchive)
            LibraryFilter.RETIRED -> {
                SecondaryAction(archiveLabel, "Archive", MaterialTheme.colorScheme.onSurfaceVariant, actions.onArchive)
                SecondaryAction(deleteLabel, "Delete", MaterialTheme.colorScheme.error, actions.onDelete)
            }
            LibraryFilter.ARCHIVED ->
                SecondaryAction(restoreLabel, "Unarchive", MaterialTheme.colorScheme.primary, actions.onUnarchive)
        }
    }
}
```

(g) Change `VoteControls` to support read-only display. New signature and body:

```kotlin
@Composable
private fun VoteControls(
    upGlyph: String,
    downGlyph: String,
    votesUp: Int,
    votesDown: Int,
    color: Color,
    enabled: Boolean,
    onVoteUp: () -> Unit,
    onVoteDown: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        VoteCell("$upGlyph $votesUp", "Upvotes: $votesUp", "Vote up", color, enabled, onVoteUp)
        VoteCell("$downGlyph $votesDown", "Downvotes: $votesDown", "Vote down", color, enabled, onVoteDown)
    }
}

@Composable
private fun VoteCell(
    text: String,
    description: String,
    clickLabel: String,
    color: Color,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val base = Modifier.semantics { contentDescription = description }
    val modifier = if (enabled) {
        base.clickable(onClick = onClick, role = Role.Button, onClickLabel = clickLabel)
    } else {
        base
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
    ) {
        Text(text = text, style = MaterialTheme.typography.bodySmall, color = color)
    }
}
```

Update the `VoteControls` KDoc's first sentence to: "Two vote controls, tappable only when `enabled` (Active cards); archived and retired cards show the counts read-only."

(h) Change the two panels to take `actions: CardActions` instead of `onVoteUp`/`onVoteDown`, and replace their `VoteControls(...)` call with `CardActionRow(...)`:

`InsultPanel` signature → `private fun InsultPanel(message: Message, actions: CardActions)`; replace its `VoteControls(` block with:

```kotlin
                CardActionRow(
                    message = message,
                    actions = actions,
                    upGlyph = "▲",
                    downGlyph = "▼",
                    voteColor = MaterialTheme.colorScheme.secondary,
                    archiveLabel = "[ ARCHIVE ]",
                    restoreLabel = "[ RESTORE ]",
                    deleteLabel = "[ DELETE ]",
                )
```

`BubblegumPanel` signature → `private fun BubblegumPanel(message: Message, actions: CardActions)`; replace its `VoteControls(` block with:

```kotlin
                CardActionRow(
                    message = message,
                    actions = actions,
                    upGlyph = "💕",
                    downGlyph = "💔",
                    voteColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    archiveLabel = "Keep 🗂",
                    restoreLabel = "Restore 💫",
                    deleteLabel = "Bye 🗑",
                )
```

The `TriggerChip`/`triggerType` text in `InsultPanel` currently takes `Modifier.weight(1f)`; keep it so the action row stays right-aligned. In `BubblegumPanel` the existing `Spacer(Modifier.weight(1f))` stays.

- [ ] **Step 3: Compile and lint**

Run: `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew spotlessApply :mobile:compileDebugKotlin --console=plain -q`
Expected: exit 0, no `e:` lines. If ktlint complains about wrapped parameter lists in `SecondaryAction` calls, put each argument on its own line.

- [ ] **Step 4: Run the mobile unit suite and build the APK**

Run: `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :mobile:testDebugUnitTest :mobile:assembleDebug --console=plain -q`
Expected: exit 0.

- [ ] **Step 5: Commit**

```bash
git add mobile/src/main/java/com/meatsack/motivator/mobile/ui/library/LibraryFilterChips.kt mobile/src/main/java/com/meatsack/motivator/mobile/ui/library/LibraryScreen.kt
git commit -m "feat(mobile): Library filter chips (Active/Archived/Retired), per-state card actions, delete-all-retired

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 7: Docs, full verification, manual check

**Files:**
- Modify: `README.md` (Using the phone app → Library bullet; v2 additions list; Known limitations)
- Modify: `CLAUDE.md` (Modules → `mobile/` bullet; Known v1 Limitations sync bullet)

- [ ] **Step 1: README — Library bullet**

Replace the Library bullet under "Two tabs:" with:

```markdown
- **Library** — every message in the local DB, split into three chips: **Active** (fireable; ▲/▼ votes and an **Archive** action per card), **Archived** (your keep-pile: never fires, never pruned, **Restore** to bring one back), and **Retired** (3+ 👎, can never fire; **Archive** to keep a hilarious one, **Delete** to drop it, or **Delete all retired** to clear them without generating). Plus a **Sync to Watch** button. Vote tallies stay live: thumbs you tap on the watch sync back here automatically, and every vote/archive/delete here pushes to the watch automatically. Tap Sync to push every message (up to 200, retired and archived ones included so the watch can hide them too) to the paired watch.
```

- [ ] **Step 2: README — v2 additions bullet**

After the "Self-pruning library." bullet add:

```markdown
- **Archive the keepers.** Any insult can be archived from the phone Library: it stops firing on the watch, is never pruned, stays out of the AI's style examples, and sits under the Archived chip until you restore it. Retired insults (3+ 👎) can be deleted from the Retired chip without pressing Generate.
```

- [ ] **Step 3: README — Known limitations**

Replace the "Votes converge by last writer, not by merge." bullet with:

```markdown
- **Votes and archive flags converge by last writer, not by merge.** Both devices send absolute state (watch → phone on `/votes`, phone → watch inside `/messages`), so a change made on one device while the other is offline can be overwritten when both come back. Tapping on one device at a time avoids it. Three 👎 retires a message permanently, and there is no undo; deleting from the Retired chip is likewise permanent on the phone (the watch keeps a hidden copy until a future cleanup feature).
```

- [ ] **Step 4: CLAUDE.md — mobile module bullet**

In the `mobile/` bullet, after "`LibraryViewModel` reads from the shared Room DB;" insert:

```markdown
`LibraryFilters` (pure) derives each row's Active/Archived/Retired state (`isActive` false = archived; `votesDown >= 3` = retired) and the screen filters by chip after `FrozenOrder`; `LibraryEditor` applies votes/archive/delete via `LibraryStore` and debounces a sync;
```

- [ ] **Step 5: CLAUDE.md — sync limitation bullet**

In the Known v1 Limitations sync bullet, replace "Phone-side votes (Library ▲/▼) are pushed to the watch by a debounced auto-sync so the watch's next absolute snapshot can't overwrite them." with:

```markdown
Phone-side votes and archive/unarchive (Library ▲/▼, Archive/Restore) are pushed to the watch by a debounced auto-sync so the watch's next absolute snapshot can't overwrite them; the sender sends inactive (archived) and retired rows too, because the watch hides them by query and would otherwise keep firing a stale copy.
```

- [ ] **Step 6: Full verification**

Run: `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew spotlessCheck :shared:testDebugUnitTest :wear:testDebugUnitTest :mobile:testDebugUnitTest :shared:compileDebugAndroidTestKotlin :wear:compileDebugAndroidTestKotlin :mobile:assembleDebug :wear:assembleDebug --console=plain -q`
Expected: exit 0. Then `grep -o 'failures="[1-9][0-9]*"' */build/test-results/testDebugUnitTest/*.xml` prints nothing.

- [ ] **Step 7: Manual check on a phone emulator (if one is running)**

```bash
adb -s <phone-serial> install -r mobile/build/outputs/apk/debug/mobile-debug.apk
adb -s <phone-serial> shell am start -n com.meatsack.motivator/.mobile.MainActivity
```

Then on screen: Library opens on **Active** with a count lower than the total; tap **Archive** on a card → it disappears from Active and the Archived count increments; open **Archived** → the card shows read-only votes and **Restore**; open **Retired** → **Delete all retired (N)** bar appears when N > 0 and asks for confirmation. Switch theme in Settings and repeat once in Bubblegum. If a paired watch is available: archive, wait 3 s, pull the watch DB (procedure in `docs`/memory: `run-as com.meatsack.motivator cat databases/meatsack_database*`) and confirm `isActive = 0` for that id.

If no emulator is available, state that explicitly in the commit body and the PR.

- [ ] **Step 8: Commit and open the PR**

```bash
git add README.md CLAUDE.md
git commit -m "docs: Library archive/filters/delete in README and CLAUDE.md

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
git push -u origin feature/library-archive
gh pr create --base main --head feature/library-archive --title "feat: Library archive, Active/Archived/Retired filters, delete retired" --body-file <notes>
```

PR body must list: the spec path, the no-migration decision, the sender change, the pruner rule, tests run (JVM in CI; instrumented run-or-compiled-only), manual check result, and end with `🤖 Generated with [Claude Code](https://claude.com/claude-code)`. Then run `pr-review-toolkit:review-pr` on the new PR (project rule).

---

## Self-review

**Spec coverage:** States table → Task 1. Filter chips + counts + header count → Tasks 5, 6. Per-card actions by chip → Task 6. Ordering after FrozenOrder → Task 5 (`combine` after `order.apply`). Sync: sender sends inactive rows → Task 3; archive/unarchive/delete via editor → Task 4. Pruner rule → Task 2. AI examples unchanged (already `isActive = 1`) → no task needed, documented in Task 7. `setActive` DAO + delete `deactivate` → Task 3. Error handling (log-and-drop, empty delete no-op) → Task 4. Testing list → Tasks 1, 2, 3 (DAO), 4 (editor). Docs → Task 7. Known limitations → Task 7 README bullet.

**Placeholder scan:** none; every code step has full code. The PR `--body-file <notes>` is the one deliberately open slot, with its required contents listed.

**Type consistency:** `LibraryFilter`/`LibraryFilters.stateOf/apply/counts` (Task 1) used identically in Tasks 5, 6. `LibraryStore.setActive(messageId, active)`/`deleteByIds(ids)` (Task 4) match `MessageDao.setActive(messageId, active)`/`deleteByIds(ids)` (Task 3). `LibraryEditor.archive/unarchive/delete(List<Long>)` (Task 4) match `LibraryViewModel.delete(messageId)` → `editor.delete(listOf(messageId))` (Task 5). `CardActions` fields (Task 6) match the ViewModel methods. `VoteControls` gains `enabled` (Task 6) and both call sites pass it.
