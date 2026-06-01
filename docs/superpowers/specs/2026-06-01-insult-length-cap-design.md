# Insult Length Cap — Design

> **Status:** approved design, pre-implementation.

## Problem

Some seeded and AI-generated insults exceed the watch's `InsultScreen` text capacity. When the text wraps too many lines, the 👍/👎 vote buttons either get pushed below the screen edge or overlap the text — voting becomes impossible. Two existing `SeedData` entries (104 and 115 chars) reliably trigger this; AI-generated insults are unbounded in practice despite the prompt's "max 20 words" instruction.

## Solution

Enforce a single invariant — `insult.text.length ≤ 100` — at every entry point into the message store:

- **Wire format gate** (`MessageSerializer.serialize`) so sync can never carry a too-long message.
- **AI generation filter** (`AiMessageGenerator.generateBatch`) so over-long generations are dropped and logged before persist.
- **Prompt instruction** (`Prompts.kt`) so Claude is told the hard limit, reducing the rejection rate of the filter above.
- **Seed cleanup** (`SeedData.kt`) so the existing 2 outliers fit, otherwise the new wire-format gate would throw on first sync.

The constant lives once in `:shared` so both modules read the same number.

### Why 100 characters

Empirically calibrated against the Wear OS `LARGE_ROUND` Compose preview (`InsultScreen.kt`). At 100 chars the text wraps to ~4 lines and leaves room for the brand text, stats line, and 40dp vote buttons. At 120+ the buttons overflow; at ≤80 the screen looks sparse. 100 is the breakpoint that fixes the immediate problem without over-trimming.

### Why drop (not regenerate or truncate) in `AiMessageGenerator`

- **Drop:** preserves Claude's intent; the user gets fewer but coherent insults.
- **Truncate:** mid-sentence cuts produce broken jokes — worse than not showing it.
- **Regenerate:** doubles the API cost and adds a retry loop's complexity for a constraint that should be rare given the prompt update.

## Files touched

| Path | Change |
|---|---|
| `shared/src/main/java/com/meatsack/shared/constants/MessageLimits.kt` | New file. Single `const val MAX_MESSAGE_TEXT_LENGTH = 100` in an object with KDoc explaining the empirical calibration. |
| `shared/src/main/java/com/meatsack/shared/sync/MessageSerializer.kt` | Add `m.text.length <= MessageLimits.MAX_MESSAGE_TEXT_LENGTH` to the existing `require {}` clause. Error message includes the actual length for debuggability. |
| `shared/src/main/java/com/meatsack/shared/data/SeedData.kt` | Rewrite 2 lines so both stay under 100 chars without losing voice. Proposed: `"You soft-bellied comfort addict. 90 minutes. Your muscles scream; your excuses drown them out."` (97) and `"2 hours. You beached walrus. Every minute is a choice and you keep choosing weakness."` (85). |
| `mobile/src/main/java/com/meatsack/motivator/mobile/ai/AiMessageGenerator.kt` | After the response is parsed into a list of strings, `.filter { it.length <= MessageLimits.MAX_MESSAGE_TEXT_LENGTH }` and `Log.w` the rejection count when nonzero. Use the existing `TAG` constant in the class. |
| `mobile/src/main/java/com/meatsack/motivator/mobile/ai/Prompts.kt` | Replace `"Generate $count short (1-2 sentence, max 20 words each) motivational insults."` with `"Generate $count motivational insults. Each must be 1–2 sentences AND at most 100 characters total — strict limit, never exceed."`. The "Return ONE message per line. No numbering, no bullets, no quotes." footer stays. |
| `shared/src/test/java/com/meatsack/shared/sync/MessageSerializerTest.kt` | Add 2 tests: `serialize_acceptsExactly100Chars` (boundary passes) and `serialize_rejects101Chars` (boundary +1 throws and the error message contains the actual length). |

## Error handling

- `MessageSerializer.serialize` already throws on `|` and newline; this PR extends the same `require {}` block. Callers see `IllegalArgumentException` with a descriptive message. The phone's `PhoneSyncSender` invokes `serialize` and would propagate. This matches the existing failure mode for `|`/`\n` violations.
- `AiMessageGenerator.generateBatch` filtering uses `Log.w` to surface rejection counts. The filter is non-fatal: the user simply gets fewer accepted insults from a generation batch. If Claude produces zero acceptable outputs, the existing batch-result handling reports an empty set (no API-level change).
- No watch-side display changes — by the time text reaches `InsultScreen`, it has already passed the wire gate.

## Out of scope

- Display-side truncation/ellipsis on the watch. Once data is gated upstream, this is unnecessary.
- Min-length floor. Very short insults (e.g. "MOVE.") are intentional and shouldn't be rejected.
- Retry-on-rejection in `AiMessageGenerator`. Cost not justified for a rare event.
- Backfilling user-generated long messages in existing installs. There are no production users.

## Acceptance criteria

- `./gradlew :shared:testDebugUnitTest` passes (includes the 2 new tests).
- `./gradlew :shared:compileDebugKotlin :mobile:compileDebugKotlin :wear:compileDebugKotlin` clean.
- Pre-commit hook (`spotlessCheck` + unit tests) clean.
- Manual verification: install fresh on phone + watch, trigger an insult (or rely on existing seed coverage), confirm vote buttons remain visible and tappable on the longest seed message.
