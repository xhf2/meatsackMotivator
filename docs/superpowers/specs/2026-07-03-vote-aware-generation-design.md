# Vote-aware, multi-level insult generation + library retention — design

**Date:** 2026-07-03
**Status:** Approved (design). Ready for implementation planning.
**Branch:** `feature/vote-aware-generation`

## Overview

Today a "Generate" press makes **one** Claude call for 10 `SAVAGE / INACTIVITY / FULL_SEND`
insults, and votes influence generation only weakly: the top-5 net-scored **active** messages
become "match this voice" examples, with no vote floor (so unvoted seeds masquerade as "loved")
and downvotes are never used as a negative signal.

This redesign makes generation **vote-aware in both directions** and **multi-level**, and adds a
**retention sweep** so the library can't grow unbounded now that each press produces far more
insults.

Five items:

- **A — Loved floor + honest fallback:** only genuinely net-positive messages become positive
  exemplars; when none exist yet, say so instead of claiming a preference.
- **B — Avoid-list from downvotes:** net-negative messages become "avoid this voice" examples.
- **C — Multi-level generation:** one press generates across all four levels, not just SAVAGE.
- **D — Magnitude via ranking:** exemplars are ranked by net score (favorites float to the top);
  no vote counts are shown to the model.
- **E — Library retention:** a prune sweep caps the library per bucket, protecting favorites and
  a per-bucket floor.

## Vote partition (shared vocabulary)

By net sign, whole-library:

| Class | Predicate | Use |
|-------|-----------|-----|
| **loved** | `votesUp > votesDown` | positive exemplars; permanent (never pruned) |
| **neutral** | `votesUp == votesDown` (incl. unvoted 0/0) | ignored everywhere |
| **hated** | `votesDown > votesUp` | negative exemplars |

`0/0` seeds are neutral: they steer nothing and are neither "loved" nor "hated".

## A + B + D — Vote-aware prompt

`Prompts.buildUserPrompt` swaps its single `topVoted: List<String>` param for:

- `loved: List<String>` — up to **5**, ordered by net score DESC.
- `hated: List<String>` — up to **3**, ordered by net score ASC (most-negative first).

Both are **whole-library** (level-independent); the per-call `Level:` line governs intensity while
exemplars govern voice/vocabulary.

Prompt blocks:

- **Loved present:** `Here are messages this user loved — match this voice:\n- …`
- **Loved empty (fallback):** `The user hasn't rated any favorites yet — establish a strong,
  consistent signature voice.` (No fabricated "loved" list.)
- **Hated present:** `The user disliked these — do NOT write anything in this voice:\n- …`
- **Hated empty:** the avoid block is omitted entirely.

**D** is expressed purely through ranking + the top-N cut (no counts in the prompt, so the model
can't echo numbers).

## C — Multi-level generation

One "Generate" press runs **4 sequential Claude calls**, one per `EscalationLevel`
(`AGGRESSIVE, SAVAGE, NUCLEAR, EXISTENTIAL`), each requesting **5** insults. Total **20** per press.
`trigger` stays `INACTIVITY` and `tone` stays `FULL_SEND` — **only level varies**. Each returned
line is filtered by the existing rules (`≤ MAX_MESSAGE_TEXT_LENGTH`, no `|`, no newline), tagged
with its level, and inserted as `AI_GENERATED`.

Sequential (not parallel) to stay rate-limit-friendly; the UI already shows a generating state.

## E — Library retention

A **hard-delete** prune sweep runs once at the end of each generate press, over **all sources**
(seeds included), grouped per `(level, tone, trigger)` **bucket**. Age is **not** used (the schema
has no creation timestamp and adding one is out of scope; the per-bucket cap bounds growth anyway).

Per bucket:

1. **Protect favorites** — `votesUp > votesDown` rows are never deleted (any source).
2. **Delete rejected** — non-loved rows with `votesDown >= 3` (already unfireable dead weight).
3. **Delete surplus** — when a bucket exceeds the **cap (50)**, delete the lowest-net non-loved
   rows down to the cap. Only non-loved rows are eligible, so a bucket with more than `cap`
   favorites may legitimately exceed the cap — favorites are permanent and the cap never forces
   their deletion.
4. **Floor guard** — never let a bucket's count of **fireable** rows (`votesDown < 3`) drop below
   the **floor (5)**; if a deletion would, retain the highest-net affected rows to hold the floor.

**Why the floor is mandatory here:** `generateBatch` only ever refills `FULL_SEND / INACTIVITY`
buckets. The `WORK_SAFE` tone and the `BEHIND_PACE / END_OF_DAY / NO_WORKOUT` triggers are seeded
once and never regenerated, so without a floor a run of downvotes could empty them permanently →
`selectMessage` returns `null` → that trigger goes silent forever.

**Composition with selection:** `selectMessage` already excludes `votesDown >= 3`. Deleting rejected
rows therefore changes nothing about *what fires* — it only reclaims space. Defining the floor on
**fireable** rows (not raw count) keeps the two systems consistent: pruning can never silence a
bucket that still has fireable content.

**Back-sync safety:** hard `DELETE` is safe against the watch→phone vote back-sync — `setVotes` on a
missing id updates 0 rows (a no-op).

The prune **selection** is a pure function so it is unit-testable without a DB; the DAO only reads a
snapshot and deletes by id.

## Components

### shared

- **`Message`** — unchanged (no migration).
- **`MessageDao`**
  - Remove `getTopUpvotedTexts`.
  - Add `getLovedTexts(limit): List<String>` —
    `WHERE isActive = 1 AND votesUp > votesDown ORDER BY (votesUp - votesDown) DESC LIMIT :limit`.
  - Add `getHatedTexts(limit): List<String>` —
    `WHERE isActive = 1 AND votesDown > votesUp ORDER BY (votesUp - votesDown) ASC LIMIT :limit`.
  - Add `deleteByIds(ids: List<Long>)` — `DELETE FROM messages WHERE id IN (:ids)`.
  - Reuse existing `getAllMessages(): List<Message>` snapshot for pruning.
- **`LibraryPruner`** (new, pure Kotlin, no Android) —
  `fun selectForDeletion(messages: List<Message>, cap: Int, floor: Int): List<Long>`.
  Groups by bucket and applies rules 1–4 above. Deterministic and fully unit-testable.
- **Constants** (new `GenerationLimits` in `shared/constants`) —
  `INSULTS_PER_LEVEL = 5`, `LOVED_EXAMPLES = 5`, `HATED_EXAMPLES = 3`,
  `BUCKET_CAP = 50`, `BUCKET_FLOOR = 5`. Hardcoded for v1 (no Settings toggle — YAGNI).

### mobile

- **`Prompts.buildUserPrompt`** — new `loved`/`hated` params + conditional blocks + fallback (above).
- **`AiMessageGenerator`** — refactor for testability and multi-level:
  - Extract a narrow `GenerationStore` interface (`getLovedTexts`, `getHatedTexts`, `getAllMessages`,
    `insertAll`, `deleteByIds`) implemented by the Room DAO, so the orchestration can be unit-tested
    with a fake.
  - Constructor takes `store: GenerationStore`, `client: ClaudeApiClient`, and `sync: suspend () ->
    SyncResult`; a `create(context)` factory wires the real Room DAO + `PhoneSyncSender`.
  - New `generateAcrossLevels(trigger, tone, hourOfDay, currentSteps): GenerationResult`:
    1. Fetch `loved(5)` + `hated(3)` once.
    2. For each of the 4 levels: build the level prompt, call the client, filter, tag with that
       level, accumulate valid messages.
    3. `insertAll(accumulated)`.
    4. `LibraryPruner.selectForDeletion(getAllMessages(), CAP, FLOOR)` → `deleteByIds(...)`.
    5. `sync()` once.
    6. Aggregate result (below).
  - Keep a per-level helper for the single-call logic.
- **`SettingsViewModel.generateNow`** — call `generateAcrossLevels(INACTIVITY, FULL_SEND, hour, 0)`;
  surface the total inserted.
- **`GenerationResult`** — `Success` still carries the (aggregate) message list; the status text
  shows the total count. No new variant required.
- **UI** — button label → `Generate 20 (5 per level)` (Vitals/Bubblegum variants preserved).

## Data flow

```
generateNow (SettingsViewModel)
  └─ AiMessageGenerator.generateAcrossLevels(INACTIVITY, FULL_SEND, hour, 0)
       ├─ store.getLovedTexts(5), store.getHatedTexts(3)        (once)
       ├─ for level in [AGGRESSIVE, SAVAGE, NUCLEAR, EXISTENTIAL]:
       │     ├─ Prompts.buildUserPrompt(loved, hated, level, …)
       │     ├─ client.generate(SYSTEM_PROMPT, prompt)
       │     └─ filter + tag(level) + accumulate
       ├─ store.insertAll(accumulated)
       ├─ store.deleteByIds(LibraryPruner.selectForDeletion(store.getAllMessages(), 50, 5))
       └─ sync()  →  PhoneSyncSender → /messages → watch
```

## Error handling

- **No API key** → `generateAcrossLevels` returns `NoApiKey` before any call (checked once).
- **Per-level isolation** — a failed level (HTTP/exception) does not abort the others; whatever
  succeeded is still inserted, pruned, and synced. Result is `Success(all accumulated)` if **≥ 1**
  level produced messages; otherwise the first non-success (`HttpError` / `Failed`) is propagated.
- **Empty after filtering** — treated like a level that produced nothing (not an error).
- **Prune runs regardless** of partial failure (after the inserts that did happen).
- Existing per-line filter (length / `|` / newline) is unchanged.

## Testing

- **`Prompts.buildUserPrompt`** — unit (TDD): loved block rendered + ordered; avoid block rendered;
  no-loved fallback wording; both-empty; caps at 5 / 3; level line reflects the requested level.
- **`LibraryPruner.selectForDeletion`** — unit (TDD): rejected deleted; surplus-over-cap deletes
  lowest-net; loved never deleted (any source); floor respected on fireable rows; a seed with no
  votes is eligible but **kept** when deleting it would drop the bucket below the floor; buckets are
  isolated (deletion in one doesn't affect another).
- **`MessageDao`** — `androidTest` (emulator): `getLovedTexts` / `getHatedTexts` partition + order +
  `LIMIT` + `isActive` filter; `deleteByIds` removes exactly the given ids.
- **`AiMessageGenerator.generateAcrossLevels`** — unit with fakes (`GenerationStore` + fake client +
  fake sync): all 4 levels called with the right level; per-level tagging; partial-failure
  aggregation; `NoApiKey` short-circuit (no calls); prune invoked once; sync invoked once.

## Out of scope (future)

- Creation-timestamp column + age-based staleness (would need a Room migration).
- Making thresholds Settings-configurable + synced.
- Varying `trigger`/`tone` per press (only `level` varies here).
- Parallelizing the 4 level calls.
- An anti-duplication guard (showing Claude the existing library to avoid near-duplicates).
