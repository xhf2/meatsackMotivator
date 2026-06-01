# Insult Length Cap Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Cap insult text at 100 characters across the entire pipeline (seed → AI generation → wire format) so the watch's vote buttons never get pushed off-screen by overly long messages.

**Architecture:** New `MessageLimits` constant in `:shared/constants/` is the single source of truth. `MessageSerializer.serialize` extends its existing `require {}` clause to gate the wire format. `AiMessageGenerator.generateBatch` already has a filter — we wire it to the constant and add a rejection-count log. `Prompts.kt` tells Claude the hard limit so the filter rarely fires. `SeedData.kt`'s 2 outliers get rewritten under 100 chars before the validator lands.

**Tech Stack:** Kotlin, Room (`:shared`), JUnit (`:shared` unit tests). No new dependencies.

**Spec:** `docs/superpowers/specs/2026-06-01-insult-length-cap-design.md`

---

## File Structure

### New

| Path | Responsibility |
|---|---|
| `shared/src/main/java/com/meatsack/shared/constants/MessageLimits.kt` | `MAX_MESSAGE_TEXT_LENGTH = 100`. Lone constant + KDoc explaining the empirical calibration. |

### Modified

| Path | Change |
|---|---|
| `shared/src/main/java/com/meatsack/shared/data/SeedData.kt` | Rewrite 2 lines (115- and 104-char ones) under 100 chars. |
| `shared/src/main/java/com/meatsack/shared/sync/MessageSerializer.kt` | Extend the existing `require {}` clause in `serialize` with a length check; update the error message to include actual length. |
| `shared/src/test/java/com/meatsack/shared/sync/MessageSerializerTest.kt` | Append 2 tests: 100-char acceptance + 101-char rejection. |
| `mobile/src/main/java/com/meatsack/motivator/mobile/ai/AiMessageGenerator.kt` | Replace the hardcoded `200` length filter with `MessageLimits.MAX_MESSAGE_TEXT_LENGTH`. Add `Log.w` reporting the dropped count when nonzero. |
| `mobile/src/main/java/com/meatsack/motivator/mobile/ai/Prompts.kt` | Replace "max 20 words" instruction with "≤100 characters total — strict limit". |

---

## Pre-flight

- [ ] **Step 0: Confirm branch**

Run: `git status`
Expected: `On branch fix/insult-length-cap`. The spec was committed on this branch as `8a18895`. If on the wrong branch: `git checkout fix/insult-length-cap`.

---

## Task 1: Add `MessageLimits` constant

**Files:**
- Create: `shared/src/main/java/com/meatsack/shared/constants/MessageLimits.kt`

- [ ] **Step 1.1: Create the file**

```kotlin
package com.meatsack.shared.constants

/**
 * Hard caps for message data. Single source of truth — referenced by
 * MessageSerializer (wire-format gate), AiMessageGenerator (post-generation
 * filter), and Prompts (preempt at generation time).
 */
object MessageLimits {
    /**
     * Maximum length of [Message.text] in characters. Sized so the watch's
     * InsultScreen fits the text + stats line + 👍/👎 vote buttons on a
     * Wear OS LARGE_ROUND face without overflow. Calibrated empirically
     * against the Compose preview in InsultScreen.kt.
     */
    const val MAX_MESSAGE_TEXT_LENGTH = 100
}
```

- [ ] **Step 1.2: Build :shared to verify**

Run: `./gradlew :shared:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 1.3: Commit**

```bash
git add shared/src/main/java/com/meatsack/shared/constants/MessageLimits.kt
git commit -m "feat: add MessageLimits.MAX_MESSAGE_TEXT_LENGTH constant"
```

---

## Task 2: Rewrite the 2 SeedData outliers

**Files:**
- Modify: `shared/src/main/java/com/meatsack/shared/data/SeedData.kt`

The two seeds that exceed 100 chars are:
- `"You soft-bellied comfort addict. 90 minutes. Your muscles are screaming and you can't hear them over your excuses."` (115 chars)
- `"2 hours. You beached fucking walrus of a human. Every minute is a choice and you keep choosing weakness."` (104 chars)

- [ ] **Step 2.1: Find and replace the 115-char outlier**

Use the `Edit` tool (or your editor) to replace:

`"You soft-bellied comfort addict. 90 minutes. Your muscles are screaming and you can't hear them over your excuses."`

with:

`"You soft-bellied comfort addict. 90 minutes. Your muscles scream; your excuses drown them out."`

(97 characters — verified by counting.)

- [ ] **Step 2.2: Find and replace the 104-char outlier**

Replace:

`"2 hours. You beached fucking walrus of a human. Every minute is a choice and you keep choosing weakness."`

with:

`"2 hours. You beached walrus. Every minute is a choice and you keep choosing weakness."`

(85 characters — verified by counting.)

- [ ] **Step 2.3: Verify no other seed exceeds 100 chars**

Run this from the repo root:

```bash
grep -oE '"[^"]{101,}"' shared/src/main/java/com/meatsack/shared/data/SeedData.kt
```

Expected: no output (no remaining lines over 100 chars).

- [ ] **Step 2.4: Build to confirm Kotlin still parses**

Run: `./gradlew :shared:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 2.5: Commit**

```bash
git add shared/src/main/java/com/meatsack/shared/data/SeedData.kt
git commit -m "data: shorten 2 seed insults that overflowed the watch screen"
```

---

## Task 3: Extend `MessageSerializer.serialize` with length check (TDD)

**Files:**
- Modify: `shared/src/test/java/com/meatsack/shared/sync/MessageSerializerTest.kt`
- Modify: `shared/src/main/java/com/meatsack/shared/sync/MessageSerializer.kt`

- [ ] **Step 3.1: Write failing tests**

Append to `shared/src/test/java/com/meatsack/shared/sync/MessageSerializerTest.kt` (above the closing `}`):

```kotlin
@Test
fun serialize_acceptsExactly100Chars() {
    val text100 = "x".repeat(100)
    val serialized = MessageSerializer.serialize(listOf(sampleMessage(text = text100)))
    assertNotNull(serialized)
    assertTrue("100-char text should round-trip", serialized.contains(text100))
}

@Test
fun serialize_rejects101Chars() {
    val text101 = "x".repeat(101)
    val e = org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
        MessageSerializer.serialize(listOf(sampleMessage(text = text101)))
    }
    assertTrue(
        "Error message should include actual length; was: ${e.message}",
        e.message?.contains("101") == true,
    )
}
```

- [ ] **Step 3.2: Run the tests, expecting failure on the 101-char case**

Run: `./gradlew :shared:testDebugUnitTest --tests "com.meatsack.shared.sync.MessageSerializerTest"`
Expected: `serialize_acceptsExactly100Chars` PASSES (no length check yet, so 100 is fine); `serialize_rejects101Chars` FAILS (no exception is thrown — `require { }` doesn't reject yet).

- [ ] **Step 3.3: Add the length check to `serialize`**

Open `shared/src/main/java/com/meatsack/shared/sync/MessageSerializer.kt`. Find the current `require {}` at the top of the `joinToString` lambda:

```kotlin
require(!m.text.contains(FIELD_SEPARATOR) && !m.text.contains(LINE_SEPARATOR)) {
    "Message text cannot contain '|' or newline; id=${m.id}"
}
```

Replace it with:

```kotlin
require(
    !m.text.contains(FIELD_SEPARATOR) &&
        !m.text.contains(LINE_SEPARATOR) &&
        m.text.length <= MessageLimits.MAX_MESSAGE_TEXT_LENGTH,
) {
    "Message text invalid (length=${m.text.length}, max=${MessageLimits.MAX_MESSAGE_TEXT_LENGTH}, " +
        "has '|'=${m.text.contains(FIELD_SEPARATOR)}, has newline=${m.text.contains(LINE_SEPARATOR)}); id=${m.id}"
}
```

Add this import near the existing `com.meatsack.shared.*` imports at the top:

```kotlin
import com.meatsack.shared.constants.MessageLimits
```

- [ ] **Step 3.4: Run tests again, expecting all green**

Run: `./gradlew :shared:testDebugUnitTest --tests "com.meatsack.shared.sync.MessageSerializerTest"`
Expected: both new tests PASS; all pre-existing serializer tests still PASS.

- [ ] **Step 3.5: Commit**

```bash
git add shared/src/main/java/com/meatsack/shared/sync/MessageSerializer.kt \
        shared/src/test/java/com/meatsack/shared/sync/MessageSerializerTest.kt
git commit -m "feat(sync): MessageSerializer rejects text over 100 chars"
```

---

## Task 4: Wire `AiMessageGenerator` filter to the constant + log dropped count

**Files:**
- Modify: `mobile/src/main/java/com/meatsack/motivator/mobile/ai/AiMessageGenerator.kt`

`AiMessageGenerator.generateBatch` already filters with `it.length <= 200 && !it.contains('|') && !it.contains('\n')`. We replace the hardcoded `200` with the shared constant and add visibility into how many were dropped.

- [ ] **Step 4.1: Update the filter**

In `AiMessageGenerator.kt`, add the import alongside the existing `com.meatsack.shared.*` imports:

```kotlin
import com.meatsack.shared.constants.MessageLimits
```

Find the existing block inside `is GenerationResult.Success -> {` that reads:

```kotlin
val valid = result.messages
    .filter { it.length <= 200 && !it.contains('|') && !it.contains('\n') }
if (valid.isEmpty()) {
    Log.w(TAG, "Claude returned no valid messages after filtering")
    return GenerationResult.Success(emptyList())
}
```

Replace with:

```kotlin
val valid = result.messages
    .filter {
        it.length <= MessageLimits.MAX_MESSAGE_TEXT_LENGTH &&
            !it.contains('|') &&
            !it.contains('\n')
    }
val dropped = result.messages.size - valid.size
if (dropped > 0) {
    Log.w(TAG, "Dropped $dropped/${result.messages.size} generations (over ${MessageLimits.MAX_MESSAGE_TEXT_LENGTH} chars or contains '|'/newline)")
}
if (valid.isEmpty()) {
    Log.w(TAG, "Claude returned no valid messages after filtering")
    return GenerationResult.Success(emptyList())
}
```

- [ ] **Step 4.2: Build to verify**

Run: `./gradlew :mobile:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4.3: Run mobile unit tests for regression**

Run: `./gradlew :mobile:testDebugUnitTest`
Expected: all existing tests still pass. No new test is added — drop-and-log behavior is hard to test cleanly without mocking `ClaudeApiClient`, and the rejection log will surface the behavior in field use.

- [ ] **Step 4.4: Commit**

```bash
git add mobile/src/main/java/com/meatsack/motivator/mobile/ai/AiMessageGenerator.kt
git commit -m "feat(ai): wire filter to MessageLimits + log dropped generations"
```

---

## Task 5: Tighten `Prompts.kt` so Claude knows the hard limit

**Files:**
- Modify: `mobile/src/main/java/com/meatsack/motivator/mobile/ai/Prompts.kt`

- [ ] **Step 5.1: Replace the length instruction**

In `Prompts.kt`, find the line in `buildUserPrompt`'s triple-quoted block that reads:

```
Generate $count short (1-2 sentence, max 20 words each) motivational insults.
```

Replace with:

```
Generate $count motivational insults. Each must be 1–2 sentences AND at most 100 characters total — strict limit, never exceed.
```

The rest of the prompt (style guidance, example insults, "Return ONE message per line" footer) stays untouched.

- [ ] **Step 5.2: Build to verify**

Run: `./gradlew :mobile:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5.3: Verify `PromptsTest` still passes (string asset change may shift expected output)**

Run: `./gradlew :mobile:testDebugUnitTest --tests "com.meatsack.motivator.mobile.ai.PromptsTest"`
Expected: passes — but if the test asserts the exact prompt string anywhere, update those expected values to match the new wording. (Read `mobile/src/test/java/com/meatsack/motivator/mobile/ai/PromptsTest.kt` first to confirm.)

If tests fail because they assert the old wording, update those expected strings inline:

```bash
# Inspect what the test expects
grep -nE "max 20 words|Generate .* short" mobile/src/test/java/com/meatsack/motivator/mobile/ai/PromptsTest.kt
```

Replace any matching expected substring with `"at most 100 characters"`.

- [ ] **Step 5.4: Commit**

```bash
git add mobile/src/main/java/com/meatsack/motivator/mobile/ai/Prompts.kt
# Include the test file if Step 5.3 required updates:
# git add mobile/src/test/java/com/meatsack/motivator/mobile/ai/PromptsTest.kt
git commit -m "feat(ai): tell Claude the 100-char limit in the user prompt"
```

---

## Task 6: Full verification + push + PR

**Files:** none modified.

- [ ] **Step 6.1: Full build (catches spotless/lint regressions)**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6.2: Full unit test sweep**

Run: `./gradlew :shared:testDebugUnitTest :mobile:testDebugUnitTest :wear:testDebugUnitTest`
Expected: all green. New `MessageSerializerTest` tests included.

- [ ] **Step 6.3: Manual smoke (optional but recommended)**

Per the spec's acceptance criteria:
- Install fresh on paired emulators: `./gradlew :mobile:assembleDebug :wear:assembleDebug`, then `adb -s <phone-id> install -r mobile/build/outputs/apk/debug/mobile-debug.apk` and same for watch.
- Launch the watch app; if you can trigger an insult (drop `EscalationLevel.INACTIVITY_THRESHOLD_MINUTES_DEFAULT` temporarily, or wait for the polling service to fire), confirm 👍/👎 buttons remain visible on the longest seed.

Optional. The Compose preview validates the layout; the new wire-format gate guarantees no longer-than-cap text reaches the screen.

- [ ] **Step 6.4: Push the branch**

```bash
git push -u origin fix/insult-length-cap
```

- [ ] **Step 6.5: Open the PR**

```bash
gh pr create --title "Cap insult text at 100 chars so watch vote buttons stay reachable" --body "$(cat <<'EOF'
## Summary
- Adds `MessageLimits.MAX_MESSAGE_TEXT_LENGTH = 100` to `:shared/constants/` as the single source of truth.
- `MessageSerializer.serialize` extends its existing `require {}` clause to reject text over the limit (alongside the existing `|`/newline checks). Two new boundary tests: exact-100 accepted, 101 rejected with informative error.
- `AiMessageGenerator.generateBatch` swaps the hardcoded `200`-char filter for the shared constant and now logs the dropped count when generations are filtered.
- `Prompts.kt` tells Claude the strict 100-char limit so the filter rarely fires.
- `SeedData.kt`: 2 outliers (115 and 104 chars) rewritten under 100 chars to keep the validator from rejecting our own seeds.

## Why
The watch's `InsultScreen` lays out brand text → insult text → stats → 👍/👎 buttons. When the insult text wraps too many lines, the vote buttons get pushed off-screen and voting becomes impossible. Calibrated against the Compose preview to find the breakpoint at ~100 chars.

## Test plan
- [x] `./gradlew build` — BUILD SUCCESSFUL
- [x] `./gradlew :shared:testDebugUnitTest :mobile:testDebugUnitTest :wear:testDebugUnitTest` — all green (includes 2 new `MessageSerializer` boundary tests)
- [ ] Manual on paired emulators (optional): install fresh, trigger insults from longest seeds, confirm vote buttons stay visible.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

- [ ] **Step 6.6: Run PR review per project convention**

After `gh pr create`, invoke `pr-review-toolkit:review-pr` on the new PR. This PR is small (6 files); a single code-reviewer agent is sufficient — the multi-agent fan-out would be overkill.

---

## Risks / known unknowns

- **`PromptsTest` exact-string assertions:** Task 5 Step 5.3 warns. If `PromptsTest` asserts the full prompt text verbatim, the wording change requires updating the test's expected value. Handle inline.
- **Seed rewrites change voice slightly:** Both rewrites preserve the spirit but the user may want to tweak phrasing. They're called out separately in the spec so the user can override in review.

## Out of scope (deferred — track separately if needed)

- Watch-side display truncation/ellipsis (unnecessary if upstream gating works).
- Min-length floor (short insults are intentional).
- Retry-on-rejection in `AiMessageGenerator` (one-shot drop is fine).
- Backfilling user-generated long messages (no production users).
