# Phone Library voting — design

**Date:** 2026-09-06
**Status:** Approved (design). Ready for implementation planning.
**Branch:** `feature/phone-library-votes`

## Overview

Votes are the only signal the AI generator learns from: loved messages become
"match this voice" exemplars, hated ones become "avoid this voice" exemplars,
and three downvotes make a message unfireable and eligible for pruning. Today
the **only** place to vote is the watch, one message at a time, whenever it
happens to fire. Messages that rarely fire rarely get voted on, so the
generator's steering signal accumulates slowly.

This feature adds **upvote and downvote buttons to every card on the phone
Library screen**, so the user can rate the whole library in one sitting. A
phone vote is the same operation as a watch vote (same DAO increment on the
same column); the difference is where the tap happens.

Explicitly **not** included: deleting messages from the phone (the user chose
downvote-only; three downvotes already retire a message), and import/export
of joke sets (separate feature, separate branch).

## Behaviour

- Each Library card shows an **▲ up** and **▼ down** control with the current
  counts beside them, replacing the read-only `▲n ▼m` text. Both the Vitals
  panel and the Bubblegum panel get the controls (they are separate
  composables; Bubblegum keeps its 💕/💔 glyphs).
- A tap increments the corresponding counter via the existing
  `MessageDao.voteUp` / `voteDown` queries. No confirmation dialog; votes are
  unbounded, matching the watch.
- Counts on the card update live (`LibraryViewModel.messages` observes
  `getAllMessagesFlow()`), but the **display order is frozen** for the
  ViewModel's lifetime via `FrozenOrder`: the DAO's net-score ordering would
  otherwise move a card out from under the user's finger after each tap, and
  with no undo and a permanent retire at three downvotes, a mis-tap is
  uncorrectable. New rows append at the bottom; the order re-sorts on next
  screen creation.
- After a vote, the phone **automatically syncs to the watch** using the same
  `PhoneSyncSender.syncMessagesToWatch()` the Sync button runs, **debounced**
  (~2 s) so a burst of taps across several cards produces one sync.

### Why auto-sync is required, not optional

The watch → phone vote channel (`/votes`) sends **absolute** counts and
`PhoneVoteReceiver` applies them with `setVotes`. If a phone vote sat
unsynced, the next watch vote on that message would overwrite the phone's
count with the watch's stale one. Pushing the phone's counts to the watch
promptly (the `/messages` payload carries `votesUp`/`votesDown` and the watch
inserts with `REPLACE`) keeps the two copies converged; subsequent watch
votes increment from the phone's value. This closes the phone-side window
only; see *Reverse vote clobber* under Known limitations.

### Sync filter change

`PhoneSyncSender` currently sends only rows with `isActive && votesDown < 3`.
On the watch that filter was harmless: a watch downvote reaches 3 locally, so
the watch's own `getEligibleMessages` (which already requires
`votesDown < 3`) hides it. A **phone** downvote to 3 is different: the phone
would stop sending the row, the watch would keep its `votesDown = 2` copy,
and the message would keep firing.

Fix: drop the `votesDown < 3` half of the filter. Keep `isActive`. The watch
still filters `votesDown < 3` in its query, so rejected rows are sent but
never fire. Capacity is not a concern: `getAllMessages()` orders by net score
DESC so rejected rows sort last and are the first to fall off the 200-row
`CACHE_SIZE`; the watch accepts up to 500.

## Components

### `VoteStore` (new, `mobile/ui/library`)

Narrow persistence surface, mirroring `GenerationStore`, so the editor is
unit-testable with a fake:

```kotlin
interface VoteStore {
    suspend fun voteUp(messageId: Long)
    suspend fun voteDown(messageId: Long)
}

class RoomVoteStore(private val dao: MessageDao) : VoteStore { /* delegates */ }
```

### `LibraryEditor` (new, `mobile/ui/library`)

Owns the vote → debounced-sync behaviour. Constructor-injected for tests:

```kotlin
class LibraryEditor(
    private val store: VoteStore,
    private val sync: suspend () -> SyncResult,
    private val scope: CoroutineScope,
    private val debounceMs: Long = 2_000,
    private val onSyncResult: (SyncResult) -> Unit,
) {
    fun voteUp(messageId: Long)
    fun voteDown(messageId: Long)
}
```

Each `voteX` launches on `scope`: awaits the store write, then (re)starts a
single debounce job. When the debounce elapses, `sync()` runs once and its
result is handed to `onSyncResult`. A new vote during the window cancels the
pending job and restarts the timer; it never cancels a sync that is already
in flight. `CancellationException` is rethrown, matching the rest of the sync
code.

### `FrozenOrder` (new, `mobile/ui/library`)

Pins the Library's display order to the order first observed from a
non-empty emission, so a vote's re-sort (net score DESC) doesn't move a card
under the user's finger. New ids append at the end; removed ids drop out.

### `LibraryViewModel` (modified)

Constructs a `LibraryEditor` with `RoomVoteStore(dao)`,
`{ PhoneSyncSender(app).syncMessagesToWatch() }`, and `viewModelScope`, and
exposes `voteUp(id)` / `voteDown(id)` plus a `SharedFlow<SyncResult>` of
auto-sync outcomes for the screen to surface. Nothing else in the ViewModel
changes.

### `LibraryScreen` (modified)

`InsultPanel` and `BubblegumPanel` take `onVoteUp: () -> Unit` and
`onVoteDown: () -> Unit`. The count text becomes two `IconButton`-style
controls with content descriptions ("Vote up", "Vote down") for
accessibility. The screen collects the auto-sync result flow and shows the
existing snackbar text for `Failed`; `Success`/`NoMessages` after an
auto-sync are silent (the manual button keeps its current messages).

### `PhoneSyncSender` (modified)

Filter becomes `.filter { it.isActive }`; the accompanying comment explains
why rejected rows are now sent (see *Sync filter change*).

## Data flow

1. User taps ▼ on a card → `viewModel.voteDown(id)` → `LibraryEditor.voteDown`.
2. `RoomVoteStore.voteDown` runs `UPDATE messages SET votesDown = votesDown + 1`.
   The Room flow emits; the card re-renders with the new count.
3. Debounce timer (re)starts. Further taps within 2 s repeat steps 1–2 and
   reset the timer.
4. Timer elapses → `PhoneSyncSender.syncMessagesToWatch()` writes the
   `/messages` DataItem with all active rows, including their vote counts.
5. Watch `WatchSyncReceiver` inserts with `REPLACE`; its copy now carries the
   phone's counts. Rows with `votesDown ≥ 3` are present but excluded by
   `getEligibleMessages`.
6. Any later watch vote increments from the synced value and flows back via
   `/votes` as before.

## Error handling

- **Store write fails:** logged; no sync is scheduled for that tap; the UI
  simply doesn't change. (Room write failures here are effectively
  disk-full; no user-facing message beyond the log.)
- **Auto-sync fails:** the vote is already committed. The screen shows the
  existing `"Sync failed: …"` snackbar. The next manual or automatic sync
  carries the vote. The result is held in a one-slot replay cache until a
  collector consumes it, so a failure that lands while the Library tab is
  off-screen is still shown when the user returns.
- **Watch not connected:** `putDataItem` still succeeds locally and the Data
  Layer delivers when the watch reconnects; no special handling.
- **Cancellation:** rethrown, never swallowed.

## Testing

JVM unit tests (`mobile/src/test/.../ui/library/LibraryEditorTest.kt`) using
`kotlinx-coroutines-test` with a `StandardTestDispatcher` and a fake
`VoteStore` that records calls, plus a counting fake `sync`:

- `voteUp` calls `store.voteUp(id)` exactly once with the right id; same for
  `voteDown`.
- Three votes inside the debounce window → exactly **one** sync, after the
  window.
- A vote after the window has elapsed → a second sync.
- `sync` returning `Failed` is delivered to `onSyncResult`; nothing throws.
- `sync` throwing `CancellationException` propagates (not swallowed).

`PhoneSyncSender` has no JVM test today (it touches Room + the Data Layer);
the filter change is covered by an emulator check rather than a new test
harness.

On-device (paired emulators): downvote a message three times on the phone,
confirm the watch's `WatchSyncReceiver` log shows the replaced batch, then
pull the watch DB and confirm the row has `votesDown = 3`.

## Known limitations (pre-existing or accepted, out of scope)

The watch never deletes rows. `AiMessageGenerator`'s prune (`deleteByIds`)
removes rows on the phone, but the watch keeps its copy and can keep firing
it until it is independently downvoted there. This feature does not make it
worse (nothing is deleted) and does not fix it. A later fix would have the
watch treat each `/messages` payload as authoritative and delete ids absent
from it.

- **Every `/messages` push resets the watch's 24 h repeat-suppression.** The
  payload carries `lastShownTimestamp`, which the phone never sets (only the
  watch's `MessageRepository.markShown` does), and the watch inserts with
  `REPLACE`. Manual Sync and post-generation sync already did this;
  auto-sync after votes makes it happen more often during a rating session,
  so expect more repeats on the wrist until a watch-side upsert that
  preserves `lastShownTimestamp` lands.
- **Reverse vote clobber.** The `/messages` payload also REPLACEs the
  watch's vote counts. If an auto-sync lands between a watch vote and the
  delivery of that watch's `/votes` snapshot, the watch's count is reverted
  and one wrist vote is lost. Needs concurrent phone and wrist voting;
  self-limiting. Auto-sync narrows the phone-side window but does not make
  convergence guaranteed in either direction — a versioned merge would.
- **Exit inside the debounce window.** The sync leg runs in
  `viewModelScope`; leaving the app within 2 s of a vote cancels the
  pending push. The vote is committed locally and the next sync (manual or
  automatic) carries the full table, so nothing is lost permanently.

## Out of scope (future)

- Delete-from-phone.
- Watch-side reconciliation of pruned rows (above).
- Import / export of insult sets (next feature; the flat
  `insults.json` record schema was designed for it).
