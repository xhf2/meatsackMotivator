# Play-Compliant Vote Notification — Design

**Date:** 2026-06-07
**Status:** Approved (brainstorming) — pending implementation plan
**Branch:** `feature/play-compliant-vote-notifications`

## Problem

On the watch, most insults fire but never present the 👍/👎 voting UI — they only
vibrate and sit in the notification stream. Some (long-idle "2-hour" insults) do
take over the screen.

### Root cause (confirmed)

The voting UI is delivered **exclusively** via `setFullScreenIntent` (FSI). On
Android 14+ the `USE_FULL_SCREEN_INTENT` permission is denied-by-default for
non-alarm/non-calling apps, so the OS **demotes the FSI to a heads-up notification
whenever the watch screen is on**, and only launches the full-screen activity when
the screen is off/locked. The real Galaxy Watch runs Android 16 (API 36); the FSI
code is unchanged since v1, so this is an OS-behavior expression, not a code
regression. (Evidence: appop `USE_FULL_SCREEN_INTENT: default` with a recent
`rejectTime`; single FSI-only delivery path in `InsultNotificationService`.)

### Why a redesign, not a permission grant

The app is targeted for the **Google Play Store**. `USE_FULL_SCREEN_INTENT` is a
Play-restricted permission (alarms/calling only); declaring it risks rejection.
Forcing a full-screen activity from the background on a timer/inactivity is exactly
what Play's disruptive-behavior policy prohibits, regardless of mechanism
(`SYSTEM_ALERT_WINDOW` included). The Play-safe model is **notification action
buttons**: vote directly on the notification, no screen takeover, no restricted
permission.

## Goals

- Every insult is **votable** (👍/👎) directly from the notification — Play-compliant.
- Reproduce the desired "WhatsApp-style" experience: the insult **peeks** onto the
  watch face when it fires and the notification **fills the screen with the vote
  buttons when the user raises their wrist** (this is the OS surfacing a
  HIGH-importance notification — not an app screen takeover).
- Keep the full-screen `InsultActivity` (centered insult + buttons) as an **optional
  tap-to-open** view, reached via a normal `contentIntent` (no special permission).
- Remove all FSI machinery so there is no restricted permission and no grant to lose
  on reinstall.
- Add a **debug-only fire trigger** so the behavior can be verified on-device on
  demand, now and in future.

## Non-goals / out of scope

- Back-syncing votes from watch → phone (existing v1 limitation; unchanged).
- Centering text **in the notification** (system-styled, not controllable on Wear).
  Centering remains in the tap-to-open `InsultActivity` only.
- The separate **worker self-cancel** concern (BehindPace/EndOfDay re-enqueue with
  `ExistingWorkPolicy.REPLACE` at the top of `doWork`, which may cancel the running
  instance before `deliverInsult`). Flagged for a separate investigation/spec.
- Forcing notification surfacing when the user's watch settings (DND, Bedtime,
  notification pop-ups) suppress it — that is user/OS configuration.

## Approach (chosen)

Vote taps are handled by a lightweight **`BroadcastReceiver`** (no UI, no activity
flash). Each vote button is a broadcast `PendingIntent`; the receiver records the
vote on the existing `applicationScope` and cancels the notification. The
full-screen `InsultActivity` stays, reached by tapping the notification body, and
writes votes through the **same** `MessageRepository` — one source of truth for vote
writes.

Rejected alternatives: invisible/translucent Activity (flicker, heavier); Service
(foreground-service ceremony for a trivial DB increment).

## Components

### New

| Component | Location | Responsibility |
|---|---|---|
| `VoteReceiver : BroadcastReceiver` | `wear/.../notification/` (`exported="false"`) | Handle vote actions. Read `messageId` + direction + notification id; `goAsync()`; record vote via `MessageRepository` on `applicationScope`; cancel the notification; `finish()`. |
| `DebugFireReceiver : BroadcastReceiver` | `wear/src/debug/.../` (debug source set only, `exported="true"`) | On `com.meatsack.motivator.DEBUG_FIRE_INSULT`, select a real message and call `deliverInsult(...)`. Compiled into debug builds only; physically absent from release. |

### Modified

- **`InsultNotificationService`** — `showFullScreenNotification` → `showInsultNotification`:
  - **Remove** `setFullScreenIntent(...)`.
  - Add two `NotificationCompat.Action` (👎/👍) → broadcast `PendingIntent`s to
    `VoteReceiver` carrying `EXTRA_MESSAGE_ID`, a vote-direction extra, and the
    notification id.
  - Add `contentIntent` → `InsultActivity` (plain tap-to-open).
  - `setContentTitle(insult)` + `setStyle(BigTextStyle().bigText(insult))` so the
    full insult is readable when the notification surfaces on raise (single-line
    titles truncate; the #38 100-char cap keeps it bounded). Stats line as
    `setContentText(stats)`.
  - `setTimeoutAfter(30 * 60 * 1000L)`, keep `setAutoCancel(true)`.
  - Notification id = `message.id.toInt()` (identical re-fires dedupe; distinct
    insults stack and each is independently votable).
  - Keep `vibrate()`; keep channel importance `HIGH`; change
    `CATEGORY_ALARM` → `CATEGORY_REMINDER`.
- **`presentation/MainActivity`** — also request `POST_NOTIFICATIONS` at runtime
  (Android 13+) alongside the existing `ACTIVITY_RECOGNITION` request, so
  notifications appear on fresh installs.
- **`wear/src/main/AndroidManifest.xml`**:
  - Remove `<uses-permission android:name="android.permission.USE_FULL_SCREEN_INTENT" />`.
  - Remove `android:showOnLockScreen` and `android:turnScreenOn` from `InsultActivity`
    (only needed for the FSI takeover).
  - Register `VoteReceiver` (`exported="false"`).

### Unchanged

- `InsultActivity` + `InsultScreen` (still the tap-to-open full view; `InsultScreen`
  already centers text). Votes flow through `MessageRepository`.
- `MessageRepository`, `MessageDao`, `Message` schema, the 3-downvote retirement.
- Trigger sources (inactivity poll, BehindPace/EndOfDay workers).

## Data flow

1. **Trigger** (inactivity poll / behind-pace / end-of-day worker / debug broadcast)
   → `MessageRepository.selectMessage(...)` → `InsultNotificationService.deliverInsult(message, stats)`.
2. `deliverInsult` → `vibrate()` + `showInsultNotification(message, stats)`.
3. `showInsultNotification` builds a HIGH-importance notification: title = insult,
   body = stats, two vote actions, `contentIntent` → `InsultActivity`,
   `setTimeoutAfter(30 min)`, `setAutoCancel(true)`, id = `message.id.toInt()`; posts
   via `NotificationManagerCompat.notify(...)`.
4. **On the watch:** it vibrates and peeks onto the watch face. Raising the wrist
   surfaces the notification (HIGH importance) filling the screen with the insult +
   👎/👍 — the WhatsApp-style read-and-vote-on-raise experience.
5a. **Tap 👎/👍** → `VoteReceiver.onReceive` → `goAsync()` →
    `applicationScope.launch { repo.voteUp/voteDown(messageId) }` →
    `NotificationManagerCompat.cancel(notifId)` → `pendingResult.finish()`.
5b. **Tap card body** → `InsultActivity` opens (centered) → votes through the same
    `MessageRepository`; `autoCancel` clears the notification.
6. **Untouched** → notification self-dismisses after 30 minutes.

## Error handling & edge cases

- **Invalid/missing `messageId`** (≤ 0) in `VoteReceiver` → no-op, still `finish()`
  (mirrors `InsultActivity.recordVote` guard).
- **DB write failure** → wrapped in try/catch, logged at ERROR, does not crash. The
  notification is cancelled after the attempt (success or failure) so the user's tap
  is acknowledged. `pendingResult.finish()` runs in `finally`.
- **`goAsync()` window** (~10 s) is ample for a single increment.
- **Notification id collision** — `message.id.toInt()`: `Message.id` is an
  auto-generated `Long`, in practice small; documented assumption that it fits `Int`.
  Identical message re-fire replaces its own prior notification (acceptable dedupe).
- **PendingIntent request codes** must be unique per (message, direction) under
  `FLAG_IMMUTABLE` to avoid stale-extra reuse; encode messageId + direction into the
  request code; `contentIntent` gets its own. Use `FLAG_IMMUTABLE | FLAG_UPDATE_CURRENT`.
- **`POST_NOTIFICATIONS` denied** (Android 13+) → notifications silently don't show;
  `MainActivity` requests it. If denied, that's the user's choice (no forced
  re-prompt this iteration).
- **Debug-fire with empty DB** → log and no-op.

## Testing

### Unit (JVM, no device)

- Extract a **pure parsing function** + a `VoteSink` interface for `VoteReceiver`
  (mirroring `WatchSettingsReceiver.applySnapshot`/`ApplySink`), so we test without an
  Android runtime:
  - up action → `sink.voteUp(id)`; down action → `sink.voteDown(id)`.
  - missing/invalid id → no sink call.
- Extract notification configuration into a testable data holder consumed by the
  builder; assert: exactly 2 vote actions present, **no** `fullScreenIntent`,
  `timeoutAfter` set, `contentIntent` present, correct extras/request codes.
- Existing `MessageRepository`/`MessageSerializer` tests remain green.

### On-device (debug-fire trigger)

`adb shell am broadcast -a com.meatsack.motivator.DEBUG_FIRE_INSULT` →
1. real HIGH notification fires → raise wrist → confirm it surfaces with 👎/👍;
2. tap a vote → confirm DB increment (logcat) + notification dismissed;
3. tap body → `InsultActivity` opens (centered) → vote works;
4. confirm the **merged release manifest** contains no `USE_FULL_SCREEN_INTENT` and
   no `DebugFireReceiver`.

## Play-compliance checklist

- [ ] No `USE_FULL_SCREEN_INTENT` permission in the release manifest.
- [ ] No `setFullScreenIntent(...)` in code.
- [ ] `DebugFireReceiver` present only in the `debug` source set.
- [ ] Notification category `REMINDER` (not `ALARM`).
- [ ] `POST_NOTIFICATIONS` requested at runtime.
