# Phone → Watch Settings Sync Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Wire phone settings through to the watch by introducing a `/settings` DataItem channel backed by a single shared `SettingsSnapshot`, closing schema-drift bugs along the way (closes #23, partially addresses #25).

**Architecture:** New `:shared/sync/` types — `SettingsKeys`, `SettingsSnapshot`, hoisted `SettingsDefaults` — give phone (writer) and watch (reader) a compile-time-checked contract. `PhoneSettingsSyncer` on the phone observes `SettingsRepository` flows (`debounce(500ms)` + `distinctUntilChanged()`) and writes a `PutDataMapRequest`; `MainActivity.onResume` also triggers a one-shot snapshot via `syncNow()`. `WatchSettingsReceiver` (a `WearableListenerService`) reads the DataItem and writes into `WatchSettingsCache`. As a side effect we add `behindPaceCheckHour` to phone UI and rewire `EscalationManager` to read its inactivity threshold from `WatchSettingsCache` instead of a hardcoded constant.

**Tech Stack:** Kotlin, Jetpack Compose (mobile UI), Jetpack DataStore (phone), SharedPreferences (watch), Wear Data Layer (`com.google.android.gms.wearable`), JUnit + Turbine + `kotlinx-coroutines-test`. Robolectric only if `DataMap` turns out not to be pure-JVM-instantiable (verify in Task 3).

**Spec:** `docs/superpowers/specs/2026-05-30-phone-watch-settings-sync-design.md`

---

## File Structure

### New

| Path | Responsibility |
|---|---|
| `shared/src/main/java/com/meatsack/shared/sync/SettingsDefaults.kt` | Default values for the 7 watch-relevant settings, shared by phone + watch. |
| `shared/src/main/java/com/meatsack/shared/sync/SettingsKeys.kt` | DataMap path + key constants for the `/settings` channel. |
| `shared/src/main/java/com/meatsack/shared/sync/SettingsSnapshot.kt` | Immutable 7-field data class with `toDataMap`/`fromDataMap`. |
| `shared/src/test/java/com/meatsack/shared/sync/SettingsSnapshotTest.kt` | Round-trip, defaults-on-missing, hour coercion, defaults parity. |
| `mobile/src/main/java/com/meatsack/motivator/mobile/sync/PhoneSettingsSyncer.kt` | Observes `SettingsRepository` flows, debounces, writes `/settings` DataItem. Exposes `syncNow()`. |
| `mobile/src/test/java/com/meatsack/motivator/mobile/sync/PhoneSettingsSyncerTest.kt` | `syncNow` correctness; debounce + distinctUntilChanged; bounded retry. |
| `wear/src/main/java/com/meatsack/motivator/sync/WatchSettingsReceiver.kt` | `WearableListenerService` that applies `/settings` DataItem to `WatchSettingsCache`. |
| `wear/src/test/java/com/meatsack/motivator/sync/WatchSettingsReceiverTest.kt` | Pure-JVM test of `applySnapshot` against a fake sink. |

### Modified

| Path | Change |
|---|---|
| `mobile/src/main/java/com/meatsack/motivator/mobile/data/SettingsDefaults.kt` | Trim to `QUIET_HOURS_START`/`QUIET_HOURS_END` only. |
| `mobile/src/main/java/com/meatsack/motivator/mobile/data/SettingsRepository.kt` | Add `behindPaceCheckHour: Flow<Int>` + `setBehindPaceCheckHour`. Update imports to source 6 defaults from `:shared`. |
| `mobile/src/test/java/com/meatsack/motivator/mobile/data/SettingsRepositoryTest.kt` | Add `validateHour` tests for the new setter. |
| `mobile/src/main/java/com/meatsack/motivator/mobile/ui/settings/SettingsViewModel.kt` | Expose `behindPaceCheckHour` StateFlow + `updateBehindPaceCheckHour`. |
| `mobile/src/main/java/com/meatsack/motivator/mobile/ui/settings/SettingsScreen.kt` | Add 0..23 slider for `behindPaceCheckHour`. |
| `mobile/src/main/java/com/meatsack/motivator/mobile/MeatsackMobileApp.kt` | Construct `PhoneSettingsSyncer`; expose; call `.start(applicationScope)` in `onCreate`. |
| `mobile/src/main/java/com/meatsack/motivator/mobile/MainActivity.kt` | `onResume` launches `syncer.syncNow()`. |
| `wear/src/main/java/com/meatsack/motivator/settings/WatchSettingsCache.kt` | Add `inactivityThreshold` field. Replace hardcoded literal defaults with `:shared` `SettingsDefaults`. |
| `wear/src/main/java/com/meatsack/motivator/escalation/EscalationManager.kt` | Constructor takes `thresholdProvider: () -> Int`; replace 2 reads of `EscalationLevel.INACTIVITY_THRESHOLD_MINUTES_DEFAULT` with `thresholdProvider()`. |
| `wear/src/main/java/com/meatsack/motivator/MeatsackWearService.kt` | Pass `{ watchSettingsCache.inactivityThreshold }` to `EscalationManager`. |
| `wear/src/main/AndroidManifest.xml` | Register `WatchSettingsReceiver` with `<data-item-filter>` for `SettingsKeys.PATH`. |

---

## Pre-flight

- [ ] **Step 0: Confirm branch**

Run: `git status`
Expected: `On branch feature/phone-watch-settings-sync`. If not, run `git checkout feature/phone-watch-settings-sync`. The spec was already committed on this branch as `35a3dc5`.

---

## Task 1: Hoist `SettingsDefaults` to `:shared` and trim phone-side

**Files:**
- Create: `shared/src/main/java/com/meatsack/shared/sync/SettingsDefaults.kt`
- Modify: `mobile/src/main/java/com/meatsack/motivator/mobile/data/SettingsDefaults.kt`
- Modify: `mobile/src/main/java/com/meatsack/motivator/mobile/data/SettingsRepository.kt`
- Modify: `mobile/src/main/java/com/meatsack/motivator/mobile/ui/settings/SettingsViewModel.kt`
- Modify: `wear/src/main/java/com/meatsack/motivator/settings/WatchSettingsCache.kt`

- [ ] **Step 1.1: Create shared defaults**

`shared/src/main/java/com/meatsack/shared/sync/SettingsDefaults.kt`:

```kotlin
package com.meatsack.shared.sync

/**
 * Default values for the 7 settings the watch consumes. Mirrored by phone
 * (SettingsRepository fallbacks) and watch (WatchSettingsCache fallbacks).
 * Single source of truth — adding a default here is the only place to change it.
 */
object SettingsDefaults {
    const val DAILY_STEP_GOAL = 10_000
    const val INACTIVITY_THRESHOLD_MIN = 30
    const val ACTIVE_HOURS_START = 7
    const val ACTIVE_HOURS_END = 22
    const val CONTEXT_AWARE_ENABLED = false
    const val END_OF_DAY_HOUR = 21 // 9pm
    const val BEHIND_PACE_CHECK_HOUR = 12 // noon
}
```

- [ ] **Step 1.2: Trim phone-side `SettingsDefaults`**

Overwrite `mobile/src/main/java/com/meatsack/motivator/mobile/data/SettingsDefaults.kt`:

```kotlin
package com.meatsack.motivator.mobile.data

/**
 * Phone-only setting defaults. The 7 watch-shared defaults moved to
 * com.meatsack.shared.sync.SettingsDefaults in the settings-sync work.
 */
object SettingsDefaults {
    const val QUIET_HOURS_START = 22
    const val QUIET_HOURS_END = 7
}
```

- [ ] **Step 1.3: Update `SettingsRepository` to read shared defaults**

In `mobile/src/main/java/com/meatsack/motivator/mobile/data/SettingsRepository.kt`, add this import at the top (alongside existing imports):

```kotlin
import com.meatsack.shared.sync.SettingsDefaults as SharedDefaults
```

Then replace every `SettingsDefaults.<CONST>` reference for the 6 hoisted constants with `SharedDefaults.<CONST>`. Leave `SettingsDefaults.QUIET_HOURS_START` and `SettingsDefaults.QUIET_HOURS_END` unchanged. Concretely, lines using `SettingsDefaults.DAILY_STEP_GOAL`, `SettingsDefaults.INACTIVITY_THRESHOLD_MIN`, `SettingsDefaults.ACTIVE_HOURS_START`, `SettingsDefaults.ACTIVE_HOURS_END`, `SettingsDefaults.CONTEXT_AWARE_ENABLED`, `SettingsDefaults.END_OF_DAY_HOUR` should be updated.

(Note: `SettingsRepository` doesn't currently import `SettingsDefaults` explicitly — it resolves via the same package. Adding the `import as SharedDefaults` is the cleanest way to differentiate.)

- [ ] **Step 1.4: Update `SettingsViewModel` the same way**

In `mobile/src/main/java/com/meatsack/motivator/mobile/ui/settings/SettingsViewModel.kt`, add at top:

```kotlin
import com.meatsack.shared.sync.SettingsDefaults as SharedDefaults
```

Replace the 6 hoisted-constant uses (`SettingsDefaults.DAILY_STEP_GOAL`, `SettingsDefaults.INACTIVITY_THRESHOLD_MIN`, `SettingsDefaults.ACTIVE_HOURS_START`, `SettingsDefaults.ACTIVE_HOURS_END`, `SettingsDefaults.CONTEXT_AWARE_ENABLED`, `SettingsDefaults.END_OF_DAY_HOUR`) with `SharedDefaults.<CONST>`. Leave `SettingsDefaults.QUIET_HOURS_START` / `_END` unchanged.

- [ ] **Step 1.5: Update `WatchSettingsCache` to read shared defaults**

In `wear/src/main/java/com/meatsack/motivator/settings/WatchSettingsCache.kt`, add import:

```kotlin
import com.meatsack.shared.sync.SettingsDefaults
```

Then replace each literal default value in the property getters with the matching `SettingsDefaults` constant. The current file is:

```kotlin
var contextAwareEnabled: Boolean
    get() = prefs.getBoolean(KEY_CONTEXT_AWARE, false)
    ...
var activeHoursStart: Int
    get() = prefs.getInt(KEY_ACTIVE_START, 7)
    ...
var activeHoursEnd: Int
    get() = prefs.getInt(KEY_ACTIVE_END, 22)
    ...
var dailyStepGoal: Int
    get() = prefs.getInt(KEY_GOAL, 10_000)
    ...
var endOfDayHour: Int
    get() = prefs.getInt(KEY_END_OF_DAY, 21)
    ...
var behindPaceCheckHour: Int
    get() = prefs.getInt(KEY_BEHIND_PACE_HOUR, 12)
    ...
```

It should become:

```kotlin
var contextAwareEnabled: Boolean
    get() = prefs.getBoolean(KEY_CONTEXT_AWARE, SettingsDefaults.CONTEXT_AWARE_ENABLED)
    ...
var activeHoursStart: Int
    get() = prefs.getInt(KEY_ACTIVE_START, SettingsDefaults.ACTIVE_HOURS_START)
    ...
var activeHoursEnd: Int
    get() = prefs.getInt(KEY_ACTIVE_END, SettingsDefaults.ACTIVE_HOURS_END)
    ...
var dailyStepGoal: Int
    get() = prefs.getInt(KEY_GOAL, SettingsDefaults.DAILY_STEP_GOAL)
    ...
var endOfDayHour: Int
    get() = prefs.getInt(KEY_END_OF_DAY, SettingsDefaults.END_OF_DAY_HOUR)
    ...
var behindPaceCheckHour: Int
    get() = prefs.getInt(KEY_BEHIND_PACE_HOUR, SettingsDefaults.BEHIND_PACE_CHECK_HOUR)
    ...
```

(Leave `set(value)` lines unchanged.)

- [ ] **Step 1.6: Build all modules to verify**

Run: `./gradlew :shared:compileDebugKotlin :mobile:compileDebugKotlin :wear:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 1.7: Run existing unit tests**

Run: `./gradlew :shared:testDebugUnitTest :mobile:testDebugUnitTest :wear:testDebugUnitTest`
Expected: All pass. (No new tests yet — verifying we didn't break anything during the hoist.)

- [ ] **Step 1.8: Commit**

```bash
git add shared/src/main/java/com/meatsack/shared/sync/SettingsDefaults.kt \
        mobile/src/main/java/com/meatsack/motivator/mobile/data/SettingsDefaults.kt \
        mobile/src/main/java/com/meatsack/motivator/mobile/data/SettingsRepository.kt \
        mobile/src/main/java/com/meatsack/motivator/mobile/ui/settings/SettingsViewModel.kt \
        wear/src/main/java/com/meatsack/motivator/settings/WatchSettingsCache.kt
git commit -m "refactor: hoist 6 watch-shared defaults to :shared/sync/SettingsDefaults (#23)"
```

---

## Task 2: Add `SettingsKeys`

**Files:**
- Create: `shared/src/main/java/com/meatsack/shared/sync/SettingsKeys.kt`

- [ ] **Step 2.1: Create the file**

```kotlin
package com.meatsack.shared.sync

/**
 * Wire-level keys for the /settings DataItem channel. Single source of truth for
 * both PhoneSettingsSyncer (writer) and WatchSettingsReceiver (reader). Adding
 * a key here is the only edit needed before wiring it on each side.
 *
 * No KEY_TIMESTAMP: DataClient dedupes by content bytes, and settings have no
 * "stale" semantics on the watch — including a timestamp would fire onDataChanged
 * on every send even when nothing changed. Deliberate divergence from /messages.
 */
object SettingsKeys {
    const val PATH = "/settings"
    const val KEY_DAILY_STEP_GOAL = "daily_step_goal"
    const val KEY_INACTIVITY_THRESHOLD = "inactivity_threshold_min"
    const val KEY_ACTIVE_HOURS_START = "active_hours_start"
    const val KEY_ACTIVE_HOURS_END = "active_hours_end"
    const val KEY_CONTEXT_AWARE_ENABLED = "context_aware_enabled"
    const val KEY_END_OF_DAY_HOUR = "end_of_day_hour"
    const val KEY_BEHIND_PACE_CHECK_HOUR = "behind_pace_check_hour"
}
```

- [ ] **Step 2.2: Build :shared**

Run: `./gradlew :shared:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 2.3: Commit**

```bash
git add shared/src/main/java/com/meatsack/shared/sync/SettingsKeys.kt
git commit -m "feat: add SettingsKeys for /settings DataItem channel (#23)"
```

---

## Task 3: `SettingsSnapshot` tests (red)

This is TDD step 1 — write tests that fail because the class doesn't exist yet.

**Files:**
- Create: `shared/src/test/java/com/meatsack/shared/sync/SettingsSnapshotTest.kt`

- [ ] **Step 3.1: Write the failing test**

```kotlin
package com.meatsack.shared.sync

import com.google.android.gms.wearable.DataMap
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsSnapshotTest {

    @Test
    fun defaults_matchSettingsDefaults() {
        val snap = SettingsSnapshot.defaults
        assertEquals(SettingsDefaults.DAILY_STEP_GOAL, snap.dailyStepGoal)
        assertEquals(SettingsDefaults.INACTIVITY_THRESHOLD_MIN, snap.inactivityThreshold)
        assertEquals(SettingsDefaults.ACTIVE_HOURS_START, snap.activeHoursStart)
        assertEquals(SettingsDefaults.ACTIVE_HOURS_END, snap.activeHoursEnd)
        assertEquals(SettingsDefaults.CONTEXT_AWARE_ENABLED, snap.contextAwareEnabled)
        assertEquals(SettingsDefaults.END_OF_DAY_HOUR, snap.endOfDayHour)
        assertEquals(SettingsDefaults.BEHIND_PACE_CHECK_HOUR, snap.behindPaceCheckHour)
    }

    @Test
    fun toDataMap_fromDataMap_roundTrip() {
        val original = SettingsSnapshot(
            dailyStepGoal = 12_345,
            inactivityThreshold = 45,
            activeHoursStart = 8,
            activeHoursEnd = 21,
            contextAwareEnabled = true,
            endOfDayHour = 20,
            behindPaceCheckHour = 14,
        )
        val dm = DataMap()
        original.toDataMap(dm)
        val parsed = SettingsSnapshot.fromDataMap(dm)
        assertEquals(original, parsed)
    }

    @Test
    fun fromDataMap_missingKeys_returnsDefaults() {
        val dm = DataMap() // empty
        val parsed = SettingsSnapshot.fromDataMap(dm)
        assertEquals(SettingsSnapshot.defaults, parsed)
    }

    @Test
    fun fromDataMap_outOfRangeHours_clampedTo0to23() {
        val dm = DataMap()
        dm.putInt(SettingsKeys.KEY_ACTIVE_HOURS_START, -5)
        dm.putInt(SettingsKeys.KEY_ACTIVE_HOURS_END, 99)
        dm.putInt(SettingsKeys.KEY_END_OF_DAY_HOUR, 24)
        dm.putInt(SettingsKeys.KEY_BEHIND_PACE_CHECK_HOUR, -1)
        // Non-hour fields put through so we read defaults for them:
        val parsed = SettingsSnapshot.fromDataMap(dm)
        assertEquals(0, parsed.activeHoursStart)
        assertEquals(23, parsed.activeHoursEnd)
        assertEquals(23, parsed.endOfDayHour)
        assertEquals(0, parsed.behindPaceCheckHour)
    }
}
```

- [ ] **Step 3.2: Run the test, expecting compile failure**

Run: `./gradlew :shared:testDebugUnitTest --tests "com.meatsack.shared.sync.SettingsSnapshotTest"`

Expected: COMPILE FAILURE — `Unresolved reference: SettingsSnapshot`. If you instead get a DataMap-related error like `java.lang.NoClassDefFoundError` or a Robolectric-required hint, note this — Task 4 step 4.3 has a contingency.

- [ ] **Step 3.3: Do not commit yet**

The build is currently red. Move to Task 4.

---

## Task 4: Implement `SettingsSnapshot` (green)

**Files:**
- Create: `shared/src/main/java/com/meatsack/shared/sync/SettingsSnapshot.kt`
- Possibly modify: `shared/build.gradle.kts` (Robolectric dep if DataMap test fails on JVM)

- [ ] **Step 4.1: Create the data class**

```kotlin
package com.meatsack.shared.sync

import com.google.android.gms.wearable.DataMap

/**
 * Wire-shape for the /settings DataItem channel. Single source of truth for the
 * field set — adding a property here forces both phone (writer) and watch
 * (reader) to handle the new field, which is the structural fix for issue #23.
 *
 * fromDataMap returns SettingsDefaults values for any missing keys (forward
 * and backward compatibility) and coerces hour fields to 0..23 (defense in
 * depth against malformed payloads; phone's validateHour should already
 * prevent invalid sends).
 */
data class SettingsSnapshot(
    val dailyStepGoal: Int,
    val inactivityThreshold: Int,
    val activeHoursStart: Int,
    val activeHoursEnd: Int,
    val contextAwareEnabled: Boolean,
    val endOfDayHour: Int,
    val behindPaceCheckHour: Int,
) {

    fun toDataMap(dm: DataMap) {
        dm.putInt(SettingsKeys.KEY_DAILY_STEP_GOAL, dailyStepGoal)
        dm.putInt(SettingsKeys.KEY_INACTIVITY_THRESHOLD, inactivityThreshold)
        dm.putInt(SettingsKeys.KEY_ACTIVE_HOURS_START, activeHoursStart)
        dm.putInt(SettingsKeys.KEY_ACTIVE_HOURS_END, activeHoursEnd)
        dm.putBoolean(SettingsKeys.KEY_CONTEXT_AWARE_ENABLED, contextAwareEnabled)
        dm.putInt(SettingsKeys.KEY_END_OF_DAY_HOUR, endOfDayHour)
        dm.putInt(SettingsKeys.KEY_BEHIND_PACE_CHECK_HOUR, behindPaceCheckHour)
    }

    companion object {
        val defaults = SettingsSnapshot(
            dailyStepGoal = SettingsDefaults.DAILY_STEP_GOAL,
            inactivityThreshold = SettingsDefaults.INACTIVITY_THRESHOLD_MIN,
            activeHoursStart = SettingsDefaults.ACTIVE_HOURS_START,
            activeHoursEnd = SettingsDefaults.ACTIVE_HOURS_END,
            contextAwareEnabled = SettingsDefaults.CONTEXT_AWARE_ENABLED,
            endOfDayHour = SettingsDefaults.END_OF_DAY_HOUR,
            behindPaceCheckHour = SettingsDefaults.BEHIND_PACE_CHECK_HOUR,
        )

        fun fromDataMap(dm: DataMap): SettingsSnapshot = SettingsSnapshot(
            dailyStepGoal = dm.getInt(SettingsKeys.KEY_DAILY_STEP_GOAL, defaults.dailyStepGoal),
            inactivityThreshold = dm.getInt(SettingsKeys.KEY_INACTIVITY_THRESHOLD, defaults.inactivityThreshold),
            activeHoursStart = dm.getInt(SettingsKeys.KEY_ACTIVE_HOURS_START, defaults.activeHoursStart).coerceIn(0, 23),
            activeHoursEnd = dm.getInt(SettingsKeys.KEY_ACTIVE_HOURS_END, defaults.activeHoursEnd).coerceIn(0, 23),
            contextAwareEnabled = dm.getBoolean(SettingsKeys.KEY_CONTEXT_AWARE_ENABLED, defaults.contextAwareEnabled),
            endOfDayHour = dm.getInt(SettingsKeys.KEY_END_OF_DAY_HOUR, defaults.endOfDayHour).coerceIn(0, 23),
            behindPaceCheckHour = dm.getInt(SettingsKeys.KEY_BEHIND_PACE_CHECK_HOUR, defaults.behindPaceCheckHour).coerceIn(0, 23),
        )
    }
}
```

- [ ] **Step 4.2: Run the test, expecting green**

Run: `./gradlew :shared:testDebugUnitTest --tests "com.meatsack.shared.sync.SettingsSnapshotTest"`

Expected: All 4 tests pass. If you get a `DataMap`-related runtime error (e.g. `NoClassDefFoundError`), proceed to Step 4.3 (contingency). Otherwise skip to Step 4.4.

- [ ] **Step 4.3: (Contingency) Add Robolectric to `:shared` tests**

Only do this step if Step 4.2 failed with a `DataMap`-related error.

Open `shared/build.gradle.kts` and confirm whether Robolectric is already configured. If not, add to dependencies:

```kotlin
testImplementation("org.robolectric:robolectric:4.11.1")
```

And in the `android { }` block, add (if missing):

```kotlin
testOptions {
    unitTests.isIncludeAndroidResources = true
}
```

Annotate `SettingsSnapshotTest` with `@RunWith(RobolectricTestRunner::class)` (imports: `org.junit.runner.RunWith`, `org.robolectric.RobolectricTestRunner`). Then re-run Step 4.2.

- [ ] **Step 4.4: Commit**

```bash
git add shared/src/main/java/com/meatsack/shared/sync/SettingsSnapshot.kt \
        shared/src/test/java/com/meatsack/shared/sync/SettingsSnapshotTest.kt
# include build.gradle.kts only if Step 4.3 was needed:
# git add shared/build.gradle.kts
git commit -m "feat: SettingsSnapshot with DataMap round-trip + coerceIn(0,23) (#23)"
```

---

## Task 5: Add `behindPaceCheckHour` to `SettingsRepository`

**Files:**
- Modify: `mobile/src/main/java/com/meatsack/motivator/mobile/data/SettingsRepository.kt`
- Modify: `mobile/src/test/java/com/meatsack/motivator/mobile/data/SettingsRepositoryTest.kt`

- [ ] **Step 5.1: Write the failing test**

Append to `mobile/src/test/java/com/meatsack/motivator/mobile/data/SettingsRepositoryTest.kt`:

```kotlin
@Test
fun validateHour_rejectsTooLarge_forBehindPaceCheck() {
    val e = assertThrows(IllegalArgumentException::class.java) {
        SettingsRepository.validateHour("Behind pace check hour", 24)
    }
    assertTrue(
        "Message should include field name; was: ${e.message}",
        e.message?.contains("Behind pace check hour") == true,
    )
}
```

(This isn't strictly *new* behavior, but it documents the intent for the new setter. The setter itself is harder to unit-test without DataStore plumbing — production-level coverage of setter happy paths comes via the manual end-to-end check.)

- [ ] **Step 5.2: Run the test, expecting green (or compile-only)**

Run: `./gradlew :mobile:testDebugUnitTest --tests "com.meatsack.motivator.mobile.data.SettingsRepositoryTest.validateHour_rejectsTooLarge_forBehindPaceCheck"`

Expected: PASS (the existing `validateHour` already rejects 24; this is a contract test for the new field's reuse of it).

- [ ] **Step 5.3: Add the field + setter to `SettingsRepository`**

In `mobile/src/main/java/com/meatsack/motivator/mobile/data/SettingsRepository.kt`:

In the `companion object`, alongside existing key definitions, add:

```kotlin
val BEHIND_PACE_CHECK_HOUR = intPreferencesKey("behind_pace_check_hour")
```

In the body, after the `endOfDayHour` Flow, add:

```kotlin
val behindPaceCheckHour: Flow<Int> = context.dataStore.data.map {
    it[BEHIND_PACE_CHECK_HOUR] ?: SharedDefaults.BEHIND_PACE_CHECK_HOUR
}
```

After the `setEndOfDayHour` setter, add:

```kotlin
suspend fun setBehindPaceCheckHour(hour: Int) {
    validateHour("Behind pace check hour", hour)
    context.dataStore.edit { it[BEHIND_PACE_CHECK_HOUR] = hour }
}
```

- [ ] **Step 5.4: Build and run tests**

Run: `./gradlew :mobile:testDebugUnitTest`
Expected: All tests pass (including the new one).

- [ ] **Step 5.5: Commit**

```bash
git add mobile/src/main/java/com/meatsack/motivator/mobile/data/SettingsRepository.kt \
        mobile/src/test/java/com/meatsack/motivator/mobile/data/SettingsRepositoryTest.kt
git commit -m "feat: add behindPaceCheckHour to SettingsRepository (#23)"
```

---

## Task 6: Expose `behindPaceCheckHour` in ViewModel + slider in `SettingsScreen`

No unit test for this task — Compose UI testing is heavyweight, coverage comes via the manual E2E checklist.

**Files:**
- Modify: `mobile/src/main/java/com/meatsack/motivator/mobile/ui/settings/SettingsViewModel.kt`
- Modify: `mobile/src/main/java/com/meatsack/motivator/mobile/ui/settings/SettingsScreen.kt`

- [ ] **Step 6.1: Expose in ViewModel**

In `SettingsViewModel.kt`, after the `endOfDayHour` StateFlow declaration, add:

```kotlin
val behindPaceCheckHour = repo.behindPaceCheckHour.stateIn(
    viewModelScope,
    SharingStarted.WhileSubscribed(),
    SharedDefaults.BEHIND_PACE_CHECK_HOUR,
)
```

After the `updateEndOfDayHour` function, add:

```kotlin
fun updateBehindPaceCheckHour(hour: Int) =
    viewModelScope.launch { repo.setBehindPaceCheckHour(hour) }
```

- [ ] **Step 6.2: Add slider in `SettingsScreen`**

In `SettingsScreen.kt`, in the `Composable` body, after the quiet-hours block (the second slider pair, lines around 117) and before the API-key section, add:

```kotlin
val behindPaceHour by viewModel.behindPaceCheckHour.collectAsState()
Text(
    "Behind-pace check hour: $behindPaceHour:00",
    style = MaterialTheme.typography.titleMedium,
)
Slider(
    value = behindPaceHour.toFloat(),
    onValueChange = { viewModel.updateBehindPaceCheckHour(it.toInt()) },
    valueRange = 0f..23f,
    steps = 22,
    modifier = Modifier.fillMaxWidth(),
)
Text(
    "Time of day to check whether you're behind your step goal.",
    style = MaterialTheme.typography.bodySmall,
)
Spacer(Modifier.height(16.dp))
```

- [ ] **Step 6.3: Build**

Run: `./gradlew :mobile:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6.4: Commit**

```bash
git add mobile/src/main/java/com/meatsack/motivator/mobile/ui/settings/SettingsViewModel.kt \
        mobile/src/main/java/com/meatsack/motivator/mobile/ui/settings/SettingsScreen.kt
git commit -m "feat: add behindPaceCheckHour slider to SettingsScreen (#23)"
```

---

## Task 7: `PhoneSettingsSyncer.syncNow` — TDD (red → green)

**Files:**
- Create: `mobile/src/main/java/com/meatsack/motivator/mobile/sync/PhoneSettingsSyncer.kt`
- Create: `mobile/src/test/java/com/meatsack/motivator/mobile/sync/PhoneSettingsSyncerTest.kt`

We need to inject a mockable `DataClient`. The cleanest pattern is a small functional abstraction; testing the real `Wearable.getDataClient(context)` requires an emulator. We'll introduce a single-method interface that production satisfies with a thin lambda.

- [ ] **Step 7.1: Write the failing syncNow test**

```kotlin
package com.meatsack.motivator.mobile.sync

import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.PutDataRequest
import com.meatsack.motivator.mobile.data.SettingsRepository
import com.meatsack.shared.sync.SettingsDefaults
import com.meatsack.shared.sync.SettingsKeys
import com.meatsack.shared.sync.SettingsSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class PhoneSettingsSyncerTest {

    /** Captures the last PutDataRequest the syncer attempted to send. */
    private class FakeDataItemSink : DataItemSink {
        var lastRequest: PutDataRequest? = null
        var failNextWith: Throwable? = null
        var callCount = 0
        override suspend fun put(request: PutDataRequest) {
            callCount++
            failNextWith?.let { failNextWith = null; throw it }
            lastRequest = request
        }
    }

    @Test
    fun syncNow_writesPutDataRequest_atSettingsPath_withAllKeys() = runTest {
        val sink = FakeDataItemSink()
        val syncer = PhoneSettingsSyncer(
            settings = FakeSettingsSource(SettingsSnapshot.defaults.copy(dailyStepGoal = 12345)),
            sink = sink,
        )

        syncer.syncNow()

        val req = sink.lastRequest
        assertNotNull("expected a PutDataRequest", req)
        assertEquals(SettingsKeys.PATH, req!!.uri.path)
        val parsed = SettingsSnapshot.fromDataMap(
            com.google.android.gms.wearable.PutDataMapRequest.createFromDataMapItem(
                com.google.android.gms.wearable.DataMapItem.fromDataItem(
                    com.google.android.gms.wearable.DataItem { req.uri }
                )
            ).dataMap // see note below
        )
        // The above two-step parse is awkward; the simpler shape is to verify
        // the DataMap directly. We'll prefer that approach in the implementation
        // — see Step 7.3 for a tighter helper. For now, accept that the test
        // above documents the expected behavior even if its parse is verbose.
        assertEquals(12345, parsed.dailyStepGoal)
    }

    private class FakeSettingsSource(snap: SettingsSnapshot) : SettingsSource {
        override val flow = MutableStateFlow(snap)
        override suspend fun current(): SettingsSnapshot = flow.value
    }
}
```

**Note:** the DataItem reconstruction shape above is intentionally clunky to flag a simpler design: keep the test's assertion in terms of `DataMap` rather than `DataItem`, by having the sink expose the `DataMap` it received rather than a fully-built `PutDataRequest`. We'll adjust the abstraction in Step 7.3.

- [ ] **Step 7.2: Run the test, expecting compile failure**

Run: `./gradlew :mobile:testDebugUnitTest --tests "com.meatsack.motivator.mobile.sync.PhoneSettingsSyncerTest"`

Expected: COMPILE FAILURE — `Unresolved reference: PhoneSettingsSyncer`, `SettingsSource`, `DataItemSink`. Good — proceed.

- [ ] **Step 7.3: Simplify the abstraction, rewrite the test**

We'll make the sink hand-off a `DataMap` directly so tests don't need to reconstruct DataItems. Replace the test with:

```kotlin
package com.meatsack.motivator.mobile.sync

import com.google.android.gms.wearable.DataMap
import com.meatsack.shared.sync.SettingsDefaults
import com.meatsack.shared.sync.SettingsKeys
import com.meatsack.shared.sync.SettingsSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class PhoneSettingsSyncerTest {

    private class FakeSink : SettingsSink {
        var lastDataMap: DataMap? = null
        var callCount = 0
        var failNextWith: Throwable? = null
        override suspend fun put(path: String, dataMap: DataMap) {
            callCount++
            failNextWith?.let { failNextWith = null; throw it }
            assertEquals(SettingsKeys.PATH, path)
            lastDataMap = dataMap
        }
    }

    private class FakeSettingsSource(initial: SettingsSnapshot) : SettingsSource {
        override val snapshots = MutableStateFlow(initial)
        override suspend fun current(): SettingsSnapshot = snapshots.value
    }

    @Test
    fun syncNow_writesAllFieldsAtSettingsPath() = runTest {
        val custom = SettingsSnapshot.defaults.copy(
            dailyStepGoal = 12345,
            inactivityThreshold = 45,
            behindPaceCheckHour = 14,
        )
        val sink = FakeSink()
        val syncer = PhoneSettingsSyncer(FakeSettingsSource(custom), sink)

        syncer.syncNow()

        val dm = sink.lastDataMap
        assertNotNull("expected sink to receive a DataMap", dm)
        val parsed = SettingsSnapshot.fromDataMap(dm!!)
        assertEquals(custom, parsed)
        assertEquals(1, sink.callCount)
    }

    @Test
    fun syncNow_propagatesCancellation() = runTest {
        val sink = FakeSink().apply { failNextWith = kotlinx.coroutines.CancellationException("test") }
        val syncer = PhoneSettingsSyncer(FakeSettingsSource(SettingsSnapshot.defaults), sink)
        var threw = false
        try {
            syncer.syncNow()
        } catch (_: kotlinx.coroutines.CancellationException) {
            threw = true
        }
        assert(threw) { "syncNow should rethrow CancellationException" }
    }

    @Test
    fun syncNow_swallowsAndLogsOtherExceptions() = runTest {
        val sink = FakeSink().apply { failNextWith = RuntimeException("network down") }
        val syncer = PhoneSettingsSyncer(FakeSettingsSource(SettingsSnapshot.defaults), sink)
        syncer.syncNow() // should not throw
        // Nothing else to assert — no exception is the success criterion.
    }
}
```

- [ ] **Step 7.4: Implement `PhoneSettingsSyncer` + abstractions**

Create `mobile/src/main/java/com/meatsack/motivator/mobile/sync/PhoneSettingsSyncer.kt`:

```kotlin
package com.meatsack.motivator.mobile.sync

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.meatsack.motivator.mobile.data.SettingsRepository
import com.meatsack.shared.sync.SettingsKeys
import com.meatsack.shared.sync.SettingsSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.tasks.await

/**
 * Source of SettingsSnapshot updates. Production wires this to combine() over
 * SettingsRepository flows; tests use an in-memory MutableStateFlow.
 */
interface SettingsSource {
    val snapshots: Flow<SettingsSnapshot>
    suspend fun current(): SettingsSnapshot
}

/**
 * Single-method abstraction over the Wear DataClient. Production wraps
 * Wearable.getDataClient(context).putDataItem(...); tests fake it.
 */
interface SettingsSink {
    suspend fun put(path: String, dataMap: DataMap)
}

class PhoneSettingsSyncer(
    private val settings: SettingsSource,
    private val sink: SettingsSink,
) {

    companion object {
        private const val TAG = "PhoneSettingsSyncer"
    }

    suspend fun syncNow() {
        val snap = settings.current()
        writeSnapshot(snap)
    }

    private suspend fun writeSnapshot(snap: SettingsSnapshot) {
        try {
            val dm = DataMap()
            snap.toDataMap(dm)
            sink.put(SettingsKeys.PATH, dm)
            Log.d(TAG, "Synced settings: $snap")
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            Log.e(TAG, "Settings sync failed", e)
        }
    }
}
```

- [ ] **Step 7.5: Run tests**

Run: `./gradlew :mobile:testDebugUnitTest --tests "com.meatsack.motivator.mobile.sync.PhoneSettingsSyncerTest"`

Expected: 3 tests pass.

- [ ] **Step 7.6: Commit**

```bash
git add mobile/src/main/java/com/meatsack/motivator/mobile/sync/PhoneSettingsSyncer.kt \
        mobile/src/test/java/com/meatsack/motivator/mobile/sync/PhoneSettingsSyncerTest.kt
git commit -m "feat: PhoneSettingsSyncer.syncNow + injectable Source/Sink (#23)"
```

---

## Task 8: `PhoneSettingsSyncer.start` — debounce, distinctUntilChanged, retry

**Files:**
- Modify: `mobile/src/main/java/com/meatsack/motivator/mobile/sync/PhoneSettingsSyncer.kt`
- Modify: `mobile/src/test/java/com/meatsack/motivator/mobile/sync/PhoneSettingsSyncerTest.kt`

We use `kotlinx-coroutines-test`'s virtual time to verify debouncing without sleeping.

- [ ] **Step 8.1: Verify `kotlinx-coroutines-test` is available**

Run: `grep -n "kotlinx-coroutines-test" mobile/build.gradle.kts`

If no result, add to dependencies in `mobile/build.gradle.kts`:

```kotlin
testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
```

(Use the version matching the other kotlinx-coroutines artifacts in the project.)

- [ ] **Step 8.2: Write the failing debounce + distinctUntilChanged test**

Append to `PhoneSettingsSyncerTest.kt`:

```kotlin
@Test
fun start_debouncesRapidEmissionsToSingleWrite() = runTest {
    val source = FakeSettingsSource(SettingsSnapshot.defaults)
    val sink = FakeSink()
    val syncer = PhoneSettingsSyncer(source, sink)

    val job = launch { syncer.start(this) }

    // Three rapid edits within the 500ms debounce window — should collapse to one write.
    source.snapshots.value = SettingsSnapshot.defaults.copy(dailyStepGoal = 11000)
    advanceTimeBy(100)
    source.snapshots.value = SettingsSnapshot.defaults.copy(dailyStepGoal = 12000)
    advanceTimeBy(100)
    source.snapshots.value = SettingsSnapshot.defaults.copy(dailyStepGoal = 13000)
    advanceTimeBy(600) // > debounce window

    assertEquals("expected only the trailing emission to be written", 1, sink.callCount)
    val parsed = SettingsSnapshot.fromDataMap(sink.lastDataMap!!)
    assertEquals(13000, parsed.dailyStepGoal)

    job.cancel()
}

@Test
fun start_distinctUntilChanged_suppressesIdenticalEmissions() = runTest {
    val source = FakeSettingsSource(SettingsSnapshot.defaults)
    val sink = FakeSink()
    val syncer = PhoneSettingsSyncer(source, sink)

    val job = launch { syncer.start(this) }

    source.snapshots.value = SettingsSnapshot.defaults.copy(dailyStepGoal = 11000)
    advanceTimeBy(600)
    source.snapshots.value = SettingsSnapshot.defaults.copy(dailyStepGoal = 11000) // identical
    advanceTimeBy(600)

    assertEquals(1, sink.callCount)
    job.cancel()
}
```

Add the necessary imports at the top of the test file:

```kotlin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
```

- [ ] **Step 8.3: Run, expecting compile failure**

Run: `./gradlew :mobile:testDebugUnitTest --tests "com.meatsack.motivator.mobile.sync.PhoneSettingsSyncerTest"`

Expected: COMPILE FAILURE — `start` is not yet defined. Proceed.

- [ ] **Step 8.4: Implement `start`**

In `PhoneSettingsSyncer.kt`, add the imports:

```kotlin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
```

Replace the class body so it reads:

```kotlin
class PhoneSettingsSyncer(
    private val settings: SettingsSource,
    private val sink: SettingsSink,
) {

    companion object {
        private const val TAG = "PhoneSettingsSyncer"
        private const val MAX_RETRY_ATTEMPTS = 3
    }

    fun start(scope: CoroutineScope) {
        scope.launch {
            settings.snapshots
                .debounce(500.milliseconds)
                .distinctUntilChanged()
                .retryWhen { cause, attempt ->
                    if (attempt < MAX_RETRY_ATTEMPTS) {
                        Log.w(TAG, "settings flow failure (attempt=$attempt), retrying", cause)
                        delay(((1L shl attempt.toInt()).coerceAtMost(8)).seconds)
                        true
                    } else {
                        Log.e(TAG, "settings flow gave up after $MAX_RETRY_ATTEMPTS retries", cause)
                        false
                    }
                }
                .catch { e -> Log.e(TAG, "settings flow terminated", e) }
                .collect { snap -> writeSnapshot(snap) }
        }
    }

    suspend fun syncNow() {
        writeSnapshot(settings.current())
    }

    private suspend fun writeSnapshot(snap: SettingsSnapshot) {
        try {
            val dm = DataMap()
            snap.toDataMap(dm)
            sink.put(SettingsKeys.PATH, dm)
            Log.d(TAG, "Synced settings: $snap")
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            Log.e(TAG, "Settings sync failed", e)
        }
    }
}
```

(Notice `start` accepts a `CoroutineScope` instead of being a `suspend` function. This matches the call site `start(applicationScope)` in `MeatsackMobileApp.onCreate`.)

- [ ] **Step 8.5: Run tests**

Run: `./gradlew :mobile:testDebugUnitTest --tests "com.meatsack.motivator.mobile.sync.PhoneSettingsSyncerTest"`

Expected: all 5 tests pass (3 from Task 7 + 2 new).

- [ ] **Step 8.6: Commit**

```bash
git add mobile/src/main/java/com/meatsack/motivator/mobile/sync/PhoneSettingsSyncer.kt \
        mobile/src/test/java/com/meatsack/motivator/mobile/sync/PhoneSettingsSyncerTest.kt
# include mobile/build.gradle.kts if Step 8.1 needed an add:
# git add mobile/build.gradle.kts
git commit -m "feat: PhoneSettingsSyncer.start with debounce + retryWhen (#23)"
```

---

## Task 9: Production `SettingsSource` + `SettingsSink` + wire into `MeatsackMobileApp` + `MainActivity.onResume`

**Files:**
- Modify: `mobile/src/main/java/com/meatsack/motivator/mobile/sync/PhoneSettingsSyncer.kt` (add factories)
- Modify: `mobile/src/main/java/com/meatsack/motivator/mobile/MeatsackMobileApp.kt`
- Modify: `mobile/src/main/java/com/meatsack/motivator/mobile/MainActivity.kt`

- [ ] **Step 9.1: Add production `SettingsSource` and `SettingsSink` to `PhoneSettingsSyncer.kt`**

Append to `PhoneSettingsSyncer.kt`, below the class:

```kotlin
/**
 * Production SettingsSource backed by SettingsRepository. Combines the 7
 * watch-relevant Flows into a single SettingsSnapshot.
 */
class RepositorySettingsSource(private val repo: SettingsRepository) : SettingsSource {
    override val snapshots: Flow<SettingsSnapshot> = kotlinx.coroutines.flow.combine(
        repo.dailyStepGoal,
        repo.inactivityThreshold,
        repo.activeHoursStart,
        repo.activeHoursEnd,
        repo.contextAwareEnabled,
        repo.endOfDayHour,
        repo.behindPaceCheckHour,
    ) { values ->
        SettingsSnapshot(
            dailyStepGoal = values[0] as Int,
            inactivityThreshold = values[1] as Int,
            activeHoursStart = values[2] as Int,
            activeHoursEnd = values[3] as Int,
            contextAwareEnabled = values[4] as Boolean,
            endOfDayHour = values[5] as Int,
            behindPaceCheckHour = values[6] as Int,
        )
    }

    override suspend fun current(): SettingsSnapshot =
        SettingsSnapshot(
            dailyStepGoal = kotlinx.coroutines.flow.first(repo.dailyStepGoal),
            inactivityThreshold = kotlinx.coroutines.flow.first(repo.inactivityThreshold),
            activeHoursStart = kotlinx.coroutines.flow.first(repo.activeHoursStart),
            activeHoursEnd = kotlinx.coroutines.flow.first(repo.activeHoursEnd),
            contextAwareEnabled = kotlinx.coroutines.flow.first(repo.contextAwareEnabled),
            endOfDayHour = kotlinx.coroutines.flow.first(repo.endOfDayHour),
            behindPaceCheckHour = kotlinx.coroutines.flow.first(repo.behindPaceCheckHour),
        )
}

/** Production sink that writes via Wearable.getDataClient. */
class WearableSettingsSink(private val context: Context) : SettingsSink {
    override suspend fun put(path: String, dataMap: DataMap) {
        val req = PutDataMapRequest.create(path).apply {
            this.dataMap.putAll(dataMap)
        }.asPutDataRequest().setUrgent()
        Wearable.getDataClient(context).putDataItem(req).await()
    }
}
```

**Note:** the `first` call shape above uses the top-level operator. If your project has it under a different import (e.g., extension on Flow), adjust accordingly — the alternative shape is:

```kotlin
import kotlinx.coroutines.flow.first
// ...
override suspend fun current(): SettingsSnapshot =
    SettingsSnapshot(
        dailyStepGoal = repo.dailyStepGoal.first(),
        // ...etc
    )
```

Use whichever import shape matches the project's idioms (check existing code via `grep -rn "\.first()" mobile/src/main`). The extension shape is more idiomatic Kotlin.

- [ ] **Step 9.2: Wire into `MeatsackMobileApp`**

Modify `mobile/src/main/java/com/meatsack/motivator/mobile/MeatsackMobileApp.kt`:

Add imports:

```kotlin
import com.meatsack.motivator.mobile.data.SettingsRepository
import com.meatsack.motivator.mobile.sync.PhoneSettingsSyncer
import com.meatsack.motivator.mobile.sync.RepositorySettingsSource
import com.meatsack.motivator.mobile.sync.WearableSettingsSink
```

In the class body, after `applicationScope`, add:

```kotlin
lateinit var settingsSyncer: PhoneSettingsSyncer
    private set
```

In `onCreate()`, after `seedDatabaseIfEmpty()`, add:

```kotlin
val repo = SettingsRepository(this)
settingsSyncer = PhoneSettingsSyncer(
    settings = RepositorySettingsSource(repo),
    sink = WearableSettingsSink(this),
)
settingsSyncer.start(applicationScope)
Log.d(TAG, "PhoneSettingsSyncer started")
```

- [ ] **Step 9.3: Call `syncNow()` from `MainActivity.onResume`**

Modify `mobile/src/main/java/com/meatsack/motivator/mobile/MainActivity.kt`:

Replace the file contents with:

```kotlin
package com.meatsack.motivator.mobile

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.lifecycle.lifecycleScope
import com.meatsack.motivator.mobile.ui.navigation.MeatsackNavGraph
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                MeatsackNavGraph()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val app = applicationContext as MeatsackMobileApp
        lifecycleScope.launch {
            try {
                app.settingsSyncer.syncNow()
            } catch (t: Throwable) {
                Log.w(TAG, "onResume syncNow failed", t)
            }
        }
    }
}
```

- [ ] **Step 9.4: Build**

Run: `./gradlew :mobile:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 9.5: Commit**

```bash
git add mobile/src/main/java/com/meatsack/motivator/mobile/sync/PhoneSettingsSyncer.kt \
        mobile/src/main/java/com/meatsack/motivator/mobile/MeatsackMobileApp.kt \
        mobile/src/main/java/com/meatsack/motivator/mobile/MainActivity.kt
git commit -m "feat: wire PhoneSettingsSyncer into Application + onResume (#23)"
```

---

## Task 10: Add `inactivityThreshold` to `WatchSettingsCache`

**Files:**
- Modify: `wear/src/main/java/com/meatsack/motivator/settings/WatchSettingsCache.kt`

No new test file — `WatchSettingsCache` has SharedPreferences-backed properties; Robolectric would be required to exercise them in isolation. We'll cover the wiring via `EscalationManagerTest` (Task 11) and the manual E2E checklist.

- [ ] **Step 10.1: Add the field**

In `WatchSettingsCache.kt`, alongside the other `var` properties, add:

```kotlin
var inactivityThreshold: Int
    get() = prefs.getInt(KEY_INACTIVITY_THRESHOLD, SettingsDefaults.INACTIVITY_THRESHOLD_MIN)
    set(value) = prefs.edit().putInt(KEY_INACTIVITY_THRESHOLD, value).apply()
```

In the `companion object`, alongside the other key constants, add:

```kotlin
private const val KEY_INACTIVITY_THRESHOLD = "inactivity_threshold_min"
```

- [ ] **Step 10.2: Build**

Run: `./gradlew :wear:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 10.3: Commit**

```bash
git add wear/src/main/java/com/meatsack/motivator/settings/WatchSettingsCache.kt
git commit -m "feat: add inactivityThreshold to WatchSettingsCache (#23)"
```

---

## Task 11: Rewire `EscalationManager` to read threshold from a provider

**Files:**
- Modify: `wear/src/main/java/com/meatsack/motivator/escalation/EscalationManager.kt`
- Modify: `wear/src/test/java/com/meatsack/motivator/escalation/EscalationManagerTest.kt`
- Modify: `wear/src/main/java/com/meatsack/motivator/MeatsackWearService.kt`

- [ ] **Step 11.1: Write the failing test**

Append to `EscalationManagerTest.kt` (note: keep the default-arg test for the existing `EscalationManager()` ctor path):

```kotlin
@Test
fun `custom threshold provider overrides default`() {
    val customManager = EscalationManager(thresholdProvider = { 45 })
    assertFalse("44 is below custom threshold 45", customManager.shouldTrigger(44))
    assertTrue("45 should trigger", customManager.shouldTrigger(45))
}

@Test
fun `provider is called per shouldTrigger invocation (live config)`() {
    var threshold = 30
    val liveManager = EscalationManager(thresholdProvider = { threshold })
    assertTrue("30 triggers with threshold=30", liveManager.shouldTrigger(30))
    liveManager.onMovementDetected() // reset
    threshold = 60
    assertFalse("30 should not trigger after raising threshold to 60", liveManager.shouldTrigger(30))
    assertTrue("60 triggers with threshold=60", liveManager.shouldTrigger(60))
}
```

- [ ] **Step 11.2: Run the test, expecting compile failure**

Run: `./gradlew :wear:testDebugUnitTest --tests "com.meatsack.motivator.escalation.EscalationManagerTest"`

Expected: COMPILE FAILURE — `EscalationManager` has no `thresholdProvider` parameter.

- [ ] **Step 11.3: Modify `EscalationManager`**

In `EscalationManager.kt`:

Add import:

```kotlin
import com.meatsack.shared.sync.SettingsDefaults
```

Change the class declaration from:

```kotlin
class EscalationManager {
```

to:

```kotlin
class EscalationManager(
    private val thresholdProvider: () -> Int = { SettingsDefaults.INACTIVITY_THRESHOLD_MIN },
) {
```

Replace `EscalationLevel.INACTIVITY_THRESHOLD_MINUTES_DEFAULT` with `thresholdProvider()` on both lines (currently 57 and 67):

```kotlin
val threshold = thresholdProvider()
```

Leave `EscalationLevel.ESCALATION_INTERVAL_MINUTES` references untouched.

- [ ] **Step 11.4: Run tests**

Run: `./gradlew :wear:testDebugUnitTest --tests "com.meatsack.motivator.escalation.EscalationManagerTest"`

Expected: all tests pass (the existing `EscalationManager()` ctor still works via the default arg).

- [ ] **Step 11.5: Wire production threshold provider in `MeatsackWearService`**

Open `wear/src/main/java/com/meatsack/motivator/MeatsackWearService.kt`. Find the construction of `EscalationManager` (it should currently be a no-arg `EscalationManager()`).

Replace it with:

```kotlin
EscalationManager(thresholdProvider = { watchSettingsCache.inactivityThreshold })
```

If `watchSettingsCache` is not yet declared in scope where `EscalationManager` is built, hoist its construction earlier in the function — `WatchSettingsCache` is constructed from `Context`, so it's a one-liner. (Look near the existing `WatchSettingsCache(this)` construction site as a reference.)

- [ ] **Step 11.6: Build wear**

Run: `./gradlew :wear:compileDebugKotlin :wear:testDebugUnitTest`
Expected: all green.

- [ ] **Step 11.7: Commit**

```bash
git add wear/src/main/java/com/meatsack/motivator/escalation/EscalationManager.kt \
        wear/src/test/java/com/meatsack/motivator/escalation/EscalationManagerTest.kt \
        wear/src/main/java/com/meatsack/motivator/MeatsackWearService.kt
git commit -m "feat: EscalationManager reads inactivityThreshold from cache (#23)"
```

---

## Task 12: `WatchSettingsReceiver` — TDD via factored `applySnapshot`

**Files:**
- Create: `wear/src/main/java/com/meatsack/motivator/sync/WatchSettingsReceiver.kt`
- Create: `wear/src/test/java/com/meatsack/motivator/sync/WatchSettingsReceiverTest.kt`
- Modify: `wear/src/main/AndroidManifest.xml`

We extract the "apply each field to the cache" logic into a pure function operating on a small `SettingsSink` interface (separate from the phone-side `SettingsSink` — keep names distinct; we'll call this one `SettingsApplySink` or use a sealed interface). To avoid name collision, the watch-side sink will live in the same `wear/sync` package and have a different name.

- [ ] **Step 12.1: Write the failing test**

```kotlin
package com.meatsack.motivator.sync

import com.meatsack.shared.sync.SettingsSnapshot
import org.junit.Assert.assertEquals
import org.junit.Test

class WatchSettingsReceiverTest {

    /** Map-backed fake that records all writes. */
    private class FakeApplySink : WatchSettingsReceiver.ApplySink {
        var dailyStepGoal: Int = -1
        var inactivityThreshold: Int = -1
        var activeHoursStart: Int = -1
        var activeHoursEnd: Int = -1
        var contextAwareEnabled: Boolean = false
        var endOfDayHour: Int = -1
        var behindPaceCheckHour: Int = -1
        override fun setDailyStepGoal(v: Int) { dailyStepGoal = v }
        override fun setInactivityThreshold(v: Int) { inactivityThreshold = v }
        override fun setActiveHoursStart(v: Int) { activeHoursStart = v }
        override fun setActiveHoursEnd(v: Int) { activeHoursEnd = v }
        override fun setContextAwareEnabled(v: Boolean) { contextAwareEnabled = v }
        override fun setEndOfDayHour(v: Int) { endOfDayHour = v }
        override fun setBehindPaceCheckHour(v: Int) { behindPaceCheckHour = v }
    }

    @Test
    fun applySnapshot_writesEveryField() {
        val sink = FakeApplySink()
        val snap = SettingsSnapshot(
            dailyStepGoal = 12_000,
            inactivityThreshold = 45,
            activeHoursStart = 8,
            activeHoursEnd = 21,
            contextAwareEnabled = true,
            endOfDayHour = 20,
            behindPaceCheckHour = 14,
        )
        WatchSettingsReceiver.applySnapshot(snap, sink)
        assertEquals(12_000, sink.dailyStepGoal)
        assertEquals(45, sink.inactivityThreshold)
        assertEquals(8, sink.activeHoursStart)
        assertEquals(21, sink.activeHoursEnd)
        assertEquals(true, sink.contextAwareEnabled)
        assertEquals(20, sink.endOfDayHour)
        assertEquals(14, sink.behindPaceCheckHour)
    }
}
```

- [ ] **Step 12.2: Run, expecting compile failure**

Run: `./gradlew :wear:testDebugUnitTest --tests "com.meatsack.motivator.sync.WatchSettingsReceiverTest"`

Expected: COMPILE FAILURE — `WatchSettingsReceiver` doesn't exist.

- [ ] **Step 12.3: Implement `WatchSettingsReceiver`**

```kotlin
package com.meatsack.motivator.sync

import android.util.Log
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService
import com.meatsack.motivator.settings.WatchSettingsCache
import com.meatsack.shared.sync.SettingsKeys
import com.meatsack.shared.sync.SettingsSnapshot

class WatchSettingsReceiver : WearableListenerService() {

    companion object {
        private const val TAG = "WatchSettingsReceiver"

        /**
         * Pure function. Apply the snapshot field-by-field to the sink.
         * Extracted so unit tests don't need a WearableListenerService instance.
         */
        fun applySnapshot(snap: SettingsSnapshot, sink: ApplySink) {
            sink.setDailyStepGoal(snap.dailyStepGoal)
            sink.setInactivityThreshold(snap.inactivityThreshold)
            sink.setActiveHoursStart(snap.activeHoursStart)
            sink.setActiveHoursEnd(snap.activeHoursEnd)
            sink.setContextAwareEnabled(snap.contextAwareEnabled)
            sink.setEndOfDayHour(snap.endOfDayHour)
            sink.setBehindPaceCheckHour(snap.behindPaceCheckHour)
        }
    }

    /** Minimal write surface that WatchSettingsCache satisfies via cacheAdapter(). */
    interface ApplySink {
        fun setDailyStepGoal(v: Int)
        fun setInactivityThreshold(v: Int)
        fun setActiveHoursStart(v: Int)
        fun setActiveHoursEnd(v: Int)
        fun setContextAwareEnabled(v: Boolean)
        fun setEndOfDayHour(v: Int)
        fun setBehindPaceCheckHour(v: Int)
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        dataEvents.forEach { event ->
            val path = event.dataItem.uri.path
            if (path != SettingsKeys.PATH) return@forEach
            val sourceNode = event.dataItem.uri.host
            try {
                val dm = DataMapItem.fromDataItem(event.dataItem).dataMap
                val snap = SettingsSnapshot.fromDataMap(dm)
                val cache = WatchSettingsCache(applicationContext)
                applySnapshot(snap, cacheAdapter(cache))
                Log.d(TAG, "Applied settings from node=$sourceNode: $snap")
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to apply settings from node=$sourceNode", t)
            }
        }
    }

    private fun cacheAdapter(cache: WatchSettingsCache): ApplySink = object : ApplySink {
        override fun setDailyStepGoal(v: Int) { cache.dailyStepGoal = v }
        override fun setInactivityThreshold(v: Int) { cache.inactivityThreshold = v }
        override fun setActiveHoursStart(v: Int) { cache.activeHoursStart = v }
        override fun setActiveHoursEnd(v: Int) { cache.activeHoursEnd = v }
        override fun setContextAwareEnabled(v: Boolean) { cache.contextAwareEnabled = v }
        override fun setEndOfDayHour(v: Int) { cache.endOfDayHour = v }
        override fun setBehindPaceCheckHour(v: Int) { cache.behindPaceCheckHour = v }
    }
}
```

- [ ] **Step 12.4: Register receiver in `AndroidManifest.xml`**

Open `wear/src/main/AndroidManifest.xml`. Find the existing `WatchSyncReceiver` registration and locate its pattern. Add an adjacent `<service>` element for `WatchSettingsReceiver` with a `<data-item-filter>` for the `/settings` path. The structure should mirror the existing receiver. For reference, the form is approximately:

```xml
<service
    android:name=".sync.WatchSettingsReceiver"
    android:exported="true">
    <intent-filter>
        <action android:name="com.google.android.gms.wearable.DATA_CHANGED" />
        <data
            android:scheme="wear"
            android:host="*"
            android:pathPrefix="/settings" />
    </intent-filter>
</service>
```

(Verify the exact tag shapes against the existing `WatchSyncReceiver` block — the path attribute name in particular may be `android:path` rather than `android:pathPrefix` depending on what the existing entry uses.)

- [ ] **Step 12.5: Run tests + build**

Run: `./gradlew :wear:testDebugUnitTest --tests "com.meatsack.motivator.sync.WatchSettingsReceiverTest" :wear:compileDebugKotlin`
Expected: test passes, BUILD SUCCESSFUL.

- [ ] **Step 12.6: Commit**

```bash
git add wear/src/main/java/com/meatsack/motivator/sync/WatchSettingsReceiver.kt \
        wear/src/test/java/com/meatsack/motivator/sync/WatchSettingsReceiverTest.kt \
        wear/src/main/AndroidManifest.xml
git commit -m "feat: WatchSettingsReceiver consumes /settings DataItem (#23)"
```

---

## Task 13: Full build + unit tests + manual end-to-end smoke

**Files:** none modified.

- [ ] **Step 13.1: Full build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL across all modules. spotlessCheck must pass (run `./gradlew spotlessApply && git add -u` if it complains).

- [ ] **Step 13.2: Full unit test sweep**

Run: `./gradlew :shared:testDebugUnitTest :mobile:testDebugUnitTest :wear:testDebugUnitTest`
Expected: all green.

- [ ] **Step 13.3: Install on paired emulators**

Confirm the phone (`emulator-5554`) and watch (`emulator-5556`) emulators are paired via Android Studio's Pair Wearable wizard; verify with:

```bash
adb -s emulator-5554 shell dumpsys activity service com.google.android.gms/.wearable.service.WearableService | grep "connected out of"
```

Expected: `1 connected out of 1`. If not, re-run the pairing wizard (or `adb -s emulator-5554 forward tcp:5601 tcp:5601` if it's just the tunnel that dropped).

Then:

```bash
./gradlew :mobile:installDebug :wear:installDebug
adb -s emulator-5556 shell pm grant com.meatsack.motivator android.permission.ACTIVITY_RECOGNITION
```

- [ ] **Step 13.4: Manual E2E checklist**

1. Tail watch logcat: `adb -s emulator-5556 logcat -s WatchSettingsReceiver:V WatchSettingsCache:V`
2. Tail phone logcat: `adb -s emulator-5554 logcat -s PhoneSettingsSyncer:V MainActivity:V`
3. Launch phone app. Confirm phone logcat shows `PhoneSettingsSyncer started` then a `Synced settings: SettingsSnapshot(...)` shortly after (from the `onResume → syncNow` path).
4. Open the Settings tab. Drag the `Daily step goal` slider to 12000.
5. Within ~2 seconds, watch logcat should show `Applied settings from node=...: SettingsSnapshot(dailyStepGoal=12000, ...)`.
6. Adjust `Behind-pace check hour` slider. Verify another apply log on the watch.
7. Toggle the `Context-aware language` switch. Verify the boolean propagated.
8. Force-stop the phone app, relaunch — confirm one more `Synced settings: ...` log fires from `onResume`.
9. Open the watch app. Inactivity simulation (drop `EscalationLevel.INACTIVITY_THRESHOLD_MINUTES_DEFAULT` to 1 temporarily per CLAUDE.md guidance, OR simply trust the unit tests for the `EscalationManager` rewire) — verify the inactivity threshold the watch uses now reflects the phone setting if you changed it.

- [ ] **Step 13.5: Push the branch**

```bash
git push -u origin feature/phone-watch-settings-sync
```

- [ ] **Step 13.6: Open the PR**

```bash
gh pr create --title "Phone→watch settings sync via /settings DataItem (closes #23)" --body "$(cat <<'EOF'
## Summary
- Adds a `/settings` Wear Data Layer channel carrying the 7 watch-relevant settings via DataMap-native typed keys.
- New shared types: `SettingsKeys`, `SettingsSnapshot` (with `toDataMap`/`fromDataMap` + `coerceIn(0..23)` for hour fields), and hoisted `SettingsDefaults` — single source of truth that makes future schema drift a compile error.
- `PhoneSettingsSyncer` observes `SettingsRepository` flows (debounce 500ms + distinctUntilChanged + bounded retryWhen) and exposes `syncNow()` called from `MainActivity.onResume`.
- `WatchSettingsReceiver` consumes `/settings` and applies into `WatchSettingsCache`. Pure `applySnapshot` factored for unit testing.
- Adds `behindPaceCheckHour` slider to phone UI (was watch-only).
- Rewires `EscalationManager` to read `inactivityThreshold` from `WatchSettingsCache` via injected `thresholdProvider: () -> Int` (was hardcoded to `EscalationLevel.INACTIVITY_THRESHOLD_MINUTES_DEFAULT`).

Closes #23. Partially addresses #25 (defense-in-depth `coerceIn` on the wire).

## Test plan
- [x] `./gradlew :shared:testDebugUnitTest :mobile:testDebugUnitTest :wear:testDebugUnitTest` all pass
- [x] `./gradlew build` (including spotlessCheck) passes
- [x] Manual E2E on paired emulators per spec checklist: phone slider edits propagate to watch within ~2 sec; force-stop + relaunch fires one more snapshot; toggles + hour pickers all propagate.

## Spec
`docs/superpowers/specs/2026-05-30-phone-watch-settings-sync-design.md`
EOF
)"
```

- [ ] **Step 13.7: Per-project workflow, run the PR review skill**

After the PR is created, hand off to the `pr-review-toolkit:review-pr` skill against the new PR number (the user's CLAUDE.md / memory rule: "Review every PR" — after `gh pr create`, invoke the review skill before declaring done).

---

## Risks / known unknowns flagged inline

- **DataMap pure-JVM instantiation:** Task 3 verifies. If it fails, Task 4 Step 4.3 adds Robolectric to `:shared/src/test`. Cost: one extra dep declaration.
- **AndroidManifest filter shape:** Task 12 Step 12.4 — verify against the existing `WatchSyncReceiver` entry. If the existing entry uses `android:path` not `android:pathPrefix`, mirror that.
- **`first` import shape:** Task 9 Step 9.1 — both top-level and extension forms exist in `kotlinx-coroutines-core`. Use whichever matches project idioms (`grep -rn "\.first()" mobile/src/main`).
- **`MeatsackWearService` `EscalationManager` call site:** Task 11 Step 11.5 — the exact construction site may need `WatchSettingsCache` hoisted earlier in the function. Investigate first; the fix is small.

---

## Out of scope (deliberately deferred — track separately)

- Phone-side surfacing of setter validation failures (#21).
- Worker silent-failure hardening (#24).
- `WatchSettingsCache.coerceIn(0..23)` defense at *read* time (#25; we cover the *wire* coerce here but not the cache).
- Deciding whether/how `quietHoursStart`/`quietHoursEnd` should reach the watch (file new issue).
- Surfacing `endOfDayHour` in the phone UI (the `SettingsViewModel` exposes it but `SettingsScreen.kt` has no picker for it — discovered while writing this plan, file new issue).
