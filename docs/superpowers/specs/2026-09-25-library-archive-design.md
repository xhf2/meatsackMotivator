# Library archive and filters — design

**Date:** 2026-09-25
**Status:** Approved (design). Ready for implementation planning.
**Branch:** `feature/library-archive`

## Overview

The phone Library lists every row in the database. With ~114 messages, 20 of
which already have three downvotes and can never fire again, the list has
become hard to use, and there is no way to keep an old favourite around
without it being either in the firing rotation or deleted by the pruner.

This feature adds:

1. An **Archived** state: a message the user keeps but that never fires, is
   never pruned, and is not used as an AI style example. Hand-picked, one tap
   per card, reversible.
2. **Three filter chips** on the Library screen — **Active** (default),
   **Archived**, **Retired** — so the default view shows only what can fire.
3. **Delete** for retired messages (three or more downvotes), per card and
   "delete all retired", so the user can clear them without pressing Generate.

Explicitly **not** included: automatic retirement after N deliveries (that is
the freshness feature; it will set the same archived flag), watch-side
deletion of rows the phone deleted (existing PR #58 follow-up), and any change
to voting.

## Behaviour

### States

A message is in exactly one of three states, derived from existing columns:

| State | Condition | Fires on watch | Pruned | AI examples |
|---|---|---|---|---|
| **Active** | `isActive = 1` and `votesDown < 3` | yes | by existing cap rules | yes |
| **Retired** | `isActive = 1` and `votesDown >= 3` | no (watch query hides it) | deleted on next Generate unless loved | hated only |
| **Archived** | `isActive = 0` | no (watch query hides it) | **never** | **no** |

No new column, no Room migration: `isActive` exists on `Message`, is already
carried by the `/messages` wire format, is already respected by the watch's
`getEligibleMessages`, and is currently never set to 0 by any code path. The
`deactivate` DAO method exists with no callers.

Archived is orthogonal to votes: an archived row keeps its counts. Archiving a
retired row is allowed (that is the "old, unliked, but hilarious" case) and
moves it out of the pruner's reach.

### Filter chips

Above the list, three chips with counts, e.g. `Active 74 · Archived 0 ·
Retired 20`. Active is selected on entry. The selection is screen state only
(not persisted). The vitals/bubblegum header count shows the selected chip's
count instead of the total.

Per-card actions by chip:

- **Active**: ▲ ▼ (unchanged) and **Archive**.
- **Archived**: **Unarchive**. Vote counts shown, vote buttons hidden — votes
  on a non-firing message would only distort the AI steering signal.
- **Retired**: **Archive** and **Delete**. Plus a **Delete all retired (N)**
  button at the top of the list, behind a confirmation dialog.

Archive and Unarchive are immediate (no confirmation); both are reversible.
Per-card Delete has no confirmation: the row is under a chip named Retired and
was already unfireable. "Delete all retired" confirms because it is bulk and
permanent.

### Ordering

`FrozenOrder` keeps its job (pin the order while the screen is open so a
vote's re-sort cannot cause a mis-tap). The chip filter is applied *after*
the frozen order, so switching chips never reorders rows. A row that changes
state (archive, third downvote) leaves the current chip's list on the next
emission; it does not move within it.

### Sync

Archive/unarchive/delete use the same write-then-debounced-sync path as votes
(`LibraryEditor`, 2 s). Consequences:

- **Archive/unarchive reach the watch** as `isActive` in the next `/messages`
  push, and the watch's `upsertPreservingShown` takes the flag from the phone.
  The watch's eligibility query already requires `isActive = 1`, so an
  archived message stops firing after the next sync, with no watch change.
- **`PhoneSyncSender` must stop filtering out inactive rows.** Today it sends
  only `isActive` rows; if it kept doing that, the watch would never learn a
  row was archived and would keep firing its stale active copy. The sender's
  existing comment already makes the same argument for `votesDown >= 3` rows.
  The 200-row cap is unchanged; archived rows sort by net votes like any
  other.
- **Delete is phone-only.** The watch never deletes rows (documented
  limitation). A deleted retired row was already hidden on the watch by its
  three downvotes, so nothing user-visible changes there.

### Pruner

`LibraryPruner.selectForDeletion` currently ignores `isActive`. New rule,
applied first: **inactive rows are excluded from the input** — never marked
for deletion, not counted toward the bucket cap, not counted as fireable
survivors for the floor. Everything else is unchanged.

### AI examples

`getLovedTexts` / `getHatedTexts` already require `isActive = 1`, so archived
rows drop out of both example lists with no change. Documented as intended:
the archive is for the user, not for steering Claude.

## Components

### `LibraryFilter` (new, `mobile/ui/library`, pure)

```kotlin
enum class LibraryFilter { ACTIVE, ARCHIVED, RETIRED }

object LibraryFilters {
    fun stateOf(m: Message): LibraryFilter
    fun apply(messages: List<Message>, filter: LibraryFilter): List<Message>
    fun counts(messages: List<Message>): Map<LibraryFilter, Int>
}
```

`stateOf` encodes the table above and is the single source of truth for the
three states. No Android dependencies; JVM-tested.

### `VoteStore` → `LibraryStore` (modified, `mobile/ui/library`)

Rename the interface (it is no longer vote-only) and add:

```kotlin
suspend fun setActive(messageId: Long, active: Boolean)
suspend fun deleteByIds(ids: List<Long>)
```

`RoomLibraryStore` maps `setActive` to a new `MessageDao.setActive` query and
`deleteByIds` to the existing DAO method.

### `MessageDao` (modified, `shared`)

```kotlin
@Query("UPDATE messages SET isActive = :active WHERE id = :messageId")
suspend fun setActive(messageId: Long, active: Boolean)
```

The existing `deactivate(id)` becomes redundant; delete it (no callers).

### `LibraryEditor` (modified)

New public methods, each following the existing `vote` pattern (write inside
a try/catch that logs and returns on failure; on success, `scheduleSync()`):

```kotlin
fun archive(messageId: Long)
fun unarchive(messageId: Long)
fun delete(messageIds: List<Long>)
```

`delete` also schedules a sync: not because the watch needs it, but because
the same debounced timer may already hold pending vote/archive changes and
keeping every mutation on one path is simpler than special-casing.

### `LibraryViewModel` (modified)

- Exposes `filter: StateFlow<LibraryFilter>` (default ACTIVE) and
  `setFilter(f)`.
- Exposes `counts: StateFlow<Map<LibraryFilter, Int>>` derived from the
  message flow.
- `messages` becomes the frozen-ordered list *then* filtered by the current
  chip (`combine(orderedMessages, filter)`).
- Forwards `archive`, `unarchive`, `delete`, `deleteAllRetired` to the editor.
  `deleteAllRetired` computes the id list from the current retired rows.

### `LibraryScreen` (modified)

- A `FilterChips` row under the header, one Compose function with a `vitals`
  boolean like the existing header/sync bar pairs (Vitals: monospace
  bracketed labels; Bubblegum: rounded pastel chips).
- Card action row varies by chip as specified. Archive/Unarchive/Delete use
  the same clickable-Text style as ▲/▼, with `onClickLabel`s for
  accessibility.
- "Delete all retired (N)" button appears only on the Retired chip when
  N > 0, and opens an `AlertDialog` (confirm / cancel).
- Existing snackbar behaviour for failed auto-sync is unchanged.

### `PhoneSyncSender` (modified)

Remove `.filter { it.isActive }`. Update the comment to say inactive rows are
sent so the watch can hide them.

### `LibraryPruner` (modified, `shared`)

First line of `selectForDeletion`: `val messages = messages.filter { it.isActive }`
(with a comment referencing this spec). No other change.

## Data flow

1. User taps **Archive** on an Active card.
2. `LibraryEditor.archive` → `store.setActive(id, false)` → Room emits →
   `LibraryViewModel.messages` re-emits; the card leaves the Active list.
3. 2 s later `scheduleSync` runs `PhoneSyncSender.syncMessagesToWatch()`,
   which now includes the row with `isActive = 0`.
4. Watch `WatchSyncReceiver.store` → `upsertPreservingShown` writes
   `isActive = 0` (and keeps the watch's `lastShownTimestamp`).
5. Watch `getEligibleMessages` no longer returns the row. It never fires
   again until unarchived, which reverses steps 2–5.

Delete: steps 1–3 with `store.deleteByIds`; the row simply disappears from the
phone. Step 4 sends a payload that no longer contains it; the watch keeps its
(already hidden) copy.

## Error handling

- Store write failures: logged at ERROR by `LibraryEditor` with the action
  name and id; the tap has no effect; no sync is scheduled for it. Same as
  votes today.
- Sync failures: surfaced by the existing `autoSyncResults` snackbar. An
  archive that fails to sync leaves the watch firing the message until the
  next successful sync (manual Sync to Watch or any later vote/archive). This
  is the same window votes already have and is acceptable.
- "Delete all retired" with an empty list is a no-op (button hidden at N = 0).
- Deleting a row that was pruned by a concurrent Generate: `DELETE ... WHERE
  id IN` on a missing id is a no-op. Fine.

## Testing

JVM (run in CI and the pre-commit hook):

- `LibraryFiltersTest`: `stateOf` for all six (isActive × votesDown<3 / ≥3)
  combinations; `apply` returns only matching rows and preserves input order;
  `counts` sums to the input size.
- `LibraryPrunerTest` additions: an inactive row with `votesDown >= 3` and no
  upvotes is **not** deleted; inactive rows do not count toward the cap (a
  bucket of 50 active + 10 inactive prunes nothing); inactive rows do not
  satisfy the floor (a bucket of 5 inactive + 3 rejected active still deletes
  the 3 rejected, because the floor guard only restores *fireable* marked
  rows and rejected rows are not fireable).
- `LibraryEditorTest` additions (existing test style with a fake store and a
  test dispatcher): `archive` writes `setActive(id, false)` and schedules one
  sync; `unarchive` writes `setActive(id, true)`; `delete(ids)` writes
  `deleteByIds(ids)`; a failing store write logs and schedules no sync.
- `LibraryViewModel` is thin; the combine logic is covered through
  `LibraryFilters.apply` + `FrozenOrder` tests. No new ViewModel test.

Instrumented (emulator, run by hand):

- `MessageDaoTest`: `setActive(id, false)` then `getEligibleMessages`
  excludes the row; `setActive(id, true)` restores it.
- `PhoneSyncSender` has no test today; the filter removal is covered by
  reading the code in review. (A pure "select rows to send" extraction is
  tempting but out of scope.)

Manual on paired devices: archive a message on the phone, wait 2 s, pull the
watch DB and confirm `isActive = 0` on that id; unarchive and confirm `1`.

## Known limitations (accepted)

- The watch keeps rows the phone deleted. They are hidden there by
  `votesDown >= 3`, so this is invisible, but the watch DB grows until the
  delete-reconciliation follow-up lands.
- Chip selection resets to Active every time the screen is entered.
- An archived message still counts toward the phone's 200-row sync cap.
  With ~114 rows this is far off; if the library ever approaches 200, sort
  archived rows last in the sender.
- No search or text filter; the chips are the only narrowing.

## Out of scope (future)

- Automatic retirement after N deliveries (freshness feature) — will set
  `isActive = 0` through the same `setActive` and appear under Archived.
- Export of the archive (import/export feature).
- Watch-side delete reconciliation.
