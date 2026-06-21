# Branded insult notifications (watch)

**Date:** 2026-06-14
**Branch:** `feature/whatsapp-insult-notifications`
**Status:** Implemented (design evolved during on-device testing — see "Design evolution")

## Problem

The watch's inactivity insult posted as a generic system notification fronted by
`android.R.drawable.ic_dialog_alert` (the gray "⚠️ dialog alert" triangle) with no
branding. We want a branded, WhatsApp-style card: the app icon, the sender name, and
the insult, tappable into the full-screen app to vote.

Must stay **Google Play–compliant**. PR #39 (`e82d56d`, `1e5915d`) removed full-screen
intent (FSI) for compliance; this work does **not** reintroduce it.

## Final design (as shipped)

A **plain branded `NotificationCompat`** card — not MessagingStyle (see "Design evolution").

- **Small icon:** new `R.drawable.ic_notification` (white dumbbell), replacing
  `ic_dialog_alert`.
- **Large icon:** the app launcher icon (`R.mipmap.ic_launcher`) rendered to a bitmap via
  `toBitmap()`, so the brand icon shows **on the collapsed card** and masks cleanly.
- **Accent:** `setColor(R.color.brand_red)` (`#FFFF3B30`, added to `colors.xml`; previously
  only in the Compose theme).
- **Content:** title = "meatsackMotivator", text = the insult; `BigTextStyle` so the full
  (≤100-char) insult is readable un-truncated in the detail view.
- **Category:** `CATEGORY_MESSAGE`.
- **No inline actions, no MessagingStyle, no FSI, no RemoteInput reply.** Voting happens
  only in `InsultActivity`.
- **Tap → `contentIntent` → `InsultActivity`** (full insult + 👍/👎), carrying
  `EXTRA_MESSAGE_ID` / `EXTRA_MESSAGE_TEXT` / `EXTRA_STATS_TEXT`.

### Interaction (compliant reality)

1. Inactivity fires → branded card slides over the watch face (app icon + insult preview).
2. Wrist raise wakes the screen → user sees the card.
3. Tap card → Wear detail view (insult, "Open app") → **Open app** → `InsultActivity` → vote.

The card→detail→**Open app** step is a **Samsung One UI Watch system behavior**: a
notification's first tap always opens the shade detail view; the `contentIntent` launches
via "Open app". No app-side configuration suppresses this. Direct tap-to-app would require
FSI, which is not Play-compliant for this app category, so it was deliberately not used
(see "Design evolution").

### Preserved

`POST_NOTIFICATIONS` guard, `notifId = message.id.toInt()`, `INSULT_TAG`,
`setAutoCancel`, `setTimeoutAfter(30 min)`, vibration, channel `IMPORTANCE_HIGH`.

## Components

| Unit | Responsibility | Change |
| --- | --- | --- |
| `InsultNotificationService.showInsultNotification` | Build + post the branded card | Rewritten to a plain branded notification |
| `ic_notification.xml` | Dumbbell small-icon glyph | New asset |
| `colors.xml` | `brand_red` accent | New value |
| `VoteReceiver` | Notification-action vote handler | **Orphaned** by removing inline actions; retained per user request (see PR notes) |

## Testing

Framework-heavy, so verified **on the physical SM-L320** (wireless adb → `TestFireActivity`
→ `dumpsys notification`): branded card, app-icon large icon, `category=msg`,
`fullscreenIntent=null`, no inline actions, tap → `InsultActivity`, vote → DAO write. No
unit test (the earlier pure `insultBubbles` helper was removed with MessagingStyle).

## Design evolution (why this isn't MessagingStyle)

The design changed materially during on-device testing — recorded here so the history is
clear:

1. **Started with `MessagingStyle`** (sender "meatsackMotivator", app-icon avatar, insult +
   stats as two message bubbles, inline 👍/👎). Looked right in mockups.
2. **On-device finding A — crash:** `MessagingStyle`'s constructor throws
   `IllegalArgumentException("User's name must not be empty.")` if the local-user `Person`
   has a blank name. (A code-review suggestion to blank the name was invalid; reverted.)
3. **On-device finding B — avatar:** the `MessagingStyle` avatar only renders in the
   *expanded* view, never on the collapsed card, so the card showed only the dumbbell, not
   the app icon. Fixed by switching to a plain notification with `setLargeIcon`.
4. **On-device finding C — the core one:** tapping the card opens the One UI Watch detail
   view (with the inline votes), requiring a second "Open app" tap to reach the app. The
   user wanted tap → app directly with no inline voting. Removing MessagingStyle and the
   inline actions did **not** change this — it is a system-level behavior (WhatsApp behaves
   identically on this watch).
5. **Distribution decision:** the only way to get a direct screen takeover is FSI. The user
   chose to **stay Play-compliant** and accept the 2-tap flow rather than use FSI. So the
   final design is the plain branded card above; the MessagingStyle bubbles and inline
   voting were dropped.

## Out of scope / follow-ups

- `VoteReceiver` + its test + manifest registration are now dead code (kept per user
  request). Candidate for a future cleanup PR.
- A dedicated avatar art (currently reuses the launcher icon).
