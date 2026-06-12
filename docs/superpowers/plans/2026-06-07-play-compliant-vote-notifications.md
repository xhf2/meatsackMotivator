# Play-Compliant Vote Notifications Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the full-screen-intent voting takeover with HIGH-importance notification action buttons (👎/👍) handled by a `BroadcastReceiver`, so every insult is votable directly on the notification — Play-compliant, no restricted permission.

**Architecture:** `InsultNotificationService` posts a notification carrying two vote-action `PendingIntent`s (broadcasts to a new `VoteReceiver`) plus a `contentIntent` to the existing `InsultActivity`. `VoteReceiver` records the vote on `MeatsackWearApp.applicationScope` via `MessageRepository` and dismisses the notification. All FSI machinery is removed. The existing debug-only `TestFireActivity` is repurposed to fire the real notification for on-device verification.

**Tech Stack:** Kotlin, AndroidX `NotificationCompat`, Room (`:shared`), JUnit (`:wear` unit tests). No new dependencies.

**Spec:** `docs/superpowers/specs/2026-06-07-play-compliant-vote-notification-design.md`

---

## File Structure

### New

| Path | Responsibility |
|---|---|
| `wear/src/main/java/com/meatsack/motivator/notification/VoteReceiver.kt` | BroadcastReceiver for vote actions + the pure `VoteAction`/`requestCode` contract. |
| `wear/src/test/java/com/meatsack/motivator/notification/VoteReceiverTest.kt` | Unit tests for `VoteAction.from` and `VoteReceiver.requestCode`. |
| `wear/src/main/res/drawable/ic_thumb_up.xml` | Vector icon for the 👍 action. |
| `wear/src/main/res/drawable/ic_thumb_down.xml` | Vector icon for the 👎 action. |

### Modified

| Path | Change |
|---|---|
| `wear/src/main/java/com/meatsack/motivator/notification/InsultNotificationService.kt` | Drop `setFullScreenIntent`; add vote actions + `contentIntent` + `BigTextStyle` + `setTimeoutAfter`; id = `message.id.toInt()`; category `REMINDER`. |
| `wear/src/main/AndroidManifest.xml` | Remove `USE_FULL_SCREEN_INTENT`; remove `showOnLockScreen`/`turnScreenOn` from `InsultActivity`; register `VoteReceiver`. |
| `wear/src/debug/java/com/meatsack/motivator/debug/TestFireActivity.kt` | Fire the real notification via `deliverInsult` instead of launching `InsultActivity` directly. |

### Already satisfied (no change)

- `MainActivity` already requests `POST_NOTIFICATIONS` at runtime (lines 18-21). Verified, no task.

---

## Pre-flight

- [ ] **Step 0: Confirm branch**

Run: `git branch --show-current`
Expected: `feature/play-compliant-vote-notifications`. If not: `git checkout feature/play-compliant-vote-notifications`.

---

## Task 1: Vote contract — `VoteAction` + `requestCode` (TDD, pure)

These are pure functions with no Android dependencies, so they're unit-tested first.

**Files:**
- Create: `wear/src/main/java/com/meatsack/motivator/notification/VoteReceiver.kt`
- Test: `wear/src/test/java/com/meatsack/motivator/notification/VoteReceiverTest.kt`

- [ ] **Step 1.1: Write the failing tests**

Create `wear/src/test/java/com/meatsack/motivator/notification/VoteReceiverTest.kt`:

```kotlin
package com.meatsack.motivator.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class VoteReceiverTest {

    @Test
    fun from_upVote_returnsUp() {
        assertEquals(VoteReceiver.VoteAction.Up(5L), VoteReceiver.VoteAction.from(5L, isUp = true))
    }

    @Test
    fun from_downVote_returnsDown() {
        assertEquals(VoteReceiver.VoteAction.Down(5L), VoteReceiver.VoteAction.from(5L, isUp = false))
    }

    @Test
    fun from_invalidId_returnsIgnore() {
        assertEquals(VoteReceiver.VoteAction.Ignore, VoteReceiver.VoteAction.from(0L, isUp = true))
        assertEquals(VoteReceiver.VoteAction.Ignore, VoteReceiver.VoteAction.from(-1L, isUp = false))
    }

    @Test
    fun requestCode_upAndDownDiffer_forSameMessage() {
        assertNotEquals(
            VoteReceiver.requestCode(7L, isUp = true),
            VoteReceiver.requestCode(7L, isUp = false),
        )
    }

    @Test
    fun requestCode_differsAcrossMessages() {
        assertNotEquals(
            VoteReceiver.requestCode(7L, isUp = true),
            VoteReceiver.requestCode(8L, isUp = true),
        )
    }
}
```

- [ ] **Step 1.2: Run the tests, expecting failure (unresolved reference)**

Run: `./gradlew :wear:testDebugUnitTest --tests "com.meatsack.motivator.notification.VoteReceiverTest"`
Expected: FAIL — `VoteReceiver` does not exist yet (compilation error).

- [ ] **Step 1.3: Create `VoteReceiver` with the pure contract only**

Create `wear/src/main/java/com/meatsack/motivator/notification/VoteReceiver.kt`:

```kotlin
package com.meatsack.motivator.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.meatsack.motivator.MeatsackWearApp
import com.meatsack.motivator.messages.MessageRepository
import com.meatsack.shared.db.AppDatabase
import kotlinx.coroutines.launch

/**
 * Records a 👍/👎 vote tapped directly on an insult notification, then dismisses
 * that notification. Runs on the app scope so the DB write outlives this receiver.
 */
class VoteReceiver : BroadcastReceiver() {

    /** Pure result of interpreting a vote intent — unit-testable without Android. */
    sealed class VoteAction {
        data class Up(val messageId: Long) : VoteAction()
        data class Down(val messageId: Long) : VoteAction()
        object Ignore : VoteAction()

        companion object {
            fun from(messageId: Long, isUp: Boolean): VoteAction = when {
                messageId <= 0L -> Ignore
                isUp -> Up(messageId)
                else -> Down(messageId)
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val messageId = intent.getLongExtra(InsultNotificationService.EXTRA_MESSAGE_ID, -1L)
        val isUp = intent.getBooleanExtra(EXTRA_VOTE_UP, true)
        val notifId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)

        // Acknowledge the tap immediately by dismissing the notification.
        if (notifId > 0) NotificationManagerCompat.from(context).cancel(notifId)

        val action = VoteAction.from(messageId, isUp)
        if (action is VoteAction.Ignore) return

        val app = context.applicationContext as MeatsackWearApp
        val pending = goAsync()
        app.applicationScope.launch {
            try {
                val repo = MessageRepository(AppDatabase.getDatabase(context).messageDao())
                when (action) {
                    is VoteAction.Up -> repo.voteUp(action.messageId)
                    is VoteAction.Down -> repo.voteDown(action.messageId)
                    VoteAction.Ignore -> Unit
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Vote write failed for id=$messageId", t)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "VoteReceiver"
        const val ACTION_VOTE = "com.meatsack.motivator.ACTION_VOTE"
        const val EXTRA_VOTE_UP = "vote_up"
        const val EXTRA_NOTIFICATION_ID = "notification_id"

        /**
         * Unique request code per (message, direction). FLAG_IMMUTABLE PendingIntents
         * with identical request codes would otherwise alias and reuse stale extras.
         */
        fun requestCode(messageId: Long, isUp: Boolean): Int =
            (messageId.toInt() shl 1) or (if (isUp) 1 else 0)
    }
}
```

- [ ] **Step 1.4: Run the tests, expecting pass**

Run: `./gradlew :wear:testDebugUnitTest --tests "com.meatsack.motivator.notification.VoteReceiverTest"`
Expected: PASS (5 tests).

- [ ] **Step 1.5: Commit**

```bash
git add wear/src/main/java/com/meatsack/motivator/notification/VoteReceiver.kt \
        wear/src/test/java/com/meatsack/motivator/notification/VoteReceiverTest.kt
git commit -m "feat(wear): VoteReceiver with testable vote contract"
```

---

## Task 2: Register `VoteReceiver` in the manifest

**Files:**
- Modify: `wear/src/main/AndroidManifest.xml`

- [ ] **Step 2.1: Add the receiver**

In `wear/src/main/AndroidManifest.xml`, inside `<application>` (after the
`WatchSettingsReceiver` `<service>` block, before `</application>`), add:

```xml
        <receiver
            android:name=".notification.VoteReceiver"
            android:exported="false" />
```

- [ ] **Step 2.2: Build to verify the manifest parses**

Run: `./gradlew :wear:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 2.3: Commit**

```bash
git add wear/src/main/AndroidManifest.xml
git commit -m "feat(wear): register VoteReceiver"
```

---

## Task 3: Vote-action vector drawables

**Files:**
- Create: `wear/src/main/res/drawable/ic_thumb_up.xml`
- Create: `wear/src/main/res/drawable/ic_thumb_down.xml`

- [ ] **Step 3.1: Create `ic_thumb_up.xml`**

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24"
    android:tint="#FFFFFF">
    <path
        android:fillColor="@android:color/white"
        android:pathData="M1,21h4V9H1V21zM23,10c0,-1.1,-0.9,-2,-2,-2h-6.31l0.95,-4.57 0.03,-0.32c0,-0.41,-0.17,-0.79,-0.44,-1.06L14.17,1 7.59,7.59C7.22,7.95 7,8.45 7,9v10c0,1.1 0.9,2 2,2h9c0.83,0 1.54,-0.5 1.84,-1.22l3.02,-7.05c0.09,-0.23 0.14,-0.47 0.14,-0.73v-2z" />
</vector>
```

- [ ] **Step 3.2: Create `ic_thumb_down.xml`**

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24"
    android:tint="#FFFFFF">
    <path
        android:fillColor="@android:color/white"
        android:pathData="M15,3H6c-0.83,0 -1.54,0.5 -1.84,1.22l-3.02,7.05C1.05,11.5 1,11.74 1,12v2c0,1.1 0.9,2 2,2h6.31l-0.95,4.57 -0.03,0.32c0,0.41 0.17,0.79 0.44,1.06L9.83,23l6.59,-6.59C16.78,16.05 17,15.55 17,15V5c0,-1.1 -0.9,-2 -2,-2zM19,3v12h4V3h-4z" />
</vector>
```

- [ ] **Step 3.3: Build to verify resources compile**

Run: `./gradlew :wear:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3.4: Commit**

```bash
git add wear/src/main/res/drawable/ic_thumb_up.xml wear/src/main/res/drawable/ic_thumb_down.xml
git commit -m "feat(wear): thumb up/down vector icons for vote actions"
```

---

## Task 4: Rework `InsultNotificationService` — notification with vote actions, no FSI

**Files:**
- Modify: `wear/src/main/java/com/meatsack/motivator/notification/InsultNotificationService.kt`

- [ ] **Step 4.1: Replace the imports and `showFullScreenNotification`**

Replace the entire file contents of
`wear/src/main/java/com/meatsack/motivator/notification/InsultNotificationService.kt`
with:

```kotlin
package com.meatsack.motivator.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.meatsack.motivator.R
import com.meatsack.motivator.presentation.InsultActivity
import com.meatsack.shared.model.Message

class InsultNotificationService(private val context: Context) {

    companion object {
        const val CHANNEL_ID = "meatsack_insults"
        const val EXTRA_MESSAGE_ID = "message_id"
        const val EXTRA_MESSAGE_TEXT = "message_text"
        const val EXTRA_STATS_TEXT = "stats_text"

        // Each insult is its own notification (id = message.id) and auto-expires so
        // the stream can't grow unbounded.
        private const val NOTIFICATION_TIMEOUT_MS = 30L * 60L * 1000L
    }

    init {
        createNotificationChannel()
    }

    fun deliverInsult(message: Message, statsText: String) {
        vibrate()
        showInsultNotification(message, statsText)
    }

    private fun vibrate() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            manager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        val effect = VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE)
        vibrator.vibrate(effect)
    }

    private fun showInsultNotification(message: Message, statsText: String) {
        val notifId = message.id.toInt()

        val contentIntent = PendingIntent.getActivity(
            context,
            notifId,
            Intent(context, InsultActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_MESSAGE_ID, message.id)
                putExtra(EXTRA_MESSAGE_TEXT, message.text)
                putExtra(EXTRA_STATS_TEXT, statsText)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(message.text)
            .setContentText(statsText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message.text))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setTimeoutAfter(NOTIFICATION_TIMEOUT_MS)
            .addAction(R.drawable.ic_thumb_down, "👎", votePendingIntent(message.id, notifId, isUp = false))
            .addAction(R.drawable.ic_thumb_up, "👍", votePendingIntent(message.id, notifId, isUp = true))
            .build()

        NotificationManagerCompat.from(context).notify(notifId, notification)
    }

    private fun votePendingIntent(messageId: Long, notifId: Int, isUp: Boolean): PendingIntent {
        val intent = Intent(context, VoteReceiver::class.java).apply {
            action = VoteReceiver.ACTION_VOTE
            putExtra(EXTRA_MESSAGE_ID, messageId)
            putExtra(VoteReceiver.EXTRA_VOTE_UP, isUp)
            putExtra(VoteReceiver.EXTRA_NOTIFICATION_ID, notifId)
        }
        return PendingIntent.getBroadcast(
            context,
            VoteReceiver.requestCode(messageId, isUp),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Insult Notifications",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Aggressive motivational messages"
            enableVibration(false)
        }
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }
}
```

Notes:
- `NOTIFICATION_ID` (the old constant `1`) is intentionally gone — id is now per-message.
- `EXTRA_MESSAGE_ID/TEXT/STATS` keys are unchanged, so `InsultActivity` and the debug
  activity keep working.

- [ ] **Step 4.2: Build**

Run: `./gradlew :wear:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4.3: Run the full wear unit suite (no regressions)**

Run: `./gradlew :wear:testDebugUnitTest`
Expected: PASS (includes `VoteReceiverTest`).

- [ ] **Step 4.4: Commit**

```bash
git add wear/src/main/java/com/meatsack/motivator/notification/InsultNotificationService.kt
git commit -m "feat(wear): deliver insults as votable notifications, drop full-screen intent"
```

---

## Task 5: Manifest cleanup — remove FSI permission and activity flags

**Files:**
- Modify: `wear/src/main/AndroidManifest.xml`

- [ ] **Step 5.1: Remove the FSI permission**

Delete this line from `wear/src/main/AndroidManifest.xml`:

```xml
    <uses-permission android:name="android.permission.USE_FULL_SCREEN_INTENT" />
```

- [ ] **Step 5.2: Remove the FSI-only activity flags**

Change the `InsultActivity` declaration from:

```xml
        <activity
            android:name=".presentation.InsultActivity"
            android:exported="false"
            android:showOnLockScreen="true"
            android:turnScreenOn="true"
            android:taskAffinity=""
            android:excludeFromRecents="true" />
```

to:

```xml
        <activity
            android:name=".presentation.InsultActivity"
            android:exported="false"
            android:taskAffinity=""
            android:excludeFromRecents="true" />
```

- [ ] **Step 5.3: Verify the merged release manifest has no FSI permission**

Run: `./gradlew :wear:processReleaseManifest` then inspect the merged manifest:
`grep -c "USE_FULL_SCREEN_INTENT" wear/build/intermediates/merged_manifest/release/AndroidManifest.xml`
Expected: `0` (or "file/match not found"). Also confirm `TestFireActivity` is absent:
`grep -c "TestFireActivity" wear/build/intermediates/merged_manifest/release/AndroidManifest.xml`
Expected: `0`.

(If the release manifest task name differs in this AGP version, use
`./gradlew :wear:assembleRelease` and inspect under `wear/build/intermediates/merged_manifest/release/`.)

- [ ] **Step 5.4: Commit**

```bash
git add wear/src/main/AndroidManifest.xml
git commit -m "chore(wear): remove USE_FULL_SCREEN_INTENT and FSI activity flags for Play compliance"
```

---

## Task 6: Repurpose `TestFireActivity` to fire the real notification

The existing debug-only `TestFireActivity` launches `InsultActivity` directly (old
full-screen path). Repoint it at the new notification path so the on-device test
exercises what users actually get, with a real votable message id.

**Files:**
- Modify: `wear/src/debug/java/com/meatsack/motivator/debug/TestFireActivity.kt`

- [ ] **Step 6.1: Replace the file**

Replace the entire contents of
`wear/src/debug/java/com/meatsack/motivator/debug/TestFireActivity.kt` with:

```kotlin
package com.meatsack.motivator.debug

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import com.meatsack.motivator.MeatsackWearApp
import com.meatsack.motivator.notification.InsultNotificationService
import com.meatsack.shared.constants.EscalationLevel
import com.meatsack.shared.constants.MessageSource
import com.meatsack.shared.constants.MessageTone
import com.meatsack.shared.constants.TriggerType
import com.meatsack.shared.db.AppDatabase
import com.meatsack.shared.model.Message
import kotlinx.coroutines.launch

/**
 * Debug-only. Fires a REAL insult notification (the production delivery path) so the
 * notification + 👎/👍 voting + wrist-raise surfacing can be verified on-device:
 *
 *   adb shell am start -n com.meatsack.motivator/.debug.TestFireActivity
 *
 * Picks the top message from the DB so the vote lands on a real row; falls back to a
 * throwaway message (id = -1, vote is a no-op) if the DB is empty.
 */
class TestFireActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val stats = intent.getStringExtra("stats") ?: "42 steps. TEST fire."
        val fallbackText = intent.getStringExtra("text")
            ?: "GET UP, you cloud-native pile of laundry."

        val app = application as MeatsackWearApp
        val notifier = InsultNotificationService(applicationContext)

        app.applicationScope.launch {
            val message = AppDatabase.getDatabase(applicationContext)
                .messageDao()
                .getAllMessages()
                .firstOrNull()
                ?: Message(
                    text = fallbackText,
                    level = EscalationLevel.AGGRESSIVE,
                    triggerType = TriggerType.INACTIVITY,
                    tone = MessageTone.FULL_SEND,
                    source = MessageSource.PRE_WRITTEN,
                )
            Log.d("TestFireActivity", "Firing test insult notification: ${message.text}")
            notifier.deliverInsult(message, stats)
        }

        finish()
    }
}
```

- [ ] **Step 6.2: Build the debug variant**

Run: `./gradlew :wear:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6.3: Commit**

```bash
git add wear/src/debug/java/com/meatsack/motivator/debug/TestFireActivity.kt
git commit -m "test(wear): debug TestFireActivity fires the real vote notification"
```

---

## Task 7: Full build, unit sweep, and on-device verification

**Files:** none modified.

- [ ] **Step 7.1: Full build (catches spotless/lint regressions)**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL. If spotless fails: `./gradlew spotlessApply && git add -u && git commit -m "style: spotless"`.

- [ ] **Step 7.2: Full wear unit suite**

Run: `./gradlew :wear:testDebugUnitTest`
Expected: all green, including `VoteReceiverTest`.

- [ ] **Step 7.3: Install the debug build on the watch**

```bash
./gradlew :wear:assembleDebug
adb -s <watch-id> install -r wear/build/outputs/apk/debug/wear-debug.apk
```

(`<watch-id>` is the current `adb devices` entry for the watch, e.g. `192.168.1.109:<port>`.)

- [ ] **Step 7.4: Fire the real notification and verify the flow**

```bash
adb -s <watch-id> shell am start -n com.meatsack.motivator/.debug.TestFireActivity
```

Verify on the watch:
1. It **vibrates** and the insult **peeks / appears** in the notification stream.
2. **Raise your wrist** → the notification surfaces filling the screen with the insult
   text and **👎 / 👍** buttons.
3. **Tap 👍** → the notification dismisses. Confirm the DB write in logcat:
   `adb -s <watch-id> logcat -d | grep -iE "VoteReceiver|Vote write"` (no error logged).
4. Fire again, then **tap the notification body** → `InsultActivity` opens (centered
   insult + buttons) → tapping a button votes and closes.

- [ ] **Step 7.5: Confirm the appop is no longer needed**

The app no longer declares `USE_FULL_SCREEN_INTENT`. Confirm voting takes over via
the notification **without** any FSI grant:
`adb -s <watch-id> shell cmd appops get com.meatsack.motivator USE_FULL_SCREEN_INTENT`
Expected: the value is irrelevant to behavior now (permission not declared). The
notification + vote flow in 7.4 must work regardless.

- [ ] **Step 7.6: Push the branch**

```bash
git push -u origin feature/play-compliant-vote-notifications
```

- [ ] **Step 7.7: Open the PR**

```bash
gh pr create --title "Play-compliant vote notifications (drop full-screen intent)" --body "$(cat <<'EOF'
## Summary
- Insults are now delivered as HIGH-importance notifications with inline 👎/👍 action buttons, handled by a new `VoteReceiver`. Voting works directly on the notification — read & vote on wrist-raise, no screen takeover.
- Removed all full-screen-intent machinery (`setFullScreenIntent`, `USE_FULL_SCREEN_INTENT` permission, `showOnLockScreen`/`turnScreenOn`) — the Play-restricted path that was being demoted on Android 14+.
- Each insult is its own notification (`id = message.id`), auto-expiring after 30 min; tapping the body still opens the centered `InsultActivity`.
- Debug-only `TestFireActivity` now fires the real notification for on-device verification.

## Why
On Android 14+, `USE_FULL_SCREEN_INTENT` is denied-by-default for non-alarm apps, so the voting takeover was demoted to a silent stream entry whenever the watch screen was on. That permission is also Play-restricted. Notification actions are the Play-safe, idiomatic Wear pattern.

Spec: `docs/superpowers/specs/2026-06-07-play-compliant-vote-notification-design.md`

## Test plan
- [x] `./gradlew build` — BUILD SUCCESSFUL
- [x] `./gradlew :wear:testDebugUnitTest` — green (incl. new `VoteReceiverTest`)
- [x] On-device: `am start .debug.TestFireActivity` → notification surfaces on raise → 👍/👎 vote records + dismisses → tap body opens `InsultActivity`
- [x] Merged release manifest contains no `USE_FULL_SCREEN_INTENT` / `TestFireActivity`

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

- [ ] **Step 7.8: Run PR review per project convention**

After `gh pr create`, invoke `pr-review-toolkit:review-pr` on the new PR (per the
repo's memory/CLAUDE conventions). This PR is small (~7 files); a single
code-reviewer pass is sufficient.

---

## Testing note — why no `Notification`-builder unit test

The spec floated asserting the built notification's shape (2 actions, no
`fullScreenIntent`, timeout set). Doing that in a JVM unit test needs Robolectric,
which is **not** a current dependency — adding it is out of proportion for this
change. Instead: the **pure `requestCode`/`VoteAction` logic is unit-tested** (Task 1,
the part with real branching), and the **assembled notification is verified on-device**
(Task 7.4) plus the **merged-manifest check** confirms no FSI (Task 5.3). This trades
one brittle, dependency-heavy unit test for direct runtime evidence.

## Risks / known unknowns

- **Wrist-raise surfacing of an app-local HIGH notification** is expected to match
  bridged messaging notifications (same importance tier, confirmed enabled on the
  device) but was not provable pre-build; Step 7.4 is the definitive check.
- **`message.id.toInt()`** assumes ids fit `Int` (true for this app's autoincrement
  range). Documented in the spec.
- **Notification action rendering on Wear**: titles use emoji + thumb vector icons;
  if the watch renders only icons, the thumbs still disambiguate.

## Out of scope (tracked separately)

- Vote back-sync watch → phone (existing v1 limitation).
- The BehindPace/EndOfDay worker self-cancel concern (REPLACE-while-running) — needs
  its own systematic-debugging pass and spec.
