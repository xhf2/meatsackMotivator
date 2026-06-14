# WhatsApp-style Insult Notifications Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Render the watch inactivity insult as a branded WhatsApp-style `MessagingStyle` notification card (app-icon avatar, sender "meatsackMotivator", insult + stats as two message bubbles) that the user taps to open the existing full-screen `InsultActivity`.

**Architecture:** Presentation-only rewrite of `InsultNotificationService.showInsultNotification`. The trigger path, vote plumbing, and `InsultActivity`/`InsultScreen` are unchanged. The only non-framework logic (which bubbles, in what order, dropping blank stats) is extracted into a pure `InsultNotificationService.insultBubbles(...)` companion function and unit-tested; the rest is verified on the physical watch. No full-screen intent — stays Play-compliant.

**Tech Stack:** Kotlin, AndroidX `NotificationCompat.MessagingStyle` + `Person` + `IconCompat` (androidx.core), vector drawable, JUnit.

**Spec:** `docs/superpowers/specs/2026-06-14-whatsapp-insult-notifications-design.md`

---

### Task 1: Add the branded assets (dumbbell small icon + brand_red color)

The current gray triangle is `android.R.drawable.ic_dialog_alert`. We replace it with a
white dumbbell glyph, and add the brand-red accent color (today only in the Compose theme).

**Files:**
- Create: `wear/src/main/res/drawable/ic_notification.xml`
- Modify: `wear/src/main/res/values/colors.xml`

- [ ] **Step 1: Create the dumbbell notification glyph**

Create `wear/src/main/res/drawable/ic_notification.xml` (Material `fitness_center` dumbbell, flat white for a notification small icon):

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24"
    android:tint="#FFFFFF">
    <path
        android:fillColor="@android:color/white"
        android:pathData="M20.57,14.86L22,13.43 20.57,12 17,15.57 8.43,7 12,3.43 10.57,2 9.14,3.43 7.71,2 5.57,4.14 4.14,2.71 2.71,4.14l1.43,1.43L2,7.71l1.43,1.43L2,10.57 3.43,12 7,8.43 15.57,17 12,20.57 13.43,22l1.43,-1.43L16.29,22l2.14,-2.14l1.43,1.43l1.43,-1.43l-1.43,-1.43L23,16.29z" />
</vector>
```

- [ ] **Step 2: Add the brand_red color**

In `wear/src/main/res/values/colors.xml`, add the `brand_red` entry inside `<resources>` (matches `MeatsackColors.primary = 0xFFFF3B30`):

```xml
    <color name="brand_red">#FFFF3B30</color>
```

- [ ] **Step 3: Verify resources compile**

Run: `./gradlew :wear:compileDebugKotlin`
Expected: BUILD SUCCESSFUL (resources merge without error).

- [ ] **Step 4: Commit**

```bash
git add wear/src/main/res/drawable/ic_notification.xml wear/src/main/res/values/colors.xml
git commit -m "feat(wear): add dumbbell notification glyph + brand_red color"
```

---

### Task 2: Extract and test the pure `insultBubbles` helper

The only logic worth unit-testing: build the ordered list of bubble texts from the insult
and stats, dropping blank stats. Pure Kotlin (no Android types), mirrors the
`VoteReceiver.requestCode` / `VoteAction.from` pattern. Referencing a companion function
does **not** run the class `init {}` (no Context needed in the test).

**Files:**
- Modify: `wear/src/main/java/com/meatsack/motivator/notification/InsultNotificationService.kt`
- Test: `wear/src/test/java/com/meatsack/motivator/notification/InsultNotificationServiceTest.kt`

- [ ] **Step 1: Write the failing test**

Create `wear/src/test/java/com/meatsack/motivator/notification/InsultNotificationServiceTest.kt`:

```kotlin
package com.meatsack.motivator.notification

import org.junit.Assert.assertEquals
import org.junit.Test

class InsultNotificationServiceTest {

    @Test
    fun bubbles_includeStats_whenPresent() {
        assertEquals(
            listOf("GET UP.", "438 steps. It's 2pm."),
            InsultNotificationService.insultBubbles("GET UP.", "438 steps. It's 2pm."),
        )
    }

    @Test
    fun bubbles_insultOnly_whenStatsEmpty() {
        assertEquals(listOf("GET UP."), InsultNotificationService.insultBubbles("GET UP.", ""))
    }

    @Test
    fun bubbles_insultOnly_whenStatsBlank() {
        assertEquals(listOf("GET UP."), InsultNotificationService.insultBubbles("GET UP.", "   "))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :wear:testDebugUnitTest --tests "com.meatsack.motivator.notification.InsultNotificationServiceTest"`
Expected: FAIL — unresolved reference `insultBubbles`.

- [ ] **Step 3: Add the pure function to the companion object**

In `InsultNotificationService.kt`, add to the existing `companion object` (alongside `INSULT_TAG`):

```kotlin
        /**
         * Ordered bubble texts for the MessagingStyle card: insult first, stats second,
         * dropping any blank entry so we never post an empty bubble. Pure (no Android) so
         * it's unit-testable.
         */
        fun insultBubbles(insultText: String, statsText: String): List<String> =
            listOf(insultText, statsText).filter { it.isNotBlank() }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :wear:testDebugUnitTest --tests "com.meatsack.motivator.notification.InsultNotificationServiceTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add wear/src/main/java/com/meatsack/motivator/notification/InsultNotificationService.kt wear/src/test/java/com/meatsack/motivator/notification/InsultNotificationServiceTest.kt
git commit -m "feat(wear): pure insultBubbles helper for MessagingStyle card"
```

---

### Task 3: Rewrite `showInsultNotification` to use MessagingStyle

Swap the `BigTextStyle` + `ic_dialog_alert` notification for a `MessagingStyle` card:
sender = "meatsackMotivator" with the app-icon avatar, bubbles from `insultBubbles`, new
small icon + brand color + `CATEGORY_MESSAGE`. Everything else (permission guard, notifId,
contentIntent, vote actions, auto-cancel, timeout) stays.

**Files:**
- Modify: `wear/src/main/java/com/meatsack/motivator/notification/InsultNotificationService.kt`

- [ ] **Step 1: Add imports**

At the top of `InsultNotificationService.kt`, add (keep existing imports):

```kotlin
import androidx.core.app.Person
import androidx.core.graphics.drawable.IconCompat
```

- [ ] **Step 2: Replace the notification builder block**

Replace the body of `showInsultNotification` from the `val notification = NotificationCompat.Builder(...)` line through the `.build()` line (current lines ~86–102) with:

```kotlin
        val coach = Person.Builder()
            .setName("meatsackMotivator")
            .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
            .build()

        val you = Person.Builder().setName("You").build()
        val messagingStyle = NotificationCompat.MessagingStyle(you)
        val now = System.currentTimeMillis()
        insultBubbles(message.text, statsText).forEach { text ->
            messagingStyle.addMessage(text, now, coach)
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.brand_red))
            .setStyle(messagingStyle)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setTimeoutAfter(NOTIFICATION_TIMEOUT_MS)
            .addAction(R.drawable.ic_thumb_down, "👎", votePendingIntent(message.id, notifId, isUp = false))
            .addAction(R.drawable.ic_thumb_up, "👍", votePendingIntent(message.id, notifId, isUp = true))
            .build()
```

The `contentIntent` construction above this block and the `NotificationManagerCompat.from(context).notify(INSULT_TAG, notifId, notification)` line below it are unchanged. The `EXTRA_STATS_TEXT` / `setSummaryText` BigTextStyle wiring is fully replaced — confirm no stray `BigTextStyle` reference remains.

- [ ] **Step 3: Verify it compiles and existing tests stay green**

Run: `./gradlew :wear:compileDebugKotlin :wear:testDebugUnitTest`
Expected: BUILD SUCCESSFUL; all unit tests pass (including the 3 new `insultBubbles` tests).

- [ ] **Step 4: Verify formatting**

Run: `./gradlew :wear:spotlessCheck`
Expected: BUILD SUCCESSFUL. (If it fails: `./gradlew :wear:spotlessApply && git add -u`.)

- [ ] **Step 5: Commit**

```bash
git add wear/src/main/java/com/meatsack/motivator/notification/InsultNotificationService.kt
git commit -m "feat(wear): deliver insults as a MessagingStyle WhatsApp-style card"
```

---

### Task 4: On-device verification (manual, physical SM-L320)

Framework rendering can't be unit-tested; verify on the physical watch per the saved
watch-verify procedure (wireless adb → `TestFireActivity` → `dumpsys notification`).

**Files:** none (verification only).

- [ ] **Step 1: Build + install on the watch**

Run:
```bash
./gradlew :wear:assembleDebug
adb -s <watch-id> install -r wear/build/outputs/apk/debug/wear-debug.apk
adb -s <watch-id> shell pm grant com.meatsack.motivator android.permission.POST_NOTIFICATIONS
```
Expected: APK installs; permission granted.

- [ ] **Step 2: Fire a test insult and inspect the notification**

Run: `adb -s <watch-id> shell am start -n com.meatsack.motivator/.debug.TestFireActivity`
Then: `adb -s <watch-id> shell dumpsys notification --noredact | grep -A2 -i "meatsack\|MessagingStyle\|ic_notification"`
Expected: a posted notification with `MessagingStyle`, sender "meatsackMotivator", two messages (insult + stats), `smallIcon` = `ic_notification`, and **no** full-screen-intent flags.

- [ ] **Step 3: Visual checks on the watch face**

Confirm by eye on the SM-L320:
- Card slides over the watch face with avatar + "meatsackMotivator" + insult bubble + stats bubble + 👎/👍.
- **Avatar renders cleanly inside the circle** (adaptive-icon checkpoint). If clipped/odd, change the Task 3 Step 2 avatar line from `createWithResource` to a rendered bitmap (add import `androidx.core.graphics.drawable.toBitmap`):
  ```kotlin
  .setIcon(
      IconCompat.createWithBitmap(
          ContextCompat.getDrawable(context, R.mipmap.ic_launcher)!!.toBitmap(),
      ),
  )
  ```
  then recompile, reinstall, recheck.
- Tap the card → `InsultActivity` opens full-screen with the full insult + two big thumbs.
- Tap 👍 (or 👎) on either the card or the full screen → notification dismisses; confirm the DAO vote landed (pull the Room DB per the watch-verify note).

- [ ] **Step 4: Record the result**

Note the outcome (and whether the adaptive-icon fallback was needed) in the PR description. No commit unless the fallback edit was applied — then:
```bash
git add wear/src/main/java/com/meatsack/motivator/notification/InsultNotificationService.kt
git commit -m "fix(wear): render launcher avatar as bitmap for clean circle mask"
```

---

## Notes for the implementer

- **Do not** add `USE_FULL_SCREEN_INTENT`, FSI activity flags, or any auto-launch — that would reverse the Play-compliance work in PR #39.
- `EXTRA_STATS_TEXT` is still passed to `InsultActivity` for the full-screen stats line — leave that intent extra in place; only the *notification's* BigTextStyle use of stats is replaced by bubble 2.
- Bug #29 (settings syncer) is a **separate branch** (`fix/settings-syncer-permanent-stop`); do not touch it here.
