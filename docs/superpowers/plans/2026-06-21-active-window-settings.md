# Active-window Settings Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Active Hours the single "active day" window (gates inactivity nagging, scopes pace, sets end-of-day timing, clamps the behind-pace hour), give Context-aware language its own work-safe hours, and delete the dead Quiet Hours and the separate end-of-day-hour setting.

**Architecture:** A new pure `ActiveWindow.contains` predicate powers both the inactivity gate and the (retargeted) `ToneResolver`. Two synced settings are added (`contextAwareStart/End`) and one removed (`endOfDayHour`, now derived from `activeHoursEnd`); the dead phone-only Quiet Hours is deleted. Changes are sequenced so each commit compiles and passes all module tests (the pre-commit hook builds shared+mobile+wear).

**Tech Stack:** Kotlin, AndroidX DataStore (phone), SharedPreferences (watch), Wear Data Layer (`DataMap`), WorkManager, Jetpack Compose Material3 (`RangeSlider`, `OutlinedTextField`), JUnit.

**Spec:** `docs/superpowers/specs/2026-06-21-active-window-settings-design.md`

**Branch:** `feature/active-window-settings` (already created; spec already committed there).

---

## File Structure

- `wear/.../messages/ActiveWindow.kt` (new) — pure windowing predicate
- `wear/.../messages/ToneResolver.kt` — retargeted to context-aware hours, reuses `ActiveWindow`
- `wear/.../MeatsackWearService.kt` — inactivity active-window gate; end-of-day scheduled at `activeHoursEnd`; tone call update
- `wear/.../trigger/EndOfDayWorker.kt` — schedule/reschedule at `activeHoursEnd`; tone call update
- `wear/.../trigger/BehindPaceWorker.kt` — tone call update
- `wear/.../settings/WatchSettingsCache.kt` — −`endOfDayHour`, +`contextAwareStart/End`
- `wear/.../sync/WatchSettingsReceiver.kt` — sink/apply/adapter field changes
- `shared/.../sync/{SettingsSnapshot,SettingsKeys,SettingsDefaults}.kt` — field set changes
- `mobile/.../data/SettingsRepository.kt` — −quiet/−endOfDayHour, +context, clamp in `setActiveHours`
- `mobile/.../data/SettingsDefaults.kt` — **deleted** (only held Quiet Hours)
- `mobile/.../sync/PhoneSettingsSyncer.kt` — `combine`/`current` field set
- `mobile/.../ui/settings/{SettingsViewModel,SettingsScreen}.kt` — UI + flows
- Tests: `ActiveWindowTest` (new), `ToneResolverTest`, `SettingsSnapshotTest`, `WatchSettingsReceiverTest`

Build commands (Git Bash, Windows; `./gradlew` works):
- `./gradlew :wear:testDebugUnitTest`
- `./gradlew :shared:testDebugUnitTest :mobile:testDebugUnitTest :wear:testDebugUnitTest`
- `./gradlew spotlessCheck` (auto-fix: `./gradlew spotlessApply && git add -u`)

---

### Task 1: `ActiveWindow` pure predicate

The one piece of genuinely new logic: "is `hour` inside `[start, end)`" with overnight-wrap support. Pure, no Android types, fully unit-tested.

**Files:**
- Create: `wear/src/main/java/com/meatsack/motivator/messages/ActiveWindow.kt`
- Test: `wear/src/test/java/com/meatsack/motivator/messages/ActiveWindowTest.kt`

- [ ] **Step 1: Write the failing test**

Create `wear/src/test/java/com/meatsack/motivator/messages/ActiveWindowTest.kt`:

```kotlin
package com.meatsack.motivator.messages

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveWindowTest {

    @Test fun sameDayWindow_includesStart_excludesEnd() {
        assertTrue(ActiveWindow.contains(7, 7, 22)) // start is inclusive
        assertTrue(ActiveWindow.contains(21, 7, 22))
        assertFalse(ActiveWindow.contains(22, 7, 22)) // end is exclusive
        assertFalse(ActiveWindow.contains(6, 7, 22))
        assertFalse(ActiveWindow.contains(23, 7, 22))
    }

    @Test fun overnightWindow_wrapsAroundMidnight() {
        assertTrue(ActiveWindow.contains(23, 22, 6))
        assertTrue(ActiveWindow.contains(0, 22, 6))
        assertTrue(ActiveWindow.contains(5, 22, 6))
        assertFalse(ActiveWindow.contains(6, 22, 6)) // end exclusive
        assertFalse(ActiveWindow.contains(12, 22, 6))
    }

    @Test fun degenerateWindow_startEqualsEnd_isEmpty() {
        assertFalse(ActiveWindow.contains(9, 9, 9))
        assertFalse(ActiveWindow.contains(10, 9, 9))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :wear:testDebugUnitTest --tests "com.meatsack.motivator.messages.ActiveWindowTest"`
Expected: FAIL — unresolved reference `ActiveWindow`.

- [ ] **Step 3: Implement the predicate**

Create `wear/src/main/java/com/meatsack/motivator/messages/ActiveWindow.kt`:

```kotlin
package com.meatsack.motivator.messages

/**
 * Windowing predicate for hour-of-day ranges. A window is [start, end): the
 * start hour is inside, the end hour is outside. Supports overnight windows
 * where start > end (e.g. 22..6). start == end is an empty window.
 *
 * Pure (no Android types) so it is unit-testable and shared by both the
 * inactivity active-hours gate and ToneResolver's work-safe-hours check.
 */
object ActiveWindow {
    fun contains(hour: Int, start: Int, end: Int): Boolean =
        if (start <= end) hour in start until end else hour >= start || hour < end
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :wear:testDebugUnitTest --tests "com.meatsack.motivator.messages.ActiveWindowTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Verify formatting & commit**

Run: `./gradlew :wear:spotlessCheck` (fix with `./gradlew spotlessApply && git add -u` if needed)

```bash
git add wear/src/main/java/com/meatsack/motivator/messages/ActiveWindow.kt wear/src/test/java/com/meatsack/motivator/messages/ActiveWindowTest.kt
git commit -m "feat(wear): add ActiveWindow.contains hour-window predicate"
```

---

### Task 2: Gate inactivity nagging on the active window

Make the 24/7 inactivity nagger fire only inside Active Hours. The gate sits at the very top of `checkInactivity` so escalation does not accrue outside the window (no aggressive burst the moment active hours begin). Uses `activeHoursStart/End`, which already exist on `WatchSettingsCache`.

**Files:**
- Modify: `wear/src/main/java/com/meatsack/motivator/MeatsackWearService.kt`

- [ ] **Step 1: Add the import**

In `MeatsackWearService.kt`, add to the imports (it already imports other `com.meatsack.motivator.messages` types like `ToneResolver`):

```kotlin
import com.meatsack.motivator.messages.ActiveWindow
```

- [ ] **Step 2: Add the gate at the top of `checkInactivity`**

`checkInactivity()` currently begins:

```kotlin
    private suspend fun checkInactivity() {
        val minutesIdle = healthTracker.getMinutesSinceLastMovement()
```

Insert the gate as the first statements of the function, before `val minutesIdle = ...`:

```kotlin
    private suspend fun checkInactivity() {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        if (!ActiveWindow.contains(hour, settings.activeHoursStart, settings.activeHoursEnd)) {
            return // outside active hours — stay quiet, don't accrue escalation
        }

        val minutesIdle = healthTracker.getMinutesSinceLastMovement()
```

Then remove the now-duplicate hour computation lower in the same function. The existing block:

```kotlin
        val steps = healthTracker.totalStepsToday.value
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val ampm = if (hour < 12) "am" else "pm"
```

becomes (drop the local `val hour` line; it is now defined at the top of the function):

```kotlin
        val steps = healthTracker.totalStepsToday.value
        val ampm = if (hour < 12) "am" else "pm"
```

- [ ] **Step 3: Compile & verify formatting**

Run: `./gradlew :wear:compileDebugKotlin && ./gradlew :wear:spotlessCheck`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add wear/src/main/java/com/meatsack/motivator/MeatsackWearService.kt
git commit -m "feat(wear): suppress inactivity insults outside active hours"
```

---

### Task 3: Add context-aware work-safe hours end-to-end (additive)

Add `contextAwareStart` / `contextAwareEnd` (defaults 9 / 17) through the whole settings pipeline. Purely additive — no behavior change yet (ToneResolver is retargeted in Task 4). This mirrors the established settings-field pattern, so all modules must stay green in one commit.

**Files:**
- Modify: `shared/src/main/java/com/meatsack/shared/sync/SettingsKeys.kt`
- Modify: `shared/src/main/java/com/meatsack/shared/sync/SettingsDefaults.kt`
- Modify: `shared/src/main/java/com/meatsack/shared/sync/SettingsSnapshot.kt`
- Test: `shared/src/test/java/com/meatsack/shared/sync/SettingsSnapshotTest.kt`
- Modify: `mobile/src/main/java/com/meatsack/motivator/mobile/data/SettingsRepository.kt`
- Modify: `mobile/src/main/java/com/meatsack/motivator/mobile/sync/PhoneSettingsSyncer.kt`
- Modify: `mobile/src/main/java/com/meatsack/motivator/mobile/ui/settings/SettingsViewModel.kt`
- Modify: `wear/src/main/java/com/meatsack/motivator/settings/WatchSettingsCache.kt`
- Modify: `wear/src/main/java/com/meatsack/motivator/sync/WatchSettingsReceiver.kt`
- Test: `wear/src/test/java/com/meatsack/motivator/sync/WatchSettingsReceiverTest.kt`

- [ ] **Step 1: Extend `SettingsSnapshotTest` (failing)**

In `SettingsSnapshotTest.kt`:

In `defaults_matchSettingsDefaults()`, add after the last existing assertion:

```kotlin
        assertEquals(SettingsDefaults.CONTEXT_AWARE_START, snap.contextAwareStart)
        assertEquals(SettingsDefaults.CONTEXT_AWARE_END, snap.contextAwareEnd)
```

In `toDataMap_fromDataMap_roundTrip()`, add the two fields to the `SettingsSnapshot(...)` literal with non-default values (defaults are 9/17):

```kotlin
            contextAwareStart = 8,
            contextAwareEnd = 16,
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :shared:testDebugUnitTest --tests "com.meatsack.shared.sync.SettingsSnapshotTest"`
Expected: FAIL — unresolved reference `contextAwareStart` / `CONTEXT_AWARE_START`.

- [ ] **Step 3: Add keys**

In `SettingsKeys.kt`, add after the last key:

```kotlin
    const val KEY_CONTEXT_AWARE_START = "context_aware_start"
    const val KEY_CONTEXT_AWARE_END = "context_aware_end"
```

- [ ] **Step 4: Add shared defaults**

In `shared/.../sync/SettingsDefaults.kt`, add inside the object:

```kotlin
    const val CONTEXT_AWARE_START = 9 // 9am
    const val CONTEXT_AWARE_END = 17 // 5pm
```

- [ ] **Step 5: Add the two fields to `SettingsSnapshot`**

In `SettingsSnapshot.kt`, add to the constructor after `endOfDayEnabled: Boolean,`:

```kotlin
    val contextAwareStart: Int,
    val contextAwareEnd: Int,
```

Add to `toDataMap`:

```kotlin
        dm.putInt(SettingsKeys.KEY_CONTEXT_AWARE_START, contextAwareStart)
        dm.putInt(SettingsKeys.KEY_CONTEXT_AWARE_END, contextAwareEnd)
```

Add to the `defaults` initializer:

```kotlin
            contextAwareStart = SettingsDefaults.CONTEXT_AWARE_START,
            contextAwareEnd = SettingsDefaults.CONTEXT_AWARE_END,
```

Add to `fromDataMap` (coerce to a valid hour, matching the existing hour fields):

```kotlin
            contextAwareStart = dm.getInt(SettingsKeys.KEY_CONTEXT_AWARE_START, defaults.contextAwareStart).coerceIn(0, 23),
            contextAwareEnd = dm.getInt(SettingsKeys.KEY_CONTEXT_AWARE_END, defaults.contextAwareEnd).coerceIn(0, 23),
```

- [ ] **Step 6: Run shared test to verify it passes**

Run: `./gradlew :shared:testDebugUnitTest --tests "com.meatsack.shared.sync.SettingsSnapshotTest"`
Expected: PASS. (`:mobile`/`:wear` not yet compiling — continue.)

- [ ] **Step 7: Phone `SettingsRepository`**

In `SettingsRepository.kt`, in the `companion object` after the last key:

```kotlin
        val CONTEXT_AWARE_START = intPreferencesKey("context_aware_start")
        val CONTEXT_AWARE_END = intPreferencesKey("context_aware_end")
```

After the `contextAwareEnabled` flow:

```kotlin
    val contextAwareStart: Flow<Int> = context.dataStore.data.map {
        it[CONTEXT_AWARE_START] ?: SharedDefaults.CONTEXT_AWARE_START
    }
    val contextAwareEnd: Flow<Int> = context.dataStore.data.map {
        it[CONTEXT_AWARE_END] ?: SharedDefaults.CONTEXT_AWARE_END
    }
```

After `setContextAwareEnabled`:

```kotlin
    suspend fun setContextAwareStart(hour: Int) {
        validateHour("Context-aware start", hour)
        context.dataStore.edit { it[CONTEXT_AWARE_START] = hour }
    }

    suspend fun setContextAwareEnd(hour: Int) {
        validateHour("Context-aware end", hour)
        context.dataStore.edit { it[CONTEXT_AWARE_END] = hour }
    }
```

- [ ] **Step 8: Phone `PhoneSettingsSyncer`**

In `PhoneSettingsSyncer.kt`, update `RepositorySettingsSource.snapshots` to append the two flows and map them. Replace the whole `combine(...)` block with:

```kotlin
    override val snapshots: Flow<SettingsSnapshot> = kotlinx.coroutines.flow.combine(
        repo.dailyStepGoal,
        repo.inactivityThreshold,
        repo.activeHoursStart,
        repo.activeHoursEnd,
        repo.contextAwareEnabled,
        repo.endOfDayHour,
        repo.behindPaceCheckHour,
        repo.behindPaceEnabled,
        repo.endOfDayEnabled,
        repo.contextAwareStart,
        repo.contextAwareEnd,
    ) { values ->
        SettingsSnapshot(
            dailyStepGoal = values[0] as Int,
            inactivityThreshold = values[1] as Int,
            activeHoursStart = values[2] as Int,
            activeHoursEnd = values[3] as Int,
            contextAwareEnabled = values[4] as Boolean,
            endOfDayHour = values[5] as Int,
            behindPaceCheckHour = values[6] as Int,
            behindPaceEnabled = values[7] as Boolean,
            endOfDayEnabled = values[8] as Boolean,
            contextAwareStart = values[9] as Int,
            contextAwareEnd = values[10] as Int,
        )
    }
```

In `current()`, add after `endOfDayEnabled = repo.endOfDayEnabled.first(),`:

```kotlin
            contextAwareStart = repo.contextAwareStart.first(),
            contextAwareEnd = repo.contextAwareEnd.first(),
```

- [ ] **Step 9: Phone `SettingsViewModel`**

In `SettingsViewModel.kt`, after the `contextAwareEnabled` `stateIn`:

```kotlin
    val contextAwareStart = repo.contextAwareStart.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(),
        SharedDefaults.CONTEXT_AWARE_START,
    )
    val contextAwareEnd = repo.contextAwareEnd.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(),
        SharedDefaults.CONTEXT_AWARE_END,
    )
```

After `toggleContextAware`:

```kotlin
    fun updateContextAwareStart(hour: Int) =
        viewModelScope.launch { repo.setContextAwareStart(hour) }
    fun updateContextAwareEnd(hour: Int) =
        viewModelScope.launch { repo.setContextAwareEnd(hour) }
```

- [ ] **Step 10: Watch `WatchSettingsCache`**

In `WatchSettingsCache.kt`, after the last property:

```kotlin
    var contextAwareStart: Int
        get() = prefs.getInt(KEY_CONTEXT_AWARE_START, SettingsDefaults.CONTEXT_AWARE_START)
        set(value) = prefs.edit().putInt(KEY_CONTEXT_AWARE_START, value).apply()

    var contextAwareEnd: Int
        get() = prefs.getInt(KEY_CONTEXT_AWARE_END, SettingsDefaults.CONTEXT_AWARE_END)
        set(value) = prefs.edit().putInt(KEY_CONTEXT_AWARE_END, value).apply()
```

In the `companion object`:

```kotlin
        private const val KEY_CONTEXT_AWARE_START = "context_aware_start"
        private const val KEY_CONTEXT_AWARE_END = "context_aware_end"
```

- [ ] **Step 11: Watch `WatchSettingsReceiver`**

In `WatchSettingsReceiver.kt`, in `applySnapshot`, after the last `sink.set...` call:

```kotlin
            sink.setContextAwareStart(snap.contextAwareStart)
            sink.setContextAwareEnd(snap.contextAwareEnd)
```

In the `ApplySink` interface:

```kotlin
        fun setContextAwareStart(v: Int)
        fun setContextAwareEnd(v: Int)
```

In `cacheAdapter`:

```kotlin
        override fun setContextAwareStart(v: Int) {
            cache.contextAwareStart = v
        }
        override fun setContextAwareEnd(v: Int) {
            cache.contextAwareEnd = v
        }
```

- [ ] **Step 12: Extend `WatchSettingsReceiverTest`**

In `WatchSettingsReceiverTest.kt`, in `FakeApplySink`, add recorded fields:

```kotlin
        var recordedContextAwareStart: Int = -1
        var recordedContextAwareEnd: Int = -1
```

and overrides:

```kotlin
        override fun setContextAwareStart(v: Int) {
            recordedContextAwareStart = v
        }
        override fun setContextAwareEnd(v: Int) {
            recordedContextAwareEnd = v
        }
```

In `applySnapshot_writesEveryField()`, add to the `SettingsSnapshot(...)` literal:

```kotlin
            contextAwareStart = 8,
            contextAwareEnd = 16,
```

and after the last assertion:

```kotlin
        assertEquals(8, sink.recordedContextAwareStart)
        assertEquals(16, sink.recordedContextAwareEnd)
```

- [ ] **Step 13: Build all modules + tests**

Run: `./gradlew :shared:testDebugUnitTest :mobile:testDebugUnitTest :wear:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all pass.

- [ ] **Step 14: Verify formatting & commit**

Run: `./gradlew spotlessCheck` (fix if needed).

```bash
git add shared/src/main/java/com/meatsack/shared/sync/SettingsKeys.kt shared/src/main/java/com/meatsack/shared/sync/SettingsDefaults.kt shared/src/main/java/com/meatsack/shared/sync/SettingsSnapshot.kt shared/src/test/java/com/meatsack/shared/sync/SettingsSnapshotTest.kt mobile/src/main/java/com/meatsack/motivator/mobile/data/SettingsRepository.kt mobile/src/main/java/com/meatsack/motivator/mobile/sync/PhoneSettingsSyncer.kt mobile/src/main/java/com/meatsack/motivator/mobile/ui/settings/SettingsViewModel.kt wear/src/main/java/com/meatsack/motivator/settings/WatchSettingsCache.kt wear/src/main/java/com/meatsack/motivator/sync/WatchSettingsReceiver.kt wear/src/test/java/com/meatsack/motivator/sync/WatchSettingsReceiverTest.kt
git commit -m "feat(settings): add context-aware work-safe hours (contextAwareStart/End)"
```

---

### Task 4: Retarget `ToneResolver` to the context-aware hours

Switch tone selection from Active Hours to the new context-aware hours, and refactor `ToneResolver` to reuse `ActiveWindow.contains`. Update the three callers (they currently pass `activeHours*`) and the test.

**Files:**
- Modify: `wear/src/main/java/com/meatsack/motivator/messages/ToneResolver.kt`
- Test: `wear/src/test/java/com/meatsack/motivator/messages/ToneResolverTest.kt`
- Modify: `wear/src/main/java/com/meatsack/motivator/MeatsackWearService.kt`
- Modify: `wear/src/main/java/com/meatsack/motivator/trigger/BehindPaceWorker.kt`
- Modify: `wear/src/main/java/com/meatsack/motivator/trigger/EndOfDayWorker.kt`

- [ ] **Step 1: Rewrite `ToneResolverTest` (failing) for context-aware-hours semantics**

Replace the body of `ToneResolverTest.kt` with (parameters now mean the work-safe window; behavior identical, names clarified):

```kotlin
package com.meatsack.motivator.messages

import com.meatsack.shared.constants.MessageTone
import org.junit.Assert.assertEquals
import org.junit.Test

class ToneResolverTest {
    @Test fun fullSend_whenToggleOff_regardlessOfTime() {
        assertEquals(MessageTone.FULL_SEND, ToneResolver.resolve(false, 9, 17, hour = 10))
        assertEquals(MessageTone.FULL_SEND, ToneResolver.resolve(false, 9, 17, hour = 23))
    }

    @Test fun workSafe_insideWorkSafeWindow_whenToggleOn() {
        assertEquals(MessageTone.WORK_SAFE, ToneResolver.resolve(true, 9, 17, hour = 9))
        assertEquals(MessageTone.WORK_SAFE, ToneResolver.resolve(true, 9, 17, hour = 16))
    }

    @Test fun fullSend_outsideWorkSafeWindow_whenToggleOn() {
        assertEquals(MessageTone.FULL_SEND, ToneResolver.resolve(true, 9, 17, hour = 8))
        assertEquals(MessageTone.FULL_SEND, ToneResolver.resolve(true, 9, 17, hour = 17)) // end exclusive
        assertEquals(MessageTone.FULL_SEND, ToneResolver.resolve(true, 9, 17, hour = 23))
    }

    @Test fun overnightWorkSafeWindow_22to6_treatsMidnightCorrectly() {
        assertEquals(MessageTone.WORK_SAFE, ToneResolver.resolve(true, 22, 6, hour = 23))
        assertEquals(MessageTone.WORK_SAFE, ToneResolver.resolve(true, 22, 6, hour = 3))
        assertEquals(MessageTone.FULL_SEND, ToneResolver.resolve(true, 22, 6, hour = 12))
    }
}
```

- [ ] **Step 2: Run to verify it still compiles/passes against current signature**

Run: `./gradlew :wear:testDebugUnitTest --tests "com.meatsack.motivator.messages.ToneResolverTest"`
Expected: PASS — the current `ToneResolver` already implements `[start, end)` windowing, so these positional calls pass even before the refactor. (This step confirms the test is valid; the refactor in Step 3 must keep it green.)

- [ ] **Step 3: Refactor `ToneResolver` to reuse `ActiveWindow` and rename params**

Replace `ToneResolver.kt` with:

```kotlin
package com.meatsack.motivator.messages

import com.meatsack.shared.constants.MessageTone
import java.util.Calendar

/**
 * Picks the message tone. When context-aware is on and the current hour is
 * inside the user's work-safe window [workSafeStart, workSafeEnd), we soften
 * to WORK_SAFE; otherwise FULL_SEND. The window check is shared with the
 * inactivity active-hours gate via ActiveWindow.
 */
object ToneResolver {
    fun resolve(
        contextAwareEnabled: Boolean,
        workSafeStart: Int,
        workSafeEnd: Int,
        hour: Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY),
    ): MessageTone {
        if (!contextAwareEnabled) return MessageTone.FULL_SEND
        return if (ActiveWindow.contains(hour, workSafeStart, workSafeEnd)) {
            MessageTone.WORK_SAFE
        } else {
            MessageTone.FULL_SEND
        }
    }
}
```

- [ ] **Step 4: Update the three callers to pass context-aware hours**

In `MeatsackWearService.kt`, the `ToneResolver.resolve(...)` call inside `checkInactivity` currently uses named `activeHours*` args. Replace it with:

```kotlin
        val tone = ToneResolver.resolve(
            contextAwareEnabled = settings.contextAwareEnabled,
            workSafeStart = settings.contextAwareStart,
            workSafeEnd = settings.contextAwareEnd,
        )
```

In `BehindPaceWorker.kt`, the call currently passes `settings.contextAwareEnabled, settings.activeHoursStart, settings.activeHoursEnd` positionally. Replace those three arguments with:

```kotlin
        val tone = ToneResolver.resolve(
            settings.contextAwareEnabled,
            settings.contextAwareStart,
            settings.contextAwareEnd,
        )
```

In `EndOfDayWorker.kt`, the same positional call — replace with:

```kotlin
        val tone = ToneResolver.resolve(
            settings.contextAwareEnabled,
            settings.contextAwareStart,
            settings.contextAwareEnd,
        )
```

- [ ] **Step 5: Build + test**

Run: `./gradlew :wear:compileDebugKotlin :wear:testDebugUnitTest`
Expected: BUILD SUCCESSFUL; `ToneResolverTest` (4 tests) and `ActiveWindowTest` pass.

- [ ] **Step 6: Verify formatting & commit**

Run: `./gradlew :wear:spotlessCheck` (fix if needed).

```bash
git add wear/src/main/java/com/meatsack/motivator/messages/ToneResolver.kt wear/src/test/java/com/meatsack/motivator/messages/ToneResolverTest.kt wear/src/main/java/com/meatsack/motivator/MeatsackWearService.kt wear/src/main/java/com/meatsack/motivator/trigger/BehindPaceWorker.kt wear/src/main/java/com/meatsack/motivator/trigger/EndOfDayWorker.kt
git commit -m "feat(wear): tone uses dedicated context-aware hours, not active hours"
```

---

### Task 5: End-of-day fires at `activeHoursEnd`; remove `endOfDayHour`

End-of-day is now the bookend of the active window. Schedule it at `activeHoursEnd` and delete the `endOfDayHour` setting from every layer. This is the one atomic removal — every consumer must drop the field in one commit.

**Files:**
- Modify: `wear/src/main/java/com/meatsack/motivator/MeatsackWearService.kt`
- Modify: `wear/src/main/java/com/meatsack/motivator/trigger/EndOfDayWorker.kt`
- Modify: `wear/src/main/java/com/meatsack/motivator/settings/WatchSettingsCache.kt`
- Modify: `wear/src/main/java/com/meatsack/motivator/sync/WatchSettingsReceiver.kt`
- Test: `wear/src/test/java/com/meatsack/motivator/sync/WatchSettingsReceiverTest.kt`
- Modify: `shared/src/main/java/com/meatsack/shared/sync/SettingsSnapshot.kt`
- Modify: `shared/src/main/java/com/meatsack/shared/sync/SettingsKeys.kt`
- Modify: `shared/src/main/java/com/meatsack/shared/sync/SettingsDefaults.kt`
- Test: `shared/src/test/java/com/meatsack/shared/sync/SettingsSnapshotTest.kt`
- Modify: `mobile/src/main/java/com/meatsack/motivator/mobile/data/SettingsRepository.kt`
- Modify: `mobile/src/main/java/com/meatsack/motivator/mobile/sync/PhoneSettingsSyncer.kt`
- Modify: `mobile/src/main/java/com/meatsack/motivator/mobile/ui/settings/SettingsViewModel.kt`

- [ ] **Step 1: Schedule end-of-day at `activeHoursEnd`**

In `MeatsackWearService.kt` `onStartCommand`, change:

```kotlin
        scheduler.scheduleEndOfDay(settings.endOfDayHour)
```
to:
```kotlin
        scheduler.scheduleEndOfDay(settings.activeHoursEnd)
```

In `EndOfDayWorker.kt`, the reschedule call near the top of `doWork`:

```kotlin
            TriggerScheduler(ctx).scheduleEndOfDay(settings.endOfDayHour)
```
becomes:
```kotlin
            TriggerScheduler(ctx).scheduleEndOfDay(settings.activeHoursEnd)
```

- [ ] **Step 2: Remove `endOfDayHour` from the watch cache + receiver**

In `WatchSettingsCache.kt`, delete the `endOfDayHour` property:

```kotlin
    var endOfDayHour: Int
        get() = prefs.getInt(KEY_END_OF_DAY, SettingsDefaults.END_OF_DAY_HOUR)
        set(value) = prefs.edit().putInt(KEY_END_OF_DAY, value).apply()
```

and its companion key `private const val KEY_END_OF_DAY = "end_of_day_hour"`.

In `WatchSettingsReceiver.kt`, delete the `sink.setEndOfDayHour(snap.endOfDayHour)` line in `applySnapshot`, the `fun setEndOfDayHour(v: Int)` line in `ApplySink`, and the `override fun setEndOfDayHour(v: Int) { cache.endOfDayHour = v }` block in `cacheAdapter`.

- [ ] **Step 3: Remove `endOfDayHour` from the shared wire-shape**

In `SettingsSnapshot.kt`: delete the `val endOfDayHour: Int,` constructor field, the `dm.putInt(SettingsKeys.KEY_END_OF_DAY_HOUR, endOfDayHour)` line in `toDataMap`, the `endOfDayHour = SettingsDefaults.END_OF_DAY_HOUR,` line in `defaults`, and the `endOfDayHour = dm.getInt(...)` line in `fromDataMap`.

In `SettingsKeys.kt`: delete `const val KEY_END_OF_DAY_HOUR = "end_of_day_hour"`.

In `shared/.../sync/SettingsDefaults.kt`: delete `const val END_OF_DAY_HOUR = 21 // 9pm`.

- [ ] **Step 4: Remove `endOfDayHour` from the phone**

In `SettingsRepository.kt`: delete the `END_OF_DAY_HOUR` key, the `endOfDayHour` flow, and the `setEndOfDayHour` function.

In `PhoneSettingsSyncer.kt`: remove `repo.endOfDayHour` from the `combine(...)` flow list and the `endOfDayHour = values[...] as Int,` line from the `SettingsSnapshot(...)` mapping, then renumber the remaining `values[N]` indices to stay sequential. Also remove `endOfDayHour = repo.endOfDayHour.first(),` from `current()`. Use this final `snapshots` block:

```kotlin
    override val snapshots: Flow<SettingsSnapshot> = kotlinx.coroutines.flow.combine(
        repo.dailyStepGoal,
        repo.inactivityThreshold,
        repo.activeHoursStart,
        repo.activeHoursEnd,
        repo.contextAwareEnabled,
        repo.behindPaceCheckHour,
        repo.behindPaceEnabled,
        repo.endOfDayEnabled,
        repo.contextAwareStart,
        repo.contextAwareEnd,
    ) { values ->
        SettingsSnapshot(
            dailyStepGoal = values[0] as Int,
            inactivityThreshold = values[1] as Int,
            activeHoursStart = values[2] as Int,
            activeHoursEnd = values[3] as Int,
            contextAwareEnabled = values[4] as Boolean,
            behindPaceCheckHour = values[5] as Int,
            behindPaceEnabled = values[6] as Boolean,
            endOfDayEnabled = values[7] as Boolean,
            contextAwareStart = values[8] as Int,
            contextAwareEnd = values[9] as Int,
        )
    }
```

And the final `current()` body:

```kotlin
    override suspend fun current(): SettingsSnapshot =
        SettingsSnapshot(
            dailyStepGoal = repo.dailyStepGoal.first(),
            inactivityThreshold = repo.inactivityThreshold.first(),
            activeHoursStart = repo.activeHoursStart.first(),
            activeHoursEnd = repo.activeHoursEnd.first(),
            contextAwareEnabled = repo.contextAwareEnabled.first(),
            behindPaceCheckHour = repo.behindPaceCheckHour.first(),
            behindPaceEnabled = repo.behindPaceEnabled.first(),
            endOfDayEnabled = repo.endOfDayEnabled.first(),
            contextAwareStart = repo.contextAwareStart.first(),
            contextAwareEnd = repo.contextAwareEnd.first(),
        )
```

In `SettingsViewModel.kt`: delete the `endOfDayHour` `stateIn` block and the `updateEndOfDayHour` function (neither is rendered in the UI).

- [ ] **Step 5: Update the tests to drop `endOfDayHour`**

In `SettingsSnapshotTest.kt`: remove the `endOfDayHour = ...,` line from the round-trip literal, remove the `assertEquals(SettingsDefaults.END_OF_DAY_HOUR, snap.endOfDayHour)` line from `defaults_matchSettingsDefaults`, and remove the `dm.putInt(SettingsKeys.KEY_END_OF_DAY_HOUR, 24)` line plus its `assertEquals(23, parsed.endOfDayHour)` assertion from `fromDataMap_outOfRangeHours_clampedTo0to23`.

In `WatchSettingsReceiverTest.kt`: remove `recordedEndOfDayHour`, its override, the `endOfDayHour = ...,` line from the snapshot literal, and the `assertEquals(..., sink.recordedEndOfDayHour)` assertion.

- [ ] **Step 6: Build all modules + tests**

Run: `./gradlew :shared:testDebugUnitTest :mobile:testDebugUnitTest :wear:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all pass.

- [ ] **Step 7: Verify formatting & commit**

Run: `./gradlew spotlessCheck` (fix if needed).

```bash
git add -A
git commit -m "feat: end-of-day fires at activeHoursEnd; remove separate endOfDayHour"
```

---

### Task 6: Settings UI — Active range slider, remove Quiet Hours, context boxes, behind-pace clamp

Replace the two Active Hours sliders with one `RangeSlider`, delete the dead Quiet Hours sliders, add the two context-aware hour input boxes, and clamp the behind-pace hour into the active window.

**Files:**
- Modify: `mobile/src/main/java/com/meatsack/motivator/mobile/ui/settings/SettingsScreen.kt`
- Modify: `mobile/src/main/java/com/meatsack/motivator/mobile/ui/settings/SettingsViewModel.kt`
- Modify: `mobile/src/main/java/com/meatsack/motivator/mobile/data/SettingsRepository.kt`
- Delete: `mobile/src/main/java/com/meatsack/motivator/mobile/data/SettingsDefaults.kt`

- [ ] **Step 1: Clamp behind-pace in `setActiveHours`**

In `SettingsRepository.kt`, the current `setActiveHours`:

```kotlin
    suspend fun setActiveHours(start: Int, end: Int) {
        validateHour("Active hours start", start)
        validateHour("Active hours end", end)
        context.dataStore.edit {
            it[ACTIVE_HOURS_START] = start
            it[ACTIVE_HOURS_END] = end
        }
    }
```

becomes (also snap the stored behind-pace hour into the new window):

```kotlin
    suspend fun setActiveHours(start: Int, end: Int) {
        validateHour("Active hours start", start)
        validateHour("Active hours end", end)
        context.dataStore.edit {
            it[ACTIVE_HOURS_START] = start
            it[ACTIVE_HOURS_END] = end
            val behindPace = it[BEHIND_PACE_CHECK_HOUR] ?: SharedDefaults.BEHIND_PACE_CHECK_HOUR
            it[BEHIND_PACE_CHECK_HOUR] = behindPace.coerceIn(start, end)
        }
    }
```

- [ ] **Step 2: Remove Quiet Hours from `SettingsRepository`**

In `SettingsRepository.kt`, delete the `QUIET_HOURS_START` / `QUIET_HOURS_END` keys, the `quietHoursStart` / `quietHoursEnd` flows, and the `setQuietHours` function. The quiet flows are the only references to the phone-only `SettingsDefaults` (`SettingsDefaults.QUIET_HOURS_START/END`, resolved same-package with no import line), so deleting them removes the last use. **Keep** the aliased `import com.meatsack.shared.sync.SettingsDefaults as SharedDefaults` — it is still used by the other flows and the behind-pace clamp fallback added in Step 1.

- [ ] **Step 3: Delete the phone-only `SettingsDefaults`**

The file `mobile/src/main/java/com/meatsack/motivator/mobile/data/SettingsDefaults.kt` now holds nothing used. Delete it:

```bash
git rm mobile/src/main/java/com/meatsack/motivator/mobile/data/SettingsDefaults.kt
```

- [ ] **Step 4: Remove Quiet Hours from `SettingsViewModel`**

In `SettingsViewModel.kt`, delete the `quietHoursStart` and `quietHoursEnd` `stateIn` blocks and the `updateQuietHours` function. Remove the now-unused import `import com.meatsack.motivator.mobile.data.SettingsDefaults` (the phone-only one). `SharedDefaults` (aliased) stays.

- [ ] **Step 5: Rework `SettingsScreen` — imports**

In `SettingsScreen.kt`, add the imports needed for `RangeSlider` and the numeric boxes:

```kotlin
import androidx.compose.material3.RangeSlider
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
```

- [ ] **Step 6: Replace the Active Hours two-slider block with a `RangeSlider`**

In `SettingsScreen.kt`, the Active Hours section is currently:

```kotlin
        Text(
            "Active Hours: $activeStart:00 - $activeEnd:00",
            style = MaterialTheme.typography.titleMedium,
        )
        Text("Start", style = MaterialTheme.typography.bodySmall)
        Slider(
            value = activeStart.toFloat(),
            onValueChange = { viewModel.updateActiveHours(it.toInt(), activeEnd) },
            valueRange = 0f..23f,
            steps = 22,
            modifier = Modifier.fillMaxWidth(),
        )
        Text("End", style = MaterialTheme.typography.bodySmall)
        Slider(
            value = activeEnd.toFloat(),
            onValueChange = { viewModel.updateActiveHours(activeStart, it.toInt()) },
            valueRange = 0f..23f,
            steps = 22,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
```

Replace it with a single range slider:

```kotlin
        Text(
            "Active hours: $activeStart:00 - $activeEnd:00",
            style = MaterialTheme.typography.titleMedium,
        )
        RangeSlider(
            value = activeStart.toFloat()..activeEnd.toFloat(),
            onValueChange = { range ->
                viewModel.updateActiveHours(range.start.toInt(), range.endInclusive.toInt())
            },
            valueRange = 0f..23f,
            steps = 22,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "Hours the watch can nag you. Outside this window it stays quiet.",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(16.dp))
```

- [ ] **Step 7: Delete the Quiet Hours block from `SettingsScreen`**

Delete the entire Quiet Hours section (the `quietStart`/`quietEnd` `collectAsState` lines near the top of the composable, and the block):

```kotlin
        Text(
            "Quiet Hours: $quietStart:00 - $quietEnd:00",
            style = MaterialTheme.typography.titleMedium,
        )
        Text("Start", style = MaterialTheme.typography.bodySmall)
        Slider(
            value = quietStart.toFloat(),
            onValueChange = { viewModel.updateQuietHours(it.toInt(), quietEnd) },
            valueRange = 0f..23f,
            steps = 22,
            modifier = Modifier.fillMaxWidth(),
        )
        Text("End", style = MaterialTheme.typography.bodySmall)
        Slider(
            value = quietEnd.toFloat(),
            onValueChange = { viewModel.updateQuietHours(quietStart, it.toInt()) },
            valueRange = 0f..23f,
            steps = 22,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
```

Also delete the two unused state reads at the top of the composable:

```kotlin
    val quietStart by viewModel.quietHoursStart.collectAsState()
    val quietEnd by viewModel.quietHoursEnd.collectAsState()
```

- [ ] **Step 8: Clamp the behind-pace slider range to the active window**

In `SettingsScreen.kt`, the behind-pace slider currently has `valueRange = 0f..23f`. Change that slider's range to the active window so the thumb cannot leave it:

```kotlin
        Slider(
            value = behindPaceHour.toFloat(),
            onValueChange = { viewModel.updateBehindPaceCheckHour(it.toInt().coerceIn(0, 23)) },
            // Guard against a zero-width active window (start == end), which would make an
            // empty valueRange and crash the Slider; coerceIn on the value keeps the picked
            // hour valid for setBehindPaceCheckHour's 0..23 validation.
            valueRange = activeStart.toFloat()..activeEnd.toFloat().coerceAtLeast(activeStart + 1f),
            modifier = Modifier.fillMaxWidth(),
        )
```

(Remove the `steps = 22` argument — with a variable range the fixed step count no longer matches; a continuous slider is fine here and avoids mismatched tick math.)

- [ ] **Step 9: Add the context-aware hour boxes**

In `SettingsScreen.kt`, the Context-aware section currently ends with the help `Text(...)` after the `Switch`. Immediately after that help text, add the two hour boxes (shown always, enabled only when the toggle is on):

```kotlin
        val ctxStart by viewModel.contextAwareStart.collectAsState()
        val ctxEnd by viewModel.contextAwareEnd.collectAsState()
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = ctxStart.toString(),
                onValueChange = { v ->
                    v.toIntOrNull()?.let { viewModel.updateContextAwareStart(it.coerceIn(0, 23)) }
                },
                label = { Text("Work-safe start") },
                enabled = contextAware,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.width(140.dp),
            )
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(
                value = ctxEnd.toString(),
                onValueChange = { v ->
                    v.toIntOrNull()?.let { viewModel.updateContextAwareEnd(it.coerceIn(0, 23)) }
                },
                label = { Text("Work-safe end") },
                enabled = contextAware,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.width(140.dp),
            )
        }
```

(`contextAware` is the existing `val contextAware by viewModel.contextAwareEnabled.collectAsState()` already at the top of the composable. `OutlinedTextField`, `Row`, `Alignment`, `Spacer`, `width`, `Text` are already imported.)

- [ ] **Step 10: Build + verify formatting**

Run: `./gradlew :mobile:compileDebugKotlin && ./gradlew :mobile:spotlessCheck`
Expected: BUILD SUCCESSFUL. (Fix spotless if needed.)

- [ ] **Step 11: Commit**

```bash
git add -A
git commit -m "feat(settings): active range slider, remove Quiet Hours, context-aware boxes, behind-pace clamp"
```

---

### Task 7: Full build + on-device verification + PR

**Files:** none (verification only).

- [ ] **Step 1: Full build + all tests**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL (all modules, lint, every unit test incl. `ActiveWindowTest`, `ToneResolverTest`, `SettingsSnapshotTest`, `WatchSettingsReceiverTest`).

- [ ] **Step 2: Install on paired emulators**

```bash
./gradlew :mobile:assembleDebug :wear:assembleDebug
adb -s <phone-id> install -r mobile/build/outputs/apk/debug/mobile-debug.apk
adb -s <watch-id> install -r wear/build/outputs/apk/debug/wear-debug.apk
```

- [ ] **Step 3: Verify the settings propagate (phone → watch)**

On the phone Settings screen: narrow the Active hours range slider and change the context-aware boxes. Confirm on the watch:

```bash
adb -s <watch-id> logcat -d -s WatchSettingsReceiver | tail -3
```

Expected: the latest `SettingsSnapshot` log shows the new `contextAwareStart`/`contextAwareEnd` values and **no** `endOfDayHour` field; `activeHoursStart`/`activeHoursEnd` reflect the slider.

- [ ] **Step 4: Verify the inactivity active-window gate**

Set Active hours to a window that excludes the current hour. Then:

```bash
adb -s <watch-id> logcat -d -s MeatsackWearService | grep -i "active" | tail -3
```

Expected: at the next poll tick, `checkInactivity` returns early (no insult posted) while outside the window. (Optionally widen the window to include "now" and confirm insults resume — depends on inactivity simulation per CLAUDE.md.)

- [ ] **Step 5: Push and open the PR**

```bash
git push -u origin feature/active-window-settings
gh pr create --fill --base main
```

Then review with `pr-review-toolkit:review-pr` before merging (per project workflow).

---

## Notes for the implementer

- **Sequencing rationale:** the field *additions* (Task 3) are kept separate from the field *removal* (Task 5) so each commit stays green under the pre-commit hook (which builds shared+mobile+wear). The removal is the one unavoidable atomic commit — every `endOfDayHour` consumer must drop it together.
- **No data migration:** orphaned `quiet_hours_*` / `end_of_day_hour` keys left in an existing DataStore/SharedPreferences are simply ignored; absent `context_aware_*` keys fall back to 9/17. A phone/watch version skew degrades gracefully because `DataMap.getInt(key, default)` returns the default for absent keys.
- **Window convention is `[start, end)`** everywhere — `ActiveWindow.contains` is the single source. End-of-day is a *scheduled fire at* `activeHoursEnd`, which is intentionally the bookend, not a "contains" check.
- **Behind-pace clamp:** the slider range prevents picking out-of-window; the inline `coerceIn` in `setActiveHours` covers the case where the window narrows past an already-saved value.
- **Do not commit to `main`** — work stays on `feature/active-window-settings`; PR into `main`.
- **Active Hours overnight (start > end)** is not offered (the `RangeSlider` enforces start ≤ end). The `ActiveWindow` wrap branch exists for the context-aware boxes, which allow an overnight work-safe window.
