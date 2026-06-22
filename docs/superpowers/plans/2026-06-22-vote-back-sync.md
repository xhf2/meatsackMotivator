# Watch→phone Vote Back-Sync Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Flow watch vote counts back to the phone so the Library reflects them, via a new `/votes` Wear Data Layer channel that mirrors the existing phone→watch pipe in reverse.

**Architecture:** After each vote on the watch, `WatchVoteSender` writes the watch's current vote counts (per message id, absolute) as a `/votes` DataItem; a new phone-side `PhoneVoteReceiver` (`WearableListenerService`) applies them with `MessageDao.setVotes`. The phone Library is already reactive (`getAllMessagesFlow()`), so it refreshes live. Everything is additive — each commit compiles and passes all module tests.

**Tech Stack:** Kotlin, Wear Data Layer (`DataClient`/`WearableListenerService`/`DataMap`), Room (`MessageDao`), JUnit.

**Spec:** `docs/superpowers/specs/2026-06-22-vote-back-sync-design.md`

**Branch:** `feature/vote-back-sync` (already created; spec already committed there).

---

## File Structure

- `shared/.../sync/VoteSyncSerializer.kt` (new) — `VoteSnapshot` + pure `id|up|down` (de)serializer
- `shared/.../sync/SyncChannel.kt` — add `PATH_VOTES` / `KEY_VOTE_DATA`
- `shared/.../db/MessageDao.kt` — add `getVotedMessages()` + `setVotes(...)`
- `wear/.../sync/WatchVoteSender.kt` (new) — read voted rows → write `/votes` DataItem
- `wear/.../presentation/InsultActivity.kt` — fire `WatchVoteSender` after a vote
- `mobile/.../sync/PhoneVoteReceiver.kt` (new) — apply `/votes` to the phone DB
- `mobile/src/main/AndroidManifest.xml` — register `PhoneVoteReceiver`

Build commands (Git Bash, Windows; `./gradlew` works):
- `./gradlew :shared:testDebugUnitTest`
- `./gradlew :shared:testDebugUnitTest :mobile:testDebugUnitTest :wear:testDebugUnitTest`
- `./gradlew spotlessCheck` (auto-fix: `./gradlew spotlessApply && git add -u`)

---

### Task 1: `VoteSyncSerializer` (pure, TDD)

The one genuinely new piece of logic: a symmetric `id|up|down` (de)serializer mirroring `MessageSerializer`, dropping malformed lines instead of throwing.

**Files:**
- Create: `shared/src/main/java/com/meatsack/shared/sync/VoteSyncSerializer.kt`
- Test: `shared/src/test/java/com/meatsack/shared/sync/VoteSyncSerializerTest.kt`

- [ ] **Step 1: Write the failing test**

Create `shared/src/test/java/com/meatsack/shared/sync/VoteSyncSerializerTest.kt`:

```kotlin
package com.meatsack.shared.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class VoteSyncSerializerTest {

    @Test fun roundTrip_preservesAllRows() {
        val votes = listOf(
            VoteSnapshot(42, 5, 2),
            VoteSnapshot(7, 0, 1),
            VoteSnapshot(100, 3, 0),
        )
        val serialized = VoteSyncSerializer.serialize(votes)
        assertEquals(votes, VoteSyncSerializer.deserialize(serialized))
    }

    @Test fun deserialize_emptyString_returnsEmptyList() {
        assertEquals(emptyList<VoteSnapshot>(), VoteSyncSerializer.deserialize(""))
    }

    @Test fun deserialize_dropsMalformedLines_keepsValid() {
        // line 2 has too few fields; line 3 has a non-numeric id
        val data = "42|5|2\nbroken|line\nabc|1|1\n7|0|3"
        assertEquals(
            listOf(VoteSnapshot(42, 5, 2), VoteSnapshot(7, 0, 3)),
            VoteSyncSerializer.deserialize(data),
        )
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :shared:testDebugUnitTest --tests "com.meatsack.shared.sync.VoteSyncSerializerTest"`
Expected: FAIL — unresolved reference `VoteSnapshot` / `VoteSyncSerializer`.

- [ ] **Step 3: Implement**

Create `shared/src/main/java/com/meatsack/shared/sync/VoteSyncSerializer.kt`:

```kotlin
package com.meatsack.shared.sync

/** One message's vote counts, keyed by message id. */
data class VoteSnapshot(val id: Long, val votesUp: Int, val votesDown: Int)

/**
 * Symmetric (de)serializer for the `/votes` Wear Data Layer channel (watch → phone).
 * One snapshot per `\n`-separated line, 3 `|`-separated fields: id, votesUp, votesDown.
 *
 * Malformed lines are dropped (logged at WARN) rather than throwing, so a corrupt
 * payload never takes down the receiver. Mirrors MessageSerializer.
 */
object VoteSyncSerializer {
    private const val FIELD_COUNT = 3
    private const val FIELD_SEPARATOR = "|"
    private const val LINE_SEPARATOR = "\n"

    fun serialize(votes: List<VoteSnapshot>): String =
        votes.joinToString(LINE_SEPARATOR) { v ->
            listOf(v.id, v.votesUp, v.votesDown).joinToString(FIELD_SEPARATOR)
        }

    fun deserialize(data: String): List<VoteSnapshot> {
        if (data.isEmpty()) return emptyList()
        return data.split(LINE_SEPARATOR).mapNotNull { parseLine(it) }
    }

    private fun parseLine(line: String): VoteSnapshot? {
        val parts = line.split(FIELD_SEPARATOR)
        if (parts.size != FIELD_COUNT) {
            android.util.Log.w(
                "VoteSyncSerializer",
                "Dropped line with ${parts.size} fields (expected $FIELD_COUNT): ${line.take(80)}",
            )
            return null
        }
        return runCatching {
            VoteSnapshot(
                id = parts[0].toLong(),
                votesUp = parts[1].toInt(),
                votesDown = parts[2].toInt(),
            )
        }.onFailure { error ->
            android.util.Log.w("VoteSyncSerializer", "Dropped malformed line: ${line.take(80)}", error)
        }.getOrNull()
    }
}
```

(`android.util.Log.w` is safe in `:shared` JVM unit tests — the existing `MessageSerializerTest` exercises the same drop-malformed path, so the module is already configured for it.)

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :shared:testDebugUnitTest --tests "com.meatsack.shared.sync.VoteSyncSerializerTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Verify formatting & commit**

Run: `./gradlew :shared:spotlessCheck` (fix with `./gradlew spotlessApply && git add -u` if needed).

```bash
git add shared/src/main/java/com/meatsack/shared/sync/VoteSyncSerializer.kt shared/src/test/java/com/meatsack/shared/sync/VoteSyncSerializerTest.kt
git commit -m "feat(sync): add VoteSyncSerializer for the /votes channel"
```

---

### Task 2: `/votes` channel constants + DAO vote methods

Additive shared changes the sender and receiver will use. No unit test: `SyncChannel` is constants; the two DAO queries are Room SQL with no pre-commit harness in this project (DAO tests run under `:shared:connectedAndroidTest`, which needs an emulator) — they are exercised by the on-device smoke in Task 6.

**Files:**
- Modify: `shared/src/main/java/com/meatsack/shared/sync/SyncChannel.kt`
- Modify: `shared/src/main/java/com/meatsack/shared/db/MessageDao.kt`

- [ ] **Step 1: Add the channel constants**

In `SyncChannel.kt`, add after `KEY_TIMESTAMP`:

```kotlin
    const val PATH_VOTES = "/votes"
    const val KEY_VOTE_DATA = "vote_data"
```

- [ ] **Step 2: Add the DAO methods**

In `MessageDao.kt`, add (e.g. after the existing `voteDown` query):

```kotlin
    @Query("SELECT * FROM messages WHERE votesUp > 0 OR votesDown > 0")
    suspend fun getVotedMessages(): List<Message>

    @Query("UPDATE messages SET votesUp = :votesUp, votesDown = :votesDown WHERE id = :messageId")
    suspend fun setVotes(messageId: Long, votesUp: Int, votesDown: Int)
```

- [ ] **Step 3: Verify it compiles (Room KSP processes the new queries)**

Run: `./gradlew :shared:compileDebugKotlin`
Expected: BUILD SUCCESSFUL (Room validates the new `@Query` SQL at compile time).

- [ ] **Step 4: Verify formatting & commit**

Run: `./gradlew :shared:spotlessCheck` (fix if needed).

```bash
git add shared/src/main/java/com/meatsack/shared/sync/SyncChannel.kt shared/src/main/java/com/meatsack/shared/db/MessageDao.kt
git commit -m "feat(sync): add /votes channel constants + setVotes/getVotedMessages DAO"
```

---

### Task 3: `WatchVoteSender` (watch side)

Reads the watch's voted rows, serializes them, and writes the `/votes` DataItem. Mirrors `PhoneSyncSender` (sealed result + cancellation-safe catch). New file, not yet called — green on its own.

**Files:**
- Create: `wear/src/main/java/com/meatsack/motivator/sync/WatchVoteSender.kt`

- [ ] **Step 1: Implement**

Create `wear/src/main/java/com/meatsack/motivator/sync/WatchVoteSender.kt`:

```kotlin
package com.meatsack.motivator.sync

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.meatsack.shared.db.AppDatabase
import com.meatsack.shared.sync.SyncChannel
import com.meatsack.shared.sync.VoteSnapshot
import com.meatsack.shared.sync.VoteSyncSerializer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await

/**
 * Outcome of a vote back-sync. Mirrors PhoneSyncSender.SyncResult so callers can
 * distinguish "nothing to send" from a real failure.
 */
sealed class VoteSyncResult {
    data class Success(val count: Int) : VoteSyncResult()
    data object NoVotes : VoteSyncResult()
    data class Failed(val error: Throwable) : VoteSyncResult()
}

/**
 * Pushes the watch's current vote counts to the phone over the /votes Data Layer
 * channel. The reverse of PhoneSyncSender's forward (/messages) pipe. Absolute
 * counts keyed by message id, with a timestamp so every vote propagates.
 */
class WatchVoteSender(private val context: Context) {

    companion object {
        private const val TAG = "WatchVoteSender"
    }

    suspend fun syncVotesToPhone(): VoteSyncResult {
        val snapshots = AppDatabase.getDatabase(context).messageDao().getVotedMessages()
            .map { VoteSnapshot(it.id, it.votesUp, it.votesDown) }

        if (snapshots.isEmpty()) {
            Log.d(TAG, "No votes to sync")
            return VoteSyncResult.NoVotes
        }

        val request = PutDataMapRequest.create(SyncChannel.PATH_VOTES).apply {
            dataMap.putString(SyncChannel.KEY_VOTE_DATA, VoteSyncSerializer.serialize(snapshots))
            dataMap.putLong(SyncChannel.KEY_TIMESTAMP, System.currentTimeMillis())
        }.asPutDataRequest().setUrgent()

        return try {
            Wearable.getDataClient(context).putDataItem(request).await()
            Log.d(TAG, "Synced ${snapshots.size} vote rows to phone")
            VoteSyncResult.Success(snapshots.size)
        } catch (ce: CancellationException) {
            // Don't absorb cancellation — propagating it keeps structured concurrency honest.
            throw ce
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sync votes", e)
            VoteSyncResult.Failed(e)
        }
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :wear:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Verify formatting & commit**

Run: `./gradlew :wear:spotlessCheck` (fix if needed).

```bash
git add wear/src/main/java/com/meatsack/motivator/sync/WatchVoteSender.kt
git commit -m "feat(wear): add WatchVoteSender to push votes back to the phone"
```

---

### Task 4: Fire the back-sync after each vote (`InsultActivity`)

Wire `WatchVoteSender` into the existing `recordVote` so the snapshot is sent *after* the vote write commits (so `getVotedMessages` sees the new count). Framework/UI code — verified on-device, no unit test.

**Files:**
- Modify: `wear/src/main/java/com/meatsack/motivator/presentation/InsultActivity.kt`

- [ ] **Step 1: Add the import**

In `InsultActivity.kt`, add to the imports:

```kotlin
import com.meatsack.motivator.sync.WatchVoteSender
```

- [ ] **Step 2: Construct the sender in `onCreate`**

After the existing `val repo = MessageRepository(db.messageDao())` line, add:

```kotlin
        val voteSender = WatchVoteSender(applicationContext)
```

- [ ] **Step 3: Pass the sender through the vote callbacks**

Change the two `recordVote(...)` calls inside `setContent` to pass `voteSender`:

```kotlin
                onThumbsUp = {
                    recordVote(appScope, voteSender, messageId) { repo.voteUp(it) }
                    finish()
                },
                onThumbsDown = {
                    recordVote(appScope, voteSender, messageId) { repo.voteDown(it) }
                    finish()
                },
```

- [ ] **Step 4: Trigger the sync after the write in `recordVote`**

Replace the existing `recordVote` function with the version that takes the sender and pushes after the write succeeds:

```kotlin
    private inline fun recordVote(
        scope: kotlinx.coroutines.CoroutineScope,
        voteSender: WatchVoteSender,
        messageId: Long,
        crossinline write: suspend (Long) -> Unit,
    ) {
        if (messageId <= 0L) return
        scope.launch {
            try {
                write(messageId)
                // Best-effort back-sync; the vote is already persisted locally and the
                // next vote re-sends the full snapshot, so a failure here is non-fatal.
                voteSender.syncVotesToPhone()
            } catch (t: Throwable) {
                Log.e("InsultActivity", "Vote write failed for id=$messageId", t)
            }
        }
    }
```

- [ ] **Step 5: Verify it compiles & formatting**

Run: `./gradlew :wear:compileDebugKotlin && ./gradlew :wear:spotlessCheck`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add wear/src/main/java/com/meatsack/motivator/presentation/InsultActivity.kt
git commit -m "feat(wear): push votes to phone after each vote in InsultActivity"
```

---

### Task 5: `PhoneVoteReceiver` + manifest registration (phone side)

A new `WearableListenerService` (the phone's first) that applies `/votes` to the phone DB via `setVotes`. Mirrors `WatchSyncReceiver` in reverse. Framework code — verified on-device.

**Files:**
- Create: `mobile/src/main/java/com/meatsack/motivator/mobile/sync/PhoneVoteReceiver.kt`
- Modify: `mobile/src/main/AndroidManifest.xml`

- [ ] **Step 1: Implement the receiver**

Create `mobile/src/main/java/com/meatsack/motivator/mobile/sync/PhoneVoteReceiver.kt`:

```kotlin
package com.meatsack.motivator.mobile.sync

import android.util.Log
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService
import com.meatsack.motivator.mobile.MeatsackMobileApp
import com.meatsack.shared.db.AppDatabase
import com.meatsack.shared.sync.SyncChannel
import com.meatsack.shared.sync.VoteSyncSerializer
import kotlinx.coroutines.launch

/**
 * Applies vote counts pushed from the watch over the /votes channel to the phone
 * Room DB (absolute set, keyed by message id). The reverse of WatchSyncReceiver,
 * which receives the forward (/messages) pipe.
 */
class PhoneVoteReceiver : WearableListenerService() {

    companion object {
        private const val TAG = "PhoneVoteReceiver"

        // Bound the blast radius of a malformed/hostile payload (matches WatchSyncReceiver).
        private const val MAX_INCOMING_VOTES = 500
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        dataEvents.forEach { event ->
            val path = event.dataItem.uri.path
            if (path != SyncChannel.PATH_VOTES) return@forEach
            val sourceNode = event.dataItem.uri.host
            val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
            val serialized = dataMap.getString(SyncChannel.KEY_VOTE_DATA)
            if (serialized == null) {
                Log.w(
                    TAG,
                    "Missing ${SyncChannel.KEY_VOTE_DATA} in ${SyncChannel.PATH_VOTES} from node=$sourceNode",
                )
                return@forEach
            }

            val votes = VoteSyncSerializer.deserialize(serialized)
            if (votes.isEmpty()) {
                Log.w(TAG, "Empty or fully-malformed vote payload from node=$sourceNode")
                return@forEach
            }
            if (votes.size > MAX_INCOMING_VOTES) {
                Log.w(TAG, "Dropping oversized vote sync from node=$sourceNode size=${votes.size}")
                return@forEach
            }

            val scope = (applicationContext as MeatsackMobileApp).applicationScope
            scope.launch {
                try {
                    val dao = AppDatabase.getDatabase(applicationContext).messageDao()
                    votes.forEach { dao.setVotes(it.id, it.votesUp, it.votesDown) }
                    Log.d(TAG, "Applied ${votes.size} vote rows from node=$sourceNode")
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed to apply ${votes.size} vote rows from $sourceNode", t)
                }
            }
        }
    }
}
```

- [ ] **Step 2: Register the service in the manifest**

In `mobile/src/main/AndroidManifest.xml`, inside `<application>` and after the `<activity>` block (before `</application>`), add:

```xml
        <service
            android:name=".sync.PhoneVoteReceiver"
            android:exported="true">
            <intent-filter>
                <action android:name="com.google.android.gms.wearable.DATA_CHANGED" />
                <data
                    android:scheme="wear"
                    android:host="*"
                    android:pathPrefix="/votes" />
            </intent-filter>
        </service>
```

- [ ] **Step 3: Verify it compiles & formatting**

Run: `./gradlew :mobile:compileDebugKotlin && ./gradlew :mobile:spotlessCheck`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add mobile/src/main/java/com/meatsack/motivator/mobile/sync/PhoneVoteReceiver.kt mobile/src/main/AndroidManifest.xml
git commit -m "feat(mobile): receive /votes and apply watch votes to the phone DB"
```

---

### Task 6: Full build + on-device verification + PR

**Files:** none (verification only).

- [ ] **Step 1: Full build + all tests**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL (all modules compile, lint clean, all unit tests incl. `VoteSyncSerializerTest` pass).

- [ ] **Step 2: Install on the paired emulators**

```bash
./gradlew :mobile:assembleDebug :wear:assembleDebug
adb -s <phone-id> install -r mobile/build/outputs/apk/debug/mobile-debug.apk
adb -s <watch-id> install -r wear/build/outputs/apk/debug/wear-debug.apk
adb -s <watch-id> shell pm grant com.meatsack.motivator android.permission.POST_NOTIFICATIONS
```

- [ ] **Step 3: Fire an insult on the watch and vote**

Trigger the full-screen insult (e.g. via the debug `TestFireActivity` if present, or by letting an insult fire), then tap 👍. Watch the sender log:

```bash
adb -s <watch-id> logcat -d -s WatchVoteSender | tail -3
```

Expected: `Synced N vote rows to phone`.

- [ ] **Step 4: Confirm the phone applied the vote**

```bash
adb -s <phone-id> logcat -d -s PhoneVoteReceiver | tail -3
```

Expected: `Applied N vote rows from node=...`. Then open the phone app's **Library** tab and confirm the voted message's `👍/👎` count reflects the watch vote (the list is reactive, so it updates without reopening).

- [ ] **Step 5: Push and open the PR**

```bash
git push -u origin feature/vote-back-sync
gh pr create --fill --base main
```

Then review with `pr-review-toolkit:review-pr` before merging (per project workflow).

---

## Notes for the implementer

- **Entirely additive:** no existing field/method is removed or changed in a breaking way, so every task commits green under the pre-commit hook (which builds + unit-tests all three modules).
- **id-match invariant:** back-sync keys on message `id`, valid only because every message is born on the phone (identical seed order → matching ids; AI/custom created on phone then synced down). Do not add watch-local message creation.
- **Absolute counts, not deltas:** `setVotes` overwrites with the watch's current counts — idempotent under Data Layer redelivery. A delta scheme would double-count.
- **Eventual consistency:** the manual "Sync to Watch" (`/messages`) still carries the phone's vote counts; back-sync keeps them current. The sub-second vote-then-sync race self-heals on the next vote. (Accepted per spec.)
- **Trigger scope:** only `InsultActivity` votes fire the back-sync. The orphaned `VoteReceiver` (dead notification-action handler) is intentionally not wired.
- **Do not commit to `main`** — work stays on `feature/vote-back-sync`; PR into `main`.
