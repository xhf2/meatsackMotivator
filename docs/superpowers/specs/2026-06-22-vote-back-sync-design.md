# Watch→phone vote back-sync

**Date:** 2026-06-22
**Branch:** `feature/vote-back-sync`
**Status:** Designed — approved, proceeding to plan

## Problem

Votes are recorded only on the watch (`InsultActivity` 👍/👎 → `MessageRepository.voteUp/voteDown`
→ `MessageDao` increment). The phone `LibraryScreen` already *displays* `👍 votesUp 👎 votesDown`
and reads via the reactive `getAllMessagesFlow()`, but nothing watch-originated ever reaches the
phone: the Wear Data Layer sync is one-way (phone→watch, `/messages`), and there is no phone-side
`WearableListenerService`. So watch votes never show up in the phone Library.

## Goal

Flow vote counts from the watch back to the phone so the Library reflects them, by adding a new
`/votes` Data Layer channel that mirrors the existing forward pipe in reverse.

## Final design

### Architecture — reverse of the existing forward sync

- **Watch side (sender):** after a vote, push the watch's current vote counts as a `/votes` DataItem.
- **Phone side (receiver):** a new `WearableListenerService` applies those counts to the phone DB.
- **Phone Library:** unchanged — `getAllMessagesFlow()` is reactive, so it refreshes live once the
  phone DB updates.

### The id-match invariant (load-bearing)

Back-sync keys on the message `id`. This is safe only because **every message is born on the phone**:
both DBs seed from the identical `SeedData` list in the same order (so Room auto-generated ids match,
1..N), and AI/custom messages are created on the phone and synced down with explicit ids. The watch
never mints an id independently. Therefore "watch says message 42 now has 5 upvotes" maps correctly to
phone row 42. If the watch ever creates messages locally, this scheme breaks — keep id minting
phone-only.

### Absolute counts, not deltas

The watch sends its **current** `votesUp`/`votesDown`; the phone **sets** them (absolute `UPDATE`).
Idempotent under Data Layer redelivery (re-applying the same snapshot is a no-op), whereas a delta
scheme would double-count on duplicate delivery. `KEY_TIMESTAMP` is included so a vote that returns to
a previously-sent snapshot (e.g. up then down) still produces distinct content and fires
`onDataChanged` (same rationale as `/messages`; deliberately unlike `/settings`, which omits the
timestamp).

### Trigger

Automatic, fire-and-forget, **after each vote** on the watch (in `InsultActivity.recordVote`, on the
app scope so it outlives the activity). Votes appear in the phone Library within ~1s. The orphaned
`VoteReceiver` (notification-action handler, currently dead code) is *not* wired — noted for if it is
ever revived.

### Conflict handling — eventual consistency

Votes originate only on the watch (the vote authority); the phone is a mirror. The existing manual
"Sync to Watch" (`/messages`) carries the phone's vote counts and `insertAll(REPLACE)`s them on the
watch — back-sync keeps the phone's counts current, so the forward sync then carries accurate values.
The only anomaly is voting on the watch and tapping "Sync to Watch" in the sub-second before back-sync
lands; it self-heals on the next vote. Accepted; not worth merging-on-REPLACE complexity for v1.

## Components

| Module | File | Change |
| --- | --- | --- |
| shared | `sync/SyncChannel.kt` | Add `PATH_VOTES = "/votes"`, `KEY_VOTE_DATA = "vote_data"` |
| shared | `sync/VoteSyncSerializer.kt` (new) | `VoteSnapshot(id, votesUp, votesDown)` + `serialize`/`deserialize` (`id\|up\|down` per line, drops malformed lines) — pure, unit-tested |
| shared | `db/MessageDao.kt` | Add `getVotedMessages()` (`WHERE votesUp > 0 OR votesDown > 0`) and `setVotes(messageId, votesUp, votesDown)` (absolute `UPDATE`) |
| wear | `sync/WatchVoteSender.kt` (new) | Read voted messages → `VoteSnapshot` → serialize → write `/votes` DataItem (`setUrgent`, timestamp). Mirrors `PhoneSyncSender` (sealed result + cancellation-safe catch) |
| wear | `presentation/InsultActivity.kt` | After the existing `repo.voteUp/voteDown` in `recordVote`, fire `WatchVoteSender` on the app scope |
| mobile | `sync/PhoneVoteReceiver.kt` (new) | `WearableListenerService`; on `/votes`, deserialize and `dao.setVotes(...)` per id on `MeatsackMobileApp.applicationScope`; size-bounded. Mirrors `WatchSyncReceiver` |
| mobile | `AndroidManifest.xml` | Register `PhoneVoteReceiver` as `<service android:exported="true">` with a `DATA_CHANGED` intent-filter on `/votes` (the phone's first service) |

### Serialization format (`/votes`)

One vote per line, three `|`-delimited fields, `\n`-separated:

```
<id:Long>|<votesUp:Int>|<votesDown:Int>
```

Example: `42|5|2\n7|0|1`. Malformed lines are dropped with a warning (mirrors `MessageSerializer`).
Only messages with `votesUp > 0 || votesDown > 0` are sent (leaner; unvoted rows already read 0/0 on
the phone).

## Data flow

1. User taps 👍/👎 in `InsultActivity` on the watch → `MessageRepository.voteUp/voteDown` (DAO increment).
2. `recordVote` then calls `WatchVoteSender.syncVotesToPhone()` on the app scope → writes `/votes` DataItem.
3. Phone `PhoneVoteReceiver.onDataChanged` filters `/votes`, deserializes, applies `dao.setVotes(id, up, down)` per row.
4. Phone `LibraryViewModel` (`getAllMessagesFlow()`) emits the updated list → `LibraryScreen` shows new counts.

## Error handling

- `WatchVoteSender`: try/catch around the `putDataItem().await()`, rethrow `CancellationException`,
  log+swallow others, return a sealed result (mirrors `PhoneSyncSender.SyncResult`). A failed back-sync
  is non-fatal — the vote is already recorded locally and the next vote re-sends the full snapshot.
- `PhoneVoteReceiver`: null/empty/oversized payloads logged and skipped (mirror `WatchSyncReceiver`'s
  `MAX_INCOMING_MESSAGES` guard); per-row apply wrapped so one bad row doesn't abort the batch.

## Testing

- **`VoteSyncSerializer`** unit tests: round-trip (multiple rows incl. zero values), malformed-line
  tolerance, empty input → empty list.
- **`MessageDao.setVotes`/`getVotedMessages`**: Room queries — extend the existing
  `shared:connectedAndroidTest` (emulator) if practical; otherwise verified on-device.
- **`WatchVoteSender`/`PhoneVoteReceiver`**: framework-bound (DataClient / WearableListenerService),
  no unit harness in this project — verified on the paired emulators, like the existing forward
  sender/receiver.
- **Manual (emulator):** vote on the watch → phone `PhoneVoteReceiver` logs the applied snapshot → phone
  Library count updates live.

## Out of scope / notes

- The orphaned `VoteReceiver` is not wired into back-sync (dead code; revive-and-wire later if needed).
- No phone-side UI change — the Library already renders the counts.
- `votesDown >= 3` deactivation logic is unchanged; back-sync simply reflects the counts so the phone
  shows the same picture the watch uses for eligibility.
