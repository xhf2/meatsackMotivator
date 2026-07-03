# Vote-aware, multi-level insult generation + library retention — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make "Generate" produce vote-steered insults across all four escalation levels, and keep the library from growing unbounded.

**Architecture:** A pure `LibraryPruner` and two new DAO queries do the vote/retention math; `Prompts` gains loved/avoid blocks; `AiMessageGenerator` is refactored behind a `GenerationStore` interface so its new 4-call multi-level loop (with prune + partial-failure aggregation) is unit-testable with fakes.

**Tech Stack:** Kotlin, Room, Anthropic Java SDK, JUnit4, kotlinx-coroutines-test, Compose (Material3).

## Global Constraints

- Work on branch `feature/vote-aware-generation`; never commit to `main`.
- `JAVA_HOME` = `/c/Program Files/Android/Android Studio/jbr` for all Gradle commands.
- Formatting gate: run `./gradlew spotlessApply` before each commit (pre-commit hook also runs `spotlessCheck` + unit tests).
- Message text hard limit: `MessageLimits.MAX_MESSAGE_TEXT_LENGTH = 100`; generated lines must also contain no `|` and no newline (existing sync-wire constraint).
- Vote partition (used everywhere): loved = `votesUp > votesDown`; hated = `votesDown > votesUp`; neutral = equal (incl. 0/0), ignored.
- Retention thresholds (hardcoded, v1): `INSULTS_PER_LEVEL=5`, `LOVED_EXAMPLES=5`, `HATED_EXAMPLES=3`, `BUCKET_CAP=50`, `BUCKET_FLOOR=5`.
- Generation varies **only** level; `trigger` stays `INACTIVITY`, `tone` stays `FULL_SEND`.

---

## File Structure

- Create `shared/src/main/java/com/meatsack/shared/constants/GenerationLimits.kt` — tunable constants.
- Create `shared/src/main/java/com/meatsack/shared/retention/LibraryPruner.kt` — pure prune-selection.
- Create `shared/src/test/java/com/meatsack/shared/retention/LibraryPrunerTest.kt` — unit tests.
- Modify `shared/src/main/java/com/meatsack/shared/db/MessageDao.kt` — add `getLovedTexts`, `getHatedTexts`, `deleteByIds`; remove `getTopUpvotedTexts` (Task 5).
- Modify `shared/src/androidTest/java/com/meatsack/shared/db/MessageDaoTest.kt` — cover the new queries.
- Modify `mobile/src/main/java/com/meatsack/motivator/mobile/ai/Prompts.kt` — loved/avoid blocks.
- Modify `mobile/src/test/java/com/meatsack/motivator/mobile/ai/PromptsTest.kt` — new signature + block tests.
- Create `mobile/src/main/java/com/meatsack/motivator/mobile/ai/GenerationStore.kt` — interface + Room impl.
- Modify `mobile/src/main/java/com/meatsack/motivator/mobile/ai/ClaudeApiClient.kt` — add `InsultClient` seam.
- Modify `mobile/src/main/java/com/meatsack/motivator/mobile/ai/AiMessageGenerator.kt` — DI + `generateAcrossLevels`.
- Create `mobile/src/test/java/com/meatsack/motivator/mobile/ai/AiMessageGeneratorTest.kt` — loop tests with fakes.
- Modify `mobile/src/main/java/com/meatsack/motivator/mobile/ui/settings/SettingsViewModel.kt` — call `generateAcrossLevels`.
- Modify `mobile/src/main/java/com/meatsack/motivator/mobile/ui/settings/SettingsScreen.kt` — button label.

---

## Task 1: Generation/retention constants

**Files:**
- Create: `shared/src/main/java/com/meatsack/shared/constants/GenerationLimits.kt`

**Interfaces:**
- Produces: `object GenerationLimits { INSULTS_PER_LEVEL, LOVED_EXAMPLES, HATED_EXAMPLES, BUCKET_CAP, BUCKET_FLOOR: Int }`

- [ ] **Step 1: Create the constants file**

```kotlin
package com.meatsack.shared.constants

/**
 * Tunables for vote-aware, multi-level insult generation and library retention.
 * Hardcoded for v1 (see docs/superpowers/specs/2026-07-03-vote-aware-generation-design.md);
 * promote to Settings later if they need per-user tuning.
 */
object GenerationLimits {
    /** Insults requested per level, per "Generate" press (4 levels => 20 total). */
    const val INSULTS_PER_LEVEL = 5

    /** Max positive (loved) style exemplars injected into a prompt. */
    const val LOVED_EXAMPLES = 5

    /** Max negative (hated) "avoid this voice" exemplars injected into a prompt. */
    const val HATED_EXAMPLES = 3

    /** Soft max messages kept per (level, tone, trigger) bucket; surplus non-loved rows are pruned. */
    const val BUCKET_CAP = 50

    /** Min fireable (votesDown < 3) rows kept per bucket; pruning never drops below this. */
    const val BUCKET_FLOOR = 5
}
```

- [ ] **Step 2: Compile shared**

Run: `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :shared:compileDebugKotlin --console=plain`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add shared/src/main/java/com/meatsack/shared/constants/GenerationLimits.kt
git commit -m "feat(shared): add GenerationLimits constants for vote-aware generation"
```

---

## Task 2: LibraryPruner (pure prune-selection)

**Files:**
- Create: `shared/src/main/java/com/meatsack/shared/retention/LibraryPruner.kt`
- Test: `shared/src/test/java/com/meatsack/shared/retention/LibraryPrunerTest.kt`

**Interfaces:**
- Consumes: `com.meatsack.shared.model.Message` (fields: `id, level, triggerType, tone, votesUp, votesDown`).
- Produces: `object LibraryPruner { fun selectForDeletion(messages: List<Message>, cap: Int, floor: Int): List<Long> }` — returns ids to hard-delete.

- [ ] **Step 1: Write the failing tests**

Create `shared/src/test/java/com/meatsack/shared/retention/LibraryPrunerTest.kt`:

```kotlin
package com.meatsack.shared.retention

import com.meatsack.shared.constants.EscalationLevel
import com.meatsack.shared.constants.MessageSource
import com.meatsack.shared.constants.MessageTone
import com.meatsack.shared.constants.TriggerType
import com.meatsack.shared.model.Message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryPrunerTest {

    // Helper: a message in the default bucket (SAVAGE/FULL_SEND/INACTIVITY) unless overridden.
    private fun msg(
        id: Long,
        up: Int = 0,
        down: Int = 0,
        source: MessageSource = MessageSource.AI_GENERATED,
        level: EscalationLevel = EscalationLevel.SAVAGE,
        tone: MessageTone = MessageTone.FULL_SEND,
        trigger: TriggerType = TriggerType.INACTIVITY,
    ) = Message(
        id = id, text = "m$id", level = level, triggerType = trigger, tone = tone,
        source = source, votesUp = up, votesDown = down, lastShownTimestamp = 0, isActive = true,
    )

    @Test fun deletesRejectedNonLovedRows() {
        // votesDown >= 3 and not loved -> dead weight, deleted. Padding keeps bucket above the floor.
        val padding = (1L..5L).map { msg(it) } // 5 fireable 0/0 rows hold the floor
        val rejected = msg(99, up = 0, down = 3)
        val ids = LibraryPruner.selectForDeletion(padding + rejected, cap = 50, floor = 5)
        assertEquals(listOf(99L), ids)
    }

    @Test fun neverDeletesLovedEvenIfHeavilyDownvoted() {
        // Loved = votesUp > votesDown; permanent even with votesDown >= 3.
        val padding = (1L..5L).map { msg(it) }
        val lovedButDownvoted = msg(99, up = 5, down = 3) // loved (5 > 3)
        val ids = LibraryPruner.selectForDeletion(padding + lovedButDownvoted, cap = 50, floor = 5)
        assertFalse(99L in ids)
    }

    @Test fun deletesSurplusLowestNetDownToCap() {
        // 4 rows, cap 2, floor 0 => delete the 2 lowest-net non-loved.
        val rows = listOf(
            msg(1, up = 9), msg(2, up = 5), msg(3, up = 1), msg(4, up = 0),
        )
        val ids = LibraryPruner.selectForDeletion(rows, cap = 2, floor = 0).sorted()
        assertEquals(listOf(3L, 4L), ids)
    }

    @Test fun floorGuardKeepsFireableRowsEvenOverCap() {
        // 6 fireable 0/0 rows, cap 2, floor 5 => surplus wants to delete 4, but floor restores
        // the 2 highest-net so 5 fireable survive; net-tie => exactly one deleted.
        val rows = (1L..6L).map { msg(it) }
        val ids = LibraryPruner.selectForDeletion(rows, cap = 2, floor = 5)
        assertEquals(1, ids.size)
    }

    @Test fun rejectedBucketFullyDeletedDespiteFloor() {
        // Floor only protects fireable (votesDown < 3) rows. An all-rejected bucket has no
        // fireable rows to protect, so all are deleted (they could not fire anyway).
        val rows = (1L..5L).map { msg(it, up = 0, down = 3) }
        val ids = LibraryPruner.selectForDeletion(rows, cap = 50, floor = 5).sorted()
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), ids)
    }

    @Test fun bucketsAreIsolated() {
        // A surplus in one bucket must not delete rows from another bucket.
        val bucketA = (1L..4L).map { msg(it, level = EscalationLevel.SAVAGE) }
        val bucketB = (10L..11L).map { msg(it, level = EscalationLevel.NUCLEAR) }
        val ids = LibraryPruner.selectForDeletion(bucketA + bucketB, cap = 2, floor = 0)
        assertTrue(ids.all { it < 10L }) // only bucket A trimmed
        assertEquals(2, ids.size)
    }

    @Test fun seedsArePrunableLikeAnyOtherRow() {
        val seeds = (1L..3L).map { msg(it, source = MessageSource.PRE_WRITTEN) }
        val ids = LibraryPruner.selectForDeletion(seeds, cap = 1, floor = 0).sorted()
        assertEquals(listOf(2L, 3L), ids) // 2 lowest-net seeds pruned down to cap 1
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :shared:testDebugUnitTest --tests "com.meatsack.shared.retention.LibraryPrunerTest" --console=plain`
Expected: FAIL — `Unresolved reference: LibraryPruner`.

- [ ] **Step 3: Implement LibraryPruner**

Create `shared/src/main/java/com/meatsack/shared/retention/LibraryPruner.kt`:

```kotlin
package com.meatsack.shared.retention

import com.meatsack.shared.model.Message

/**
 * Pure selection of which messages to hard-delete during the retention sweep. No Android / DB
 * dependencies so it is fully unit-testable. See
 * docs/superpowers/specs/2026-07-03-vote-aware-generation-design.md (item E).
 *
 * Per (level, tone, trigger) bucket:
 *  - loved (votesUp > votesDown) rows are permanent (never returned).
 *  - non-loved rows with votesDown >= 3 (rejected) are always deleted.
 *  - when the bucket exceeds [cap], the lowest-net non-loved rows are deleted down to the cap.
 *  - the floor guard then restores the highest-net *fireable* (votesDown < 3) marked rows so at
 *    least [floor] fireable rows survive. Rejected rows are not fireable, so they are never
 *    restored — which is why an all-rejected bucket empties (it could not fire anyway).
 */
object LibraryPruner {

    private data class Bucket(val level: Any, val tone: Any, val trigger: Any)

    fun selectForDeletion(messages: List<Message>, cap: Int, floor: Int): List<Long> {
        val toDelete = mutableListOf<Long>()

        val byBucket = messages.groupBy { Bucket(it.level, it.tone, it.triggerType) }
        for ((_, bucket) in byBucket) {
            val loved = bucket.filter { it.votesUp > it.votesDown }.map { it.id }.toSet()
            val marked = LinkedHashSet<Long>()

            // Rule: rejected non-loved rows.
            bucket.filter { it.id !in loved && it.votesDown >= 3 }.forEach { marked += it.id }

            // Rule: surplus over cap — delete lowest-net non-loved until bucket size <= cap.
            var remaining = bucket.size - marked.size
            if (remaining > cap) {
                val surplus = remaining - cap
                bucket.asSequence()
                    .filter { it.id !in loved && it.id !in marked }
                    .sortedBy { it.votesUp - it.votesDown } // lowest net first
                    .take(surplus)
                    .forEach { marked += it.id }
            }

            // Floor guard: keep >= floor fireable (votesDown < 3) survivors.
            val fireableSurvivors = bucket.count { it.id !in marked && it.votesDown < 3 }
            if (fireableSurvivors < floor) {
                val needed = floor - fireableSurvivors
                bucket.asSequence()
                    .filter { it.id in marked && it.votesDown < 3 }
                    .sortedByDescending { it.votesUp - it.votesDown } // keep best first
                    .take(needed)
                    .forEach { marked -= it.id }
            }

            toDelete += marked
        }
        return toDelete
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :shared:testDebugUnitTest --tests "com.meatsack.shared.retention.LibraryPrunerTest" --console=plain`
Expected: `BUILD SUCCESSFUL` (7 tests pass).

- [ ] **Step 5: Format and commit**

```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew spotlessApply --console=plain
git add shared/src/main/java/com/meatsack/shared/retention/LibraryPruner.kt shared/src/test/java/com/meatsack/shared/retention/LibraryPrunerTest.kt
git commit -m "feat(shared): LibraryPruner pure prune-selection with cap+floor per bucket"
```

---

## Task 3: DAO loved/hated/delete queries

**Files:**
- Modify: `shared/src/main/java/com/meatsack/shared/db/MessageDao.kt`
- Test: `shared/src/androidTest/java/com/meatsack/shared/db/MessageDaoTest.kt`

**Interfaces:**
- Produces on `MessageDao`:
  - `suspend fun getLovedTexts(limit: Int): List<String>`
  - `suspend fun getHatedTexts(limit: Int): List<String>`
  - `suspend fun deleteByIds(ids: List<Long>)`

- [ ] **Step 1: Add the three queries to MessageDao**

In `MessageDao.kt`, add these members inside the interface (leave `getTopUpvotedTexts` in place for now — its caller is removed in Task 5):

```kotlin
    @Query(
        """
        SELECT text FROM messages
        WHERE isActive = 1 AND votesUp > votesDown
        ORDER BY (votesUp - votesDown) DESC
        LIMIT :limit
    """,
    )
    suspend fun getLovedTexts(limit: Int): List<String>

    @Query(
        """
        SELECT text FROM messages
        WHERE isActive = 1 AND votesDown > votesUp
        ORDER BY (votesUp - votesDown) ASC
        LIMIT :limit
    """,
    )
    suspend fun getHatedTexts(limit: Int): List<String>

    @Query("DELETE FROM messages WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)
```

- [ ] **Step 2: Write the failing androidTest**

Append to `MessageDaoTest.kt` (uses the same in-memory DB setup already present in that file — reuse its `dao`/`db` fixture and any existing `message(...)` helper; if the helper differs, construct `Message(...)` inline with `source = MessageSource.AI_GENERATED`):

```kotlin
    @Test fun getLovedTexts_returnsOnlyNetPositive_orderedByNetDesc() = runBlocking {
        dao.insertAll(
            listOf(
                Message(text = "loved-big", level = EscalationLevel.SAVAGE, triggerType = TriggerType.INACTIVITY, tone = MessageTone.FULL_SEND, source = MessageSource.AI_GENERATED, votesUp = 5, votesDown = 0),
                Message(text = "loved-small", level = EscalationLevel.SAVAGE, triggerType = TriggerType.INACTIVITY, tone = MessageTone.FULL_SEND, source = MessageSource.AI_GENERATED, votesUp = 2, votesDown = 1),
                Message(text = "neutral", level = EscalationLevel.SAVAGE, triggerType = TriggerType.INACTIVITY, tone = MessageTone.FULL_SEND, source = MessageSource.AI_GENERATED, votesUp = 0, votesDown = 0),
                Message(text = "hated", level = EscalationLevel.SAVAGE, triggerType = TriggerType.INACTIVITY, tone = MessageTone.FULL_SEND, source = MessageSource.AI_GENERATED, votesUp = 0, votesDown = 2),
            ),
        )
        assertEquals(listOf("loved-big", "loved-small"), dao.getLovedTexts(5))
    }

    @Test fun getHatedTexts_returnsOnlyNetNegative_mostNegativeFirst() = runBlocking {
        dao.insertAll(
            listOf(
                Message(text = "hated-worst", level = EscalationLevel.SAVAGE, triggerType = TriggerType.INACTIVITY, tone = MessageTone.FULL_SEND, source = MessageSource.AI_GENERATED, votesUp = 0, votesDown = 5),
                Message(text = "hated-mild", level = EscalationLevel.SAVAGE, triggerType = TriggerType.INACTIVITY, tone = MessageTone.FULL_SEND, source = MessageSource.AI_GENERATED, votesUp = 1, votesDown = 2),
                Message(text = "neutral", level = EscalationLevel.SAVAGE, triggerType = TriggerType.INACTIVITY, tone = MessageTone.FULL_SEND, source = MessageSource.AI_GENERATED, votesUp = 0, votesDown = 0),
            ),
        )
        assertEquals(listOf("hated-worst", "hated-mild"), dao.getHatedTexts(5))
    }

    @Test fun deleteByIds_removesExactlyThoseRows() = runBlocking {
        dao.insertAll(
            listOf(
                Message(id = 1, text = "keep", level = EscalationLevel.SAVAGE, triggerType = TriggerType.INACTIVITY, tone = MessageTone.FULL_SEND, source = MessageSource.AI_GENERATED),
                Message(id = 2, text = "drop", level = EscalationLevel.SAVAGE, triggerType = TriggerType.INACTIVITY, tone = MessageTone.FULL_SEND, source = MessageSource.AI_GENERATED),
            ),
        )
        dao.deleteByIds(listOf(2L))
        assertEquals(listOf("keep"), dao.getAllMessages().map { it.text })
    }
```

Ensure imports exist in the test file: `com.meatsack.shared.constants.MessageSource`, `EscalationLevel`, `MessageTone`, `TriggerType`, `com.meatsack.shared.model.Message`, `org.junit.Assert.assertEquals`, `kotlinx.coroutines.runBlocking`.

- [ ] **Step 3: Run the androidTest to verify it fails, then passes (needs the Wear/phone emulator running)**

Run: `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :shared:connectedDebugAndroidTest --console=plain`
Expected first run BEFORE Step 1 would fail to compile; after Step 1 it should PASS. (If no emulator is available, note it and rely on `:shared:compileDebugKotlin` + `:shared:compileDebugAndroidTestKotlin` to prove it compiles; the query behavior is also indirectly covered by the SQL being trivial.)

- [ ] **Step 4: Compile check**

Run: `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :shared:compileDebugKotlin :shared:compileDebugAndroidTestKotlin --console=plain`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Format and commit**

```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew spotlessApply --console=plain
git add shared/src/main/java/com/meatsack/shared/db/MessageDao.kt shared/src/androidTest/java/com/meatsack/shared/db/MessageDaoTest.kt
git commit -m "feat(shared): DAO getLovedTexts/getHatedTexts/deleteByIds"
```

---

## Task 4: Vote-aware prompt (loved + avoid blocks)

**Files:**
- Modify: `mobile/src/main/java/com/meatsack/motivator/mobile/ai/Prompts.kt`
- Test: `mobile/src/test/java/com/meatsack/motivator/mobile/ai/PromptsTest.kt`

**Interfaces:**
- Produces: `Prompts.buildUserPrompt(currentSteps: Int, hourOfDay: Int, level: EscalationLevel, trigger: TriggerType, tone: MessageTone, loved: List<String>, hated: List<String>, count: Int = 10): String`

- [ ] **Step 1: Replace PromptsTest with the new signature + block tests**

Replace the body of `PromptsTest.kt` with:

```kotlin
package com.meatsack.motivator.mobile.ai

import com.meatsack.shared.constants.EscalationLevel
import com.meatsack.shared.constants.MessageTone
import com.meatsack.shared.constants.TriggerType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptsTest {

    private fun prompt(
        loved: List<String> = emptyList(),
        hated: List<String> = emptyList(),
        level: EscalationLevel = EscalationLevel.SAVAGE,
        tone: MessageTone = MessageTone.FULL_SEND,
        trigger: TriggerType = TriggerType.INACTIVITY,
    ) = Prompts.buildUserPrompt(
        currentSteps = 500, hourOfDay = 14, level = level, trigger = trigger, tone = tone,
        loved = loved, hated = hated,
    )

    @Test fun includesLovedExamplesUnderMatchThisVoice() {
        val p = prompt(loved = listOf("GET UP.", "Your chair knows you."))
        assertTrue(p.contains("match this voice"))
        assertTrue(p.contains("GET UP."))
        assertTrue(p.contains("Your chair knows you."))
    }

    @Test fun includesHatedExamplesUnderAvoidBlock() {
        val p = prompt(loved = listOf("good one"), hated = listOf("weak sauce"))
        assertTrue(p.contains("do NOT write"))
        assertTrue(p.contains("weak sauce"))
    }

    @Test fun omitsAvoidBlockWhenNoHated() {
        val p = prompt(loved = listOf("good one"), hated = emptyList())
        assertFalse(p.contains("do NOT write"))
    }

    @Test fun noLoved_usesHonestFallbackNotFakePraise() {
        val p = prompt(loved = emptyList(), hated = emptyList())
        assertTrue(p.contains("hasn't rated any favorites yet"))
        assertFalse(p.contains("match this voice"))
    }

    @Test fun levelLineReflectsRequestedLevel() {
        val p = prompt(level = EscalationLevel.EXISTENTIAL)
        assertTrue(p.contains("EXISTENTIAL"))
    }

    @Test fun workSafeToneChangesLanguageLine() {
        val p = prompt(tone = MessageTone.WORK_SAFE)
        assertTrue(p.contains("Keep it clean"))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :mobile:testDebugUnitTest --tests "com.meatsack.motivator.mobile.ai.PromptsTest" --console=plain`
Expected: FAIL — compile error (`buildUserPrompt` has no `loved`/`hated` params).

- [ ] **Step 3: Update Prompts.buildUserPrompt**

In `Prompts.kt`, replace the `buildUserPrompt` function with:

```kotlin
    fun buildUserPrompt(
        currentSteps: Int,
        hourOfDay: Int,
        level: EscalationLevel,
        trigger: TriggerType,
        tone: MessageTone,
        loved: List<String>,
        hated: List<String>,
        count: Int = 10,
    ): String {
        val toneLine = when (tone) {
            MessageTone.FULL_SEND -> "Full send. Swear. Be brutal."
            MessageTone.WORK_SAFE -> "Keep it clean. No profanity. Still savage."
        }
        val triggerLine = when (trigger) {
            TriggerType.INACTIVITY -> "They have been sitting still too long."
            TriggerType.BEHIND_PACE -> "They are behind their step pace for the day."
            TriggerType.END_OF_DAY -> "The day is ending and they missed the goal."
            TriggerType.NO_WORKOUT -> "They didn't work out today."
        }
        val timeLabel = if (hourOfDay < 12) "${hourOfDay}am" else "${hourOfDay - 12}pm"

        val lovedBlock = if (loved.isNotEmpty()) {
            "Here are messages this user loved — match this voice:\n" +
                loved.take(5).joinToString("\n") { "- $it" }
        } else {
            "The user hasn't rated any favorites yet — establish a strong, consistent signature voice."
        }
        val avoidBlock = if (hated.isNotEmpty()) {
            "\n\nThe user disliked these — do NOT write anything in this voice:\n" +
                hated.take(3).joinToString("\n") { "- $it" }
        } else {
            ""
        }

        return """
            Generate $count motivational insults. Each must be 1–2 sentences AND at most 100 characters total — strict limit, never exceed.
            Style: David Goggins as an angry, disgusted drill sergeant.
            $triggerLine Steps so far: $currentSteps. Time: $timeLabel.
            Level: ${level.name} — crank the venom accordingly.
            Tone: $toneLine
            Use creative medical/anatomical compound insults like 'sarcopenic motherfucker',
            'osteopenic jello mold', 'arthritic waste of a skeleton', 'osteoporotic coward'.
            Never use 'fat' as an insult.

            $lovedBlock$avoidBlock

            Return ONE message per line. No numbering, no bullets, no quotes.
        """.trimIndent()
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :mobile:testDebugUnitTest --tests "com.meatsack.motivator.mobile.ai.PromptsTest" --console=plain`
Expected: `BUILD SUCCESSFUL` (6 tests). NOTE: `:mobile:compileDebugKotlin` will still fail here because `AiMessageGenerator` calls the old signature — that is fixed in Task 5. Run only the `PromptsTest` filter for this step.

- [ ] **Step 5: Format and commit**

```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew spotlessApply --console=plain
git add mobile/src/main/java/com/meatsack/motivator/mobile/ai/Prompts.kt mobile/src/test/java/com/meatsack/motivator/mobile/ai/PromptsTest.kt
git commit -m "feat(mobile): vote-aware prompt with loved + avoid blocks and honest fallback"
```

---

## Task 5: GenerationStore + AiMessageGenerator multi-level loop

**Files:**
- Create: `mobile/src/main/java/com/meatsack/motivator/mobile/ai/GenerationStore.kt`
- Modify: `mobile/src/main/java/com/meatsack/motivator/mobile/ai/ClaudeApiClient.kt` (add `InsultClient` seam)
- Modify: `mobile/src/main/java/com/meatsack/motivator/mobile/ai/AiMessageGenerator.kt`
- Modify: `shared/src/main/java/com/meatsack/shared/db/MessageDao.kt` (remove `getTopUpvotedTexts`)
- Test: `mobile/src/test/java/com/meatsack/motivator/mobile/ai/AiMessageGeneratorTest.kt`

**Interfaces:**
- Consumes: `Prompts.buildUserPrompt(... loved, hated ...)` (Task 4); `GenerationLimits` (Task 1); `LibraryPruner.selectForDeletion` (Task 2); `MessageDao.getLovedTexts/getHatedTexts/getAllMessages/insertAll/deleteByIds` (Task 3); `PhoneSyncSender.syncMessagesToWatch(): SyncResult`.
- Produces:
  - `interface GenerationStore { suspend getLovedTexts(Int): List<String>; suspend getHatedTexts(Int): List<String>; suspend getAllMessages(): List<Message>; suspend insertAll(List<Message>); suspend deleteByIds(List<Long>) }`
  - `class RoomGenerationStore(dao: MessageDao) : GenerationStore`
  - `interface InsultClient { suspend generate(systemPrompt: String, userPrompt: String): GenerationResult }` implemented by `ClaudeApiClient`
  - `AiMessageGenerator(store: GenerationStore, client: InsultClient, sync: suspend () -> SyncResult)` with `companion object { fun create(context: Context): AiMessageGenerator }`
  - `suspend fun AiMessageGenerator.generateAcrossLevels(trigger: TriggerType, tone: MessageTone, hourOfDay: Int, currentSteps: Int): GenerationResult`

- [ ] **Step 1: Write the failing loop tests (test-first)**

These reference `GenerationStore`, `InsultClient`, the injected `AiMessageGenerator(store, client, sync)` constructor, and `generateAcrossLevels` — none of which exist yet, so the file will not compile. That is the expected RED state.

Create `mobile/src/test/java/com/meatsack/motivator/mobile/ai/AiMessageGeneratorTest.kt`:

```kotlin
package com.meatsack.motivator.mobile.ai

import com.meatsack.motivator.mobile.sync.SyncResult
import com.meatsack.shared.constants.EscalationLevel
import com.meatsack.shared.constants.MessageTone
import com.meatsack.shared.constants.TriggerType
import com.meatsack.shared.model.Message
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiMessageGeneratorTest {

    private class FakeStore(
        val loved: List<String> = emptyList(),
        val hated: List<String> = emptyList(),
        var all: List<Message> = emptyList(),
    ) : GenerationStore {
        val inserted = mutableListOf<Message>()
        val deleted = mutableListOf<Long>()
        override suspend fun getLovedTexts(limit: Int) = loved.take(limit)
        override suspend fun getHatedTexts(limit: Int) = hated.take(limit)
        override suspend fun getAllMessages() = all
        override suspend fun insertAll(messages: List<Message>) { inserted += messages; all = all + messages }
        override suspend fun deleteByIds(ids: List<Long>) { deleted += ids }
    }

    // Implements the InsultClient seam (Step 3) — no Anthropic SDK / key needed.
    private class FakeClient(private val results: ArrayDeque<GenerationResult>) : InsultClient {
        val prompts = mutableListOf<String>()
        override suspend fun generate(systemPrompt: String, userPrompt: String): GenerationResult {
            prompts += userPrompt
            return results.removeFirst()
        }
    }

    private fun gen(store: GenerationStore, client: InsultClient, sync: suspend () -> SyncResult = { SyncResult.Success(0) }) =
        AiMessageGenerator(store, client, sync)

    @Test fun generatesFiveInsultsForEachOfFourLevels() = runTest {
        val store = FakeStore()
        val client = FakeClient(ArrayDeque(List(4) { GenerationResult.Success(List(5) { i -> "line$i" }) }))
        var syncs = 0
        val result = gen(store, client, sync = { syncs++; SyncResult.Success(20) })
            .generateAcrossLevels(TriggerType.INACTIVITY, MessageTone.FULL_SEND, hourOfDay = 9, currentSteps = 0)

        assertTrue(result is GenerationResult.Success)
        assertEquals(20, store.inserted.size)
        assertEquals(
            setOf(EscalationLevel.AGGRESSIVE, EscalationLevel.SAVAGE, EscalationLevel.NUCLEAR, EscalationLevel.EXISTENTIAL),
            store.inserted.map { it.level }.toSet(),
        )
        assertEquals(1, syncs) // synced once, not per level
    }

    @Test fun partialFailureStillInsertsSucceededLevels() = runTest {
        val store = FakeStore()
        val client = FakeClient(
            ArrayDeque(
                listOf(
                    GenerationResult.Success(List(5) { "ok" }),
                    GenerationResult.Failed(RuntimeException("network")),
                    GenerationResult.Success(List(5) { "ok" }),
                    GenerationResult.HttpError(429, "rate"),
                ),
            ),
        )
        val result = gen(store, client).generateAcrossLevels(TriggerType.INACTIVITY, MessageTone.FULL_SEND, 9, 0)
        assertTrue(result is GenerationResult.Success)
        assertEquals(10, store.inserted.size) // 2 levels x 5
    }

    @Test fun noApiKeyShortCircuitsWithoutInserting() = runTest {
        val store = FakeStore()
        val client = FakeClient(ArrayDeque(listOf<GenerationResult>(GenerationResult.NoApiKey)))
        val result = gen(store, client).generateAcrossLevels(TriggerType.INACTIVITY, MessageTone.FULL_SEND, 9, 0)
        assertTrue(result is GenerationResult.NoApiKey)
        assertEquals(0, store.inserted.size)
    }

    @Test fun filtersOverLongAndPipeAndNewlineLines() = runTest {
        val store = FakeStore()
        val bad = "x".repeat(101)
        val client = FakeClient(ArrayDeque(List(4) { GenerationResult.Success(listOf("good", bad, "a|b")) }))
        gen(store, client).generateAcrossLevels(TriggerType.INACTIVITY, MessageTone.FULL_SEND, 9, 0)
        assertTrue(store.inserted.all { it.text == "good" }) // only the valid line survives per level
        assertEquals(4, store.inserted.size)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail (compile)**

Run: `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :mobile:compileDebugUnitTestKotlin --console=plain`
Expected: FAIL — `Unresolved reference: GenerationStore` / `InsultClient` / `generateAcrossLevels`.

- [ ] **Step 3: Create GenerationStore (interface + Room impl)**

Create `mobile/src/main/java/com/meatsack/motivator/mobile/ai/GenerationStore.kt`:

```kotlin
package com.meatsack.motivator.mobile.ai

import com.meatsack.shared.db.MessageDao
import com.meatsack.shared.model.Message

/**
 * Narrow persistence surface the generator needs, so its multi-level loop can be unit-tested
 * with a fake instead of a real Room database.
 */
interface GenerationStore {
    suspend fun getLovedTexts(limit: Int): List<String>
    suspend fun getHatedTexts(limit: Int): List<String>
    suspend fun getAllMessages(): List<Message>
    suspend fun insertAll(messages: List<Message>)
    suspend fun deleteByIds(ids: List<Long>)
}

/** Production implementation backed by the Room [MessageDao]. */
class RoomGenerationStore(private val dao: MessageDao) : GenerationStore {
    override suspend fun getLovedTexts(limit: Int) = dao.getLovedTexts(limit)
    override suspend fun getHatedTexts(limit: Int) = dao.getHatedTexts(limit)
    override suspend fun getAllMessages() = dao.getAllMessages()
    override suspend fun insertAll(messages: List<Message>) = dao.insertAll(messages)
    override suspend fun deleteByIds(ids: List<Long>) = dao.deleteByIds(ids)
}
```

- [ ] **Step 4: Add the `InsultClient` seam to ClaudeApiClient**

`ApiKeyStore` wraps `EncryptedSharedPreferences` (needs Android context), so the generator must depend on an interface, not the concrete client. In `ClaudeApiClient.kt`, add above the class:

```kotlin
/** Seam so the generator can be unit-tested without the Anthropic SDK / a real key. */
interface InsultClient {
    suspend fun generate(systemPrompt: String, userPrompt: String): GenerationResult
}
```

Make the class implement it and collapse the two defaulted params into locals (no caller passes them):

- Change the declaration to `class ClaudeApiClient(private val apiKeyStore: ApiKeyStore) : InsultClient {`
- Change the function to:

```kotlin
    override suspend fun generate(systemPrompt: String, userPrompt: String): GenerationResult {
        val model = "claude-haiku-4-5-20251001"
        val maxTokens = 1024
        val key = apiKeyStore.read() ?: return GenerationResult.NoApiKey
        // ... rest of the existing body unchanged ...
    }
```

- [ ] **Step 5: Replace AiMessageGenerator with the injected multi-level loop, and drop `getTopUpvotedTexts`**

Replace `AiMessageGenerator.kt` entirely with:

```kotlin
package com.meatsack.motivator.mobile.ai

import android.content.Context
import android.util.Log
import com.meatsack.motivator.mobile.sync.PhoneSyncSender
import com.meatsack.motivator.mobile.sync.SyncResult
import com.meatsack.shared.constants.EscalationLevel
import com.meatsack.shared.constants.GenerationLimits
import com.meatsack.shared.constants.MessageLimits
import com.meatsack.shared.constants.MessageSource
import com.meatsack.shared.constants.MessageTone
import com.meatsack.shared.constants.TriggerType
import com.meatsack.shared.db.AppDatabase
import com.meatsack.shared.model.Message
import com.meatsack.shared.retention.LibraryPruner

/**
 * Orchestrates a "Generate" press: one Claude call per escalation level (vote-steered prompt),
 * insert, prune the library to keep buckets bounded, and sync to the watch once. Dependencies are
 * injected so the loop is unit-testable; [create] wires the production Room + Anthropic + sync.
 */
class AiMessageGenerator(
    private val store: GenerationStore,
    private val client: InsultClient,
    private val sync: suspend () -> SyncResult,
) {

    suspend fun generateAcrossLevels(
        trigger: TriggerType,
        tone: MessageTone,
        hourOfDay: Int,
        currentSteps: Int,
    ): GenerationResult {
        val loved = store.getLovedTexts(GenerationLimits.LOVED_EXAMPLES)
        val hated = store.getHatedTexts(GenerationLimits.HATED_EXAMPLES)

        val accumulated = mutableListOf<Message>()
        var firstFailure: GenerationResult? = null

        for (level in EscalationLevel.entries) {
            val prompt = Prompts.buildUserPrompt(
                currentSteps, hourOfDay, level, trigger, tone,
                loved = loved, hated = hated, count = GenerationLimits.INSULTS_PER_LEVEL,
            )
            when (val result = client.generate(Prompts.SYSTEM_PROMPT, prompt)) {
                is GenerationResult.Success -> {
                    val valid = result.messages.filter {
                        it.length <= MessageLimits.MAX_MESSAGE_TEXT_LENGTH &&
                            !it.contains('|') && !it.contains('\n')
                    }
                    accumulated += valid.map {
                        Message(
                            text = it, level = level, triggerType = trigger, tone = tone,
                            source = MessageSource.AI_GENERATED, lastShownTimestamp = 0, isActive = true,
                        )
                    }
                }
                GenerationResult.NoApiKey -> return GenerationResult.NoApiKey
                is GenerationResult.HttpError -> if (firstFailure == null) firstFailure = result
                is GenerationResult.Failed -> if (firstFailure == null) firstFailure = result
            }
        }

        if (accumulated.isEmpty()) {
            return firstFailure ?: GenerationResult.Success(emptyList())
        }

        store.insertAll(accumulated)

        val toDelete = LibraryPruner.selectForDeletion(
            store.getAllMessages(), GenerationLimits.BUCKET_CAP, GenerationLimits.BUCKET_FLOOR,
        )
        if (toDelete.isNotEmpty()) store.deleteByIds(toDelete)

        when (val syncResult = sync()) {
            is SyncResult.Success -> Log.d(TAG, "Synced ${syncResult.count} messages to watch")
            SyncResult.NoMessages -> Log.w(TAG, "Sync had nothing to send after generation")
            is SyncResult.Failed -> Log.w(TAG, "Sync to watch failed after generation", syncResult.error)
        }
        return GenerationResult.Success(accumulated.map { it.text })
    }

    companion object {
        private const val TAG = "AiMessageGenerator"

        fun create(context: Context): AiMessageGenerator {
            val dao = AppDatabase.getDatabase(context).messageDao()
            return AiMessageGenerator(
                store = RoomGenerationStore(dao),
                client = ClaudeApiClient(ApiKeyStore(context)),
                sync = { PhoneSyncSender(context).syncMessagesToWatch() },
            )
        }
    }
}
```

Then remove `getTopUpvotedTexts` from `MessageDao.kt` (its only caller is now gone) and confirm nothing else references it:

Run: `grep -rn "getTopUpvotedTexts" --include=*.kt .` → expect no matches after removal. Delete any `MessageDaoTest` case that referenced it.

- [ ] **Step 6: Run the loop tests to verify they pass**

Run: `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :mobile:testDebugUnitTest --tests "com.meatsack.motivator.mobile.ai.AiMessageGeneratorTest" --console=plain`
Expected: `BUILD SUCCESSFUL` (4 tests).

- [ ] **Step 7: Full compile + all unit tests across modules**

Run: `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :mobile:compileDebugKotlin :shared:testDebugUnitTest :mobile:testDebugUnitTest :wear:testDebugUnitTest --console=plain`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 8: Format and commit**

```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew spotlessApply --console=plain
git add mobile/src/main/java/com/meatsack/motivator/mobile/ai/ shared/src/main/java/com/meatsack/shared/db/MessageDao.kt mobile/src/test/java/com/meatsack/motivator/mobile/ai/AiMessageGeneratorTest.kt shared/src/androidTest/java/com/meatsack/shared/db/MessageDaoTest.kt
git commit -m "feat(mobile): multi-level vote-aware generation with prune + partial-failure handling"
```

---

## Task 6: Wire the ViewModel + button label

**Files:**
- Modify: `mobile/src/main/java/com/meatsack/motivator/mobile/ui/settings/SettingsViewModel.kt:121-141`
- Modify: `mobile/src/main/java/com/meatsack/motivator/mobile/ui/settings/SettingsScreen.kt` (button label, ~line 234)

**Interfaces:**
- Consumes: `AiMessageGenerator.create(context).generateAcrossLevels(...)` (Task 5).

- [ ] **Step 1: Update generateNow to call generateAcrossLevels**

Replace the body of `generateNow()` in `SettingsViewModel.kt` with:

```kotlin
    fun generateNow() = viewModelScope.launch {
        _generationStatus.value = null
        // v2 scope: generate across all four levels (5 each) at INACTIVITY/FULL_SEND.
        // try/catch guards against any unexpected throw so the UI never sticks in "generating…".
        _generationStatus.value = try {
            val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            AiMessageGenerator.create(getApplication()).generateAcrossLevels(
                trigger = TriggerType.INACTIVITY,
                tone = MessageTone.FULL_SEND,
                hourOfDay = hour,
                currentSteps = 0,
            )
        } catch (t: Throwable) {
            GenerationResult.Failed(t)
        }
    }
```

Remove the now-unused `EscalationLevel` import if the compiler flags it (only if nothing else in the file uses it).

- [ ] **Step 2: Update the button label**

In `SettingsScreen.kt`, find the generate `Button` text (currently `if (vitals) "> GENERATE 10 ROUNDS" else "Generate 10 new insults 💌"`) and change to:

```kotlin
            Text(if (vitals) "> GENERATE 20 // 5 PER LEVEL" else "Generate 20 new insults (5 per level) 💌")
```

- [ ] **Step 3: Compile mobile**

Run: `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :mobile:compileDebugKotlin --console=plain`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Build the phone APK**

Run: `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :mobile:assembleDebug --console=plain`
Expected: `BUILD SUCCESSFUL`; APK at `mobile/build/outputs/apk/debug/mobile-debug.apk`.

- [ ] **Step 5: Format and commit**

```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew spotlessApply --console=plain
git add mobile/src/main/java/com/meatsack/motivator/mobile/ui/settings/SettingsViewModel.kt mobile/src/main/java/com/meatsack/motivator/mobile/ui/settings/SettingsScreen.kt
git commit -m "feat(mobile): wire multi-level generation into Generate button (20, 5 per level)"
```

- [ ] **Step 6: Manual on-device verification (optional but recommended)**

Install on the phone, set an API key, tap Generate, and confirm in the Library that new insults appear across all four levels. Optionally seed a few up/down votes first and confirm generated voice tracks them.

```bash
adb -s ZY22KS3ML2 install -r mobile/build/outputs/apk/debug/mobile-debug.apk
```

---

## Final verification

- [ ] Run the whole unit suite + formatting gate:

Run: `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew spotlessCheck :shared:testDebugUnitTest :mobile:testDebugUnitTest :wear:testDebugUnitTest --console=plain`
Expected: `BUILD SUCCESSFUL`

- [ ] Open a PR from `feature/vote-aware-generation` into `main` and run the PR review toolkit before merging.
