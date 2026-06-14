# WhatsApp-style insult notifications (watch)

**Date:** 2026-06-14
**Branch:** `feature/whatsapp-insult-notifications`
**Status:** Approved design — ready for implementation plan

## Problem

The watch's inactivity insult currently posts as a generic system notification: a
gray card fronted by `android.R.drawable.ic_dialog_alert` (the "⚠️ dialog alert"
triangle). It reads like an error dialog, not a message. We want it to look and
behave like a WhatsApp message: a branded card that slides over the watch face with
a message preview, which the user taps to take over the screen with the full insult
and two vote thumbs.

This must stay **Google Play–compliant**. We deliberately removed full-screen-intent
(FSI) behavior in PR #39 (`e82d56d`, `1e5915d`). This redesign does **not** reintroduce
FSI — auto-launching an activity on wrist raise is not compliant. The compliant
WhatsApp interaction is: card appears → wrist raise wakes the screen so the user sees
the card → user **taps** the card to open the full screen.

## Scope

Presentation-only change to the watch notification. **In scope:** how the insult is
rendered as a notification. **Out of scope (unchanged):**

- Trigger path: `MeatsackWearService` → `EscalationManager` → `MessageRepository`.
- Vote plumbing: `VoteReceiver`, `votePendingIntent`, DAO writes.
- Full-screen `InsultActivity` / `InsultScreen` (the tap target).
- Manifest permissions — **no** new permissions, **no** `USE_FULL_SCREEN_INTENT`.

Bug #29 (settings syncer stops permanently after 3 retries) is **a separate track**
on its own branch (`fix/settings-syncer-permanent-stop`) — not part of this spec.

## Design

### Interaction model (WhatsApp-compliant)

1. Inactivity fires → branded `MessagingStyle` notification **card** slides over the
   watch face showing the insult preview + 👍/👎 inline actions.
2. Wrist raise wakes the screen → user sees the already-posted card. **No auto-launch.**
3. **Tap** the card → existing `contentIntent` opens `InsultActivity` full-screen
   (full insult + two big thumbs), exactly as today.

### The card — `NotificationCompat.MessagingStyle`

Model the angry coach as a **message sender**:

- A `Person` named **"meatsackMotivator"** with an avatar.
- Avatar icon = the app launcher icon, `IconCompat.createWithResource(context, R.mipmap.ic_launcher)`
  (same icon as the phone + watch apps — zero new art).
- `MessagingStyle(you)` with the insult delivered as incoming messages from that Person:
  - **Bubble 1** = `message.text` (the insult).
  - **Bubble 2** = `statsText` (e.g. "438 steps. It's 2pm. Pathetic.") as a follow-up
    message from the same sender — reads as the coach piling on, and gives stats the
    home that `BigTextStyle.setSummaryText` used to provide.
  - If `statsText` is blank, **omit bubble 2** (don't post an empty message).
- **No `RemoteInput` reply action.** MessagingStyle permits a reply but does not require
  one; we deliberately omit it. The only actions remain 👍/👎 via the existing
  `votePendingIntent`s (unchanged).
- `setCategory(NotificationCompat.CATEGORY_MESSAGE)` (was `CATEGORY_REMINDER`) so Wear
  treats it as a message card.

### New / changed assets (this is the actual fix for the gray triangle)

- **`wear/res/drawable/ic_notification.xml`** — a new monochrome, white-on-transparent
  vector glyph (dumbbell) used as `setSmallIcon(...)`, replacing
  `android.R.drawable.ic_dialog_alert`. That one line **is** the gray triangle; this
  removes it.
- **`wear/res/values/colors.xml`** — add `brand_red = #FF3B30` (currently only defined
  in the Compose theme `MeatsackColors.primary`). Apply via `.setColor(...)` for the
  accent tint on the small icon / card chrome.
- Avatar reuses `R.mipmap.ic_launcher` — no new file.

### Preserved from current implementation

`notifId = message.id.toInt()`, `INSULT_TAG`, the `POST_NOTIFICATIONS` runtime-permission
guard, `setAutoCancel(true)`, `setTimeoutAfter(30 min)`, vibration, channel
`IMPORTANCE_HIGH`, and `contentIntent` → `InsultActivity`.

## Components

| Unit | Responsibility | Change |
| --- | --- | --- |
| `InsultNotificationService` | Build + post the insult notification | Rewrite `showInsultNotification` to use `MessagingStyle`; swap small icon + color + category |
| `buildBubbles(message, statsText)` (new, pure) | Produce the ordered list of message bubbles from an insult + stats | New, unit-tested |
| `ic_notification.xml` | Monochrome small-icon glyph | New asset |
| `colors.xml` | `brand_red` accent | New value |

The pure `buildBubbles` helper isolates the only non-framework logic (which bubbles, in
what order, omitting empty stats) so it can be unit-tested without Robolectric.

## Error handling / edge cases

- **`POST_NOTIFICATIONS` denied** → keep the existing early-return + `Log.w` (unchanged).
- **Blank `statsText`** → single bubble only.
- **Bubble timestamps** → `addMessage` requires a timestamp; pass it in (injectable /
  parameter) rather than reading wall-clock inline, so `buildBubbles` is deterministic
  under test.
- **Adaptive-icon avatar** → `ic_launcher` is adaptive; `MessagingStyle` circle-masks the
  avatar. `createWithResource` usually renders fine, but Samsung One UI Watch may clip
  the foreground. **Verification checkpoint** on the physical SM-L320; one-line fallback
  to `createWithAdaptiveBitmap` if needed.

## Testing

- **Unit:** `buildBubbles(message, statsText)` — insult-only when stats blank; insult +
  stats (in order) when present. Pure function, JUnit, no emulator.
- **Manual on-device** (primary, consistent with how this framework-heavy service is
  verified): fire via `TestFireActivity`, confirm the card via `dumpsys notification`
  (sender = meatsackMotivator, two `MessagingStyle` messages, small icon = `ic_notification`,
  no FSI flags), tap → `InsultActivity`, vote → DAO write + dismiss. Confirm the avatar
  renders cleanly in the circle on the SM-L320.

## Compliance check

- No `USE_FULL_SCREEN_INTENT` permission, no FSI activity flags, no auto-launch.
- Standard `NotificationCompat` APIs only. Same compliance posture as PR #39 — this is a
  styling change within the already-compliant notification model.

## Out of scope / future

- Dedicated "angry face" avatar art (currently reuses launcher icon).
- Inline voice/text reply (intentionally omitted — votes are the interaction).
