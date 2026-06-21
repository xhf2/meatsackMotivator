# Pace-message Enable Toggles Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add two independent phone-app toggles — "Behind-pace messages" and "End-of-day messages" (both default on) — that stop the corresponding watch workers from delivering insults when off.

**Architecture:** Two new `Boolean` settings ride the existing phone→watch settings pipeline (`SettingsSnapshot` → `/settings` DataItem → `WatchSettingsCache`). The two daily workers gate on the flag immediately after their self-reschedule, so "off" means *skip this run* without cancelling the WorkManager chain. No new sync infrastructure.

**Tech Stack:** Kotlin, AndroidX DataStore (phone), SharedPreferences (watch), Wear Data Layer (`DataMap`/`DataItem`), WorkManager `CoroutineWorker`, Jetpack Compose Material3, JUnit.

**Spec:** `docs/superpowers/specs/2026-06-21-pace-toggles-design.md`

---

## File Structure

Atomic wire-shape change (Task 1 — one commit, because adding two *required* fields to `SettingsSnapshot` breaks every module until all call sites are updated, and the pre-commit hook compiles + tests all three modules):

- `shared/src/main/java/com/meatsack/shared/sync/SettingsDefaults.kt` — +2 default consts
- `shared/src/main/java/com/meatsack/shared/sync/SettingsKeys.kt` — +2 wire keys
- `shared/src/main/java/com/meatsack/shared/sync/SettingsSnapshot.kt` — +2 fields, wire `toDataMap`/`fromDataMap`/`defaults`
- `shared/src/test/java/com/meatsack/shared/sync/SettingsSnapshotTest.kt` — extend round-trip + defaults assertions
- `mobile/src/main/java/com/meatsack/motivator/mobile/data/SettingsRepository.kt` — +2 keys, flows, setters
- `mobile/src/main/java/com/meatsack/motivator/mobile/sync/PhoneSettingsSyncer.kt` — +2 flows in `combine` and `current()`
- `wear/src/main/java/com/meatsack/motivator/settings/WatchSettingsCache.kt` — +2 boolean properties + keys
- `wear/src/main/java/com/meatsack/motivator/sync/WatchSettingsReceiver.kt` — +2 in `ApplySink`/`applySnapshot`/`cacheAdapter`
- `wear/src/test/java/com/meatsack/motivator/sync/WatchSettingsReceiverTest.kt` — extend fake + assertions

Additive changes (separate commits):

- Task 2 — `mobile/.../ui/settings/SettingsViewModel.kt` + `SettingsScreen.kt` (phone UI)
- Task 3 — `wear/.../trigger/BehindPaceWorker.kt` (gate)
- Task 4 — `wear/.../trigger/EndOfDayWorker.kt` (gate)
- Task 5 — full build + verification

**Branch:** `feature/pace-message-toggles` (already created; spec already committed there).

---

### Task 1: Add the two flags to the settings wire-shape (atomic)

This task must land as **one commit** — the field addition breaks `mobile` and `wear` compilation until every call site is updated, and the pre-commit hook builds all three modules.

**Files:**
- Modify: `shared/src/main/java/com/meatsack/shared/sync/SettingsDefaults.kt`
- Modify: `shared/src/main/java/com/meatsack/shared/sync/SettingsKeys.kt`
- Modify: `shared/src/main/java/com/meatsack/shared/sync/SettingsSnapshot.kt`
- Test: `shared/src/test/java/com/meatsack/shared/sync/SettingsSnapshotTest.kt`
- Modify: `mobile/src/main/java/com/meatsack/motivator/mobile/data/SettingsRepository.kt`
- Modify: `mobile/src/main/java/com/meatsack/motivator/mobile/sync/PhoneSettingsSyncer.kt`
- Modify: `wear/src/main/java/com/meatsack/motivator/settings/WatchSettingsCache.kt`
- Modify: `wear/src/main/java/com/meatsack/motivator/sync/WatchSettingsReceiver.kt`
- Test: `wear/src/test/java/com/meatsack/motivator/sync/WatchSettingsReceiverTest.kt`

- [ ] **Step 1: Write the failing test — extend `SettingsSnapshotTest`**

In `shared/src/test/java/com/meatsack/shared/sync/SettingsSnapshotTest.kt`:

In `defaults_matchSettingsDefaults()`, add two assertions after the `behindPaceCheckHour` line (line 18):

```kotlin
        assertEquals(SettingsDefaults.BEHIND_PACE_ENABLED, snap.behindPaceEnabled)
        assertEquals(SettingsDefaults.END_OF_DAY_ENABLED, snap.endOfDayEnabled)
```

In `toDataMap_fromDataMap_roundTrip()`, change the `SettingsSnapshot(...)` literal to add the two fields with **non-default** values (defaults are both `true`, so use `false`/`false` to prove the values actually travel rather than coincidentally matching the default):

```kotlin
        val original = SettingsSnapshot(
            dailyStepGoal = 12_345,
            inactivityThreshold = 45,
            activeHoursStart = 8,
            activeHoursEnd = 21,
            contextAwareEnabled = true,
            endOfDayHour = 20,
            behindPaceCheckHour = 14,
            behindPaceEnabled = false,
            endOfDayEnabled = false,
        )
```

(The `fromDataMap_missingKeys_returnsDefaults` test needs no change — it already asserts equality to `SettingsSnapshot.defaults`, which now covers the two new defaults.)

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :shared:testDebugUnitTest --tests "com.meatsack.shared.sync.SettingsSnapshotTest"`
Expected: FAIL — compilation error, `unresolved reference: behindPaceEnabled` / `endOfDayEnabled` (the field doesn't exist yet).

- [ ] **Step 3: Add the two defaults**

In `shared/src/main/java/com/meatsack/shared/sync/SettingsDefaults.kt`, add after the `BEHIND_PACE_CHECK_HOUR` line (inside the `object`):

```kotlin
    const val BEHIND_PACE_ENABLED = true
    const val END_OF_DAY_ENABLED = true
```

- [ ] **Step 4: Add the two wire keys**

In `shared/src/main/java/com/meatsack/shared/sync/SettingsKeys.kt`, add after `KEY_BEHIND_PACE_CHECK_HOUR`:

```kotlin
    const val KEY_BEHIND_PACE_ENABLED = "behind_pace_enabled"
    const val KEY_END_OF_DAY_ENABLED = "end_of_day_enabled"
```

- [ ] **Step 5: Add the two fields to `SettingsSnapshot` and wire them**

In `shared/src/main/java/com/meatsack/shared/sync/SettingsSnapshot.kt`:

Add to the data class constructor, after `behindPaceCheckHour: Int,`:

```kotlin
    val behindPaceEnabled: Boolean,
    val endOfDayEnabled: Boolean,
```

Add to `toDataMap`, after the `KEY_BEHIND_PACE_CHECK_HOUR` put:

```kotlin
        dm.putBoolean(SettingsKeys.KEY_BEHIND_PACE_ENABLED, behindPaceEnabled)
        dm.putBoolean(SettingsKeys.KEY_END_OF_DAY_ENABLED, endOfDayEnabled)
```

Add to the `defaults` initializer, after `behindPaceCheckHour = SettingsDefaults.BEHIND_PACE_CHECK_HOUR,`:

```kotlin
            behindPaceEnabled = SettingsDefaults.BEHIND_PACE_ENABLED,
            endOfDayEnabled = SettingsDefaults.END_OF_DAY_ENABLED,
```

Add to `fromDataMap`, after the `behindPaceCheckHour = ...coerceIn(0, 23),` line:

```kotlin
            behindPaceEnabled = dm.getBoolean(SettingsKeys.KEY_BEHIND_PACE_ENABLED, defaults.behindPaceEnabled),
            endOfDayEnabled = dm.getBoolean(SettingsKeys.KEY_END_OF_DAY_ENABLED, defaults.endOfDayEnabled),
```

- [ ] **Step 6: Run the shared test to verify it passes**

Run: `./gradlew :shared:testDebugUnitTest --tests "com.meatsack.shared.sync.SettingsSnapshotTest"`
Expected: PASS (4 tests). `:shared` now compiles; `:mobile` and `:wear` do **not** yet — continue.

- [ ] **Step 7: Add the two flags to the phone `SettingsRepository`**

In `mobile/src/main/java/com/meatsack/motivator/mobile/data/SettingsRepository.kt`:

In the `companion object`, after `BEHIND_PACE_CHECK_HOUR`:

```kotlin
        val BEHIND_PACE_ENABLED = booleanPreferencesKey("behind_pace_enabled")
        val END_OF_DAY_ENABLED = booleanPreferencesKey("end_of_day_enabled")
```

After the `behindPaceCheckHour` flow:

```kotlin
    val behindPaceEnabled: Flow<Boolean> = context.dataStore.data.map {
        it[BEHIND_PACE_ENABLED] ?: SharedDefaults.BEHIND_PACE_ENABLED
    }
    val endOfDayEnabled: Flow<Boolean> = context.dataStore.data.map {
        it[END_OF_DAY_ENABLED] ?: SharedDefaults.END_OF_DAY_ENABLED
    }
```

After the `setBehindPaceCheckHour` function (booleans need no range validation):

```kotlin
    suspend fun setBehindPaceEnabled(enabled: Boolean) {
        context.dataStore.edit { it[BEHIND_PACE_ENABLED] = enabled }
    }

    suspend fun setEndOfDayEnabled(enabled: Boolean) {
        context.dataStore.edit { it[END_OF_DAY_ENABLED] = enabled }
    }
```

- [ ] **Step 8: Wire the two flags into `RepositorySettingsSource`**

In `mobile/src/main/java/com/meatsack/motivator/mobile/sync/PhoneSettingsSyncer.kt`, replace the `RepositorySettingsSource.snapshots` `combine(...)` block (currently 7 flows) with the 9-flow version:

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
        )
    }
```

And in `current()`, add the two fields after `behindPaceCheckHour = repo.behindPaceCheckHour.first(),`:

```kotlin
            behindPaceEnabled = repo.behindPaceEnabled.first(),
            endOfDayEnabled = repo.endOfDayEnabled.first(),
```

- [ ] **Step 9: Add the two flags to `WatchSettingsCache`**

In `wear/src/main/java/com/meatsack/motivator/settings/WatchSettingsCache.kt`, after the `inactivityThreshold` property:

```kotlin
    var behindPaceEnabled: Boolean
        get() = prefs.getBoolean(KEY_BEHIND_PACE_ENABLED, SettingsDefaults.BEHIND_PACE_ENABLED)
        set(value) = prefs.edit().putBoolean(KEY_BEHIND_PACE_ENABLED, value).apply()

    var endOfDayEnabled: Boolean
        get() = prefs.getBoolean(KEY_END_OF_DAY_ENABLED, SettingsDefaults.END_OF_DAY_ENABLED)
        set(value) = prefs.edit().putBoolean(KEY_END_OF_DAY_ENABLED, value).apply()
```

In the `companion object`, after `KEY_INACTIVITY_THRESHOLD`:

```kotlin
        private const val KEY_BEHIND_PACE_ENABLED = "behind_pace_enabled"
        private const val KEY_END_OF_DAY_ENABLED = "end_of_day_enabled"
```

- [ ] **Step 10: Add the two flags to `WatchSettingsReceiver`**

In `wear/src/main/java/com/meatsack/motivator/sync/WatchSettingsReceiver.kt`:

In `applySnapshot`, after `sink.setBehindPaceCheckHour(snap.behindPaceCheckHour)`:

```kotlin
            sink.setBehindPaceEnabled(snap.behindPaceEnabled)
            sink.setEndOfDayEnabled(snap.endOfDayEnabled)
```

In the `ApplySink` interface, after `fun setBehindPaceCheckHour(v: Int)`:

```kotlin
        fun setBehindPaceEnabled(v: Boolean)
        fun setEndOfDayEnabled(v: Boolean)
```

In `cacheAdapter`, after the `setBehindPaceCheckHour` override:

```kotlin
        override fun setBehindPaceEnabled(v: Boolean) {
            cache.behindPaceEnabled = v
        }
        override fun setEndOfDayEnabled(v: Boolean) {
            cache.endOfDayEnabled = v
        }
```

- [ ] **Step 11: Extend `WatchSettingsReceiverTest`**

In `wear/src/test/java/com/meatsack/motivator/sync/WatchSettingsReceiverTest.kt`:

In `FakeApplySink`, add two recorded fields after `recordedBehindPaceCheckHour`:

```kotlin
        var recordedBehindPaceEnabled: Boolean = true
        var recordedEndOfDayEnabled: Boolean = true
```

and two overrides after `setBehindPaceCheckHour`:

```kotlin
        override fun setBehindPaceEnabled(v: Boolean) {
            recordedBehindPaceEnabled = v
        }
        override fun setEndOfDayEnabled(v: Boolean) {
            recordedEndOfDayEnabled = v
        }
```

In `applySnapshot_writesEveryField()`, change the `SettingsSnapshot(...)` literal to include the two fields with non-default values:

```kotlin
        val snap = SettingsSnapshot(
            dailyStepGoal = 12_000,
            inactivityThreshold = 45,
            activeHoursStart = 8,
            activeHoursEnd = 21,
            contextAwareEnabled = true,
            endOfDayHour = 20,
            behindPaceCheckHour = 14,
            behindPaceEnabled = false,
            endOfDayEnabled = false,
        )
```

and add two assertions after the `recordedBehindPaceCheckHour` assert:

```kotlin
        assertEquals(false, sink.recordedBehindPaceEnabled)
        assertEquals(false, sink.recordedEndOfDayEnabled)
```

- [ ] **Step 12: Build all modules and run all unit tests**

Run: `./gradlew :shared:testDebugUnitTest :mobile:testDebugUnitTest :wear:testDebugUnitTest`
Expected: BUILD SUCCESSFUL; all tests pass (including the extended `SettingsSnapshotTest` and `WatchSettingsReceiverTest`). This confirms the breaking change is fully propagated.

- [ ] **Step 13: Verify formatting**

Run: `./gradlew spotlessCheck`
Expected: BUILD SUCCESSFUL. (If it fails: `./gradlew spotlessApply && git add -u`.)

- [ ] **Step 14: Commit**

```bash
git add shared/src/main/java/com/meatsack/shared/sync/SettingsDefaults.kt \
        shared/src/main/java/com/meatsack/shared/sync/SettingsKeys.kt \
        shared/src/main/java/com/meatsack/shared/sync/SettingsSnapshot.kt \
        shared/src/test/java/com/meatsack/shared/sync/SettingsSnapshotTest.kt \
        mobile/src/main/java/com/meatsack/motivator/mobile/data/SettingsRepository.kt \
        mobile/src/main/java/com/meatsack/motivator/mobile/sync/PhoneSettingsSyncer.kt \
        wear/src/main/java/com/meatsack/motivator/settings/WatchSettingsCache.kt \
        wear/src/main/java/com/meatsack/motivator/sync/WatchSettingsReceiver.kt \
        wear/src/test/java/com/meatsack/motivator/sync/WatchSettingsReceiverTest.kt
git commit -m "feat(settings): add behindPaceEnabled + endOfDayEnabled to settings wire-shape"
```

---

### Task 2: Expose the two toggles in the phone Settings UI

Additive — depends on Task 1's repository flows/setters. No automated test: this project has no Compose UI test harness, and the ViewModel methods are thin passthroughs to already-tested repository setters (mirrors the existing `toggleContextAware` pattern, which is also untested at the unit level). Verified by build + manual.

**Files:**
- Modify: `mobile/src/main/java/com/meatsack/motivator/mobile/ui/settings/SettingsViewModel.kt`
- Modify: `mobile/src/main/java/com/meatsack/motivator/mobile/ui/settings/SettingsScreen.kt`

- [ ] **Step 1: Add the ViewModel flows and toggle functions**

In `SettingsViewModel.kt`, after the `behindPaceCheckHour` `stateIn` block:

```kotlin
    val behindPaceEnabled = repo.behindPaceEnabled.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(),
        SharedDefaults.BEHIND_PACE_ENABLED,
    )
    val endOfDayEnabled = repo.endOfDayEnabled.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(),
        SharedDefaults.END_OF_DAY_ENABLED,
    )
```

After the `updateBehindPaceCheckHour` function:

```kotlin
    fun toggleBehindPaceEnabled(enabled: Boolean) =
        viewModelScope.launch { repo.setBehindPaceEnabled(enabled) }
    fun toggleEndOfDayEnabled(enabled: Boolean) =
        viewModelScope.launch { repo.setEndOfDayEnabled(enabled) }
```

- [ ] **Step 2: Add the two `Switch` rows to `SettingsScreen`**

In `SettingsScreen.kt`, insert the following block immediately **after** the behind-pace section's closing `Spacer(Modifier.height(16.dp))` (the one right after the "Time of day to check whether you're behind your step goal." help text) and **before** the `Spacer(Modifier.height(24.dp))` that precedes the "Anthropic API key" section:

```kotlin
        val behindPaceEnabled by viewModel.behindPaceEnabled.collectAsState()
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                "Behind-pace messages",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = behindPaceEnabled,
                onCheckedChange = { viewModel.toggleBehindPaceEnabled(it) },
            )
        }
        Text(
            "When off, the watch won't nag you for falling behind your step pace during the day.",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(16.dp))

        val endOfDayEnabled by viewModel.endOfDayEnabled.collectAsState()
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                "End-of-day messages",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = endOfDayEnabled,
                onCheckedChange = { viewModel.toggleEndOfDayEnabled(it) },
            )
        }
        Text(
            "When off, the watch won't nag you at the end of the day for missing your step goal.",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(16.dp))
```

(`Row`, `Switch`, `Alignment`, `collectAsState`, `Spacer`, `MaterialTheme`, `Text` are all already imported in this file — confirmed against the current imports.)

- [ ] **Step 3: Compile and verify formatting**

Run: `./gradlew :mobile:compileDebugKotlin && ./gradlew :mobile:spotlessCheck`
Expected: BUILD SUCCESSFUL. (If spotless fails: `./gradlew spotlessApply && git add -u`.)

- [ ] **Step 4: Commit**

```bash
git add mobile/src/main/java/com/meatsack/motivator/mobile/ui/settings/SettingsViewModel.kt \
        mobile/src/main/java/com/meatsack/motivator/mobile/ui/settings/SettingsScreen.kt
git commit -m "feat(settings): behind-pace + end-of-day toggle switches in Settings UI"
```

---

### Task 3: Gate `BehindPaceWorker` on the flag

Additive. The gate is a trivial early-return reading `WatchSettingsCache`; the worker is framework-bound (no unit harness in this project), so it's verified by build + the on-device check in Task 5. Placed **after** the self-reschedule so a disabled run can't break the daily chain.

**Files:**
- Modify: `wear/src/main/java/com/meatsack/motivator/trigger/BehindPaceWorker.kt`

- [ ] **Step 1: Insert the gate**

In `BehindPaceWorker.doWork()`, immediately after the reschedule `try { ... } catch (...) { return Result.retry() }` block (before the `val db = AppDatabase.getDatabase(ctx)` line), add:

```kotlin
        if (!settings.behindPaceEnabled) {
            Log.d(TAG, "Behind-pace messages disabled; skipping")
            return Result.success()
        }
```

(`settings` is the `WatchSettingsCache(ctx)` already constructed at the top of `doWork`; `Log` and `TAG` are already in scope.)

- [ ] **Step 2: Compile and verify formatting**

Run: `./gradlew :wear:compileDebugKotlin && ./gradlew :wear:spotlessCheck`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add wear/src/main/java/com/meatsack/motivator/trigger/BehindPaceWorker.kt
git commit -m "feat(wear): skip BehindPaceWorker delivery when behind-pace disabled"
```

---

### Task 4: Gate `EndOfDayWorker` on the flag

Same shape as Task 3, for the end-of-day worker.

**Files:**
- Modify: `wear/src/main/java/com/meatsack/motivator/trigger/EndOfDayWorker.kt`

- [ ] **Step 1: Insert the gate**

In `EndOfDayWorker.doWork()`, immediately after the reschedule `try { ... } catch (...) { return Result.retry() }` block (before the `val db = AppDatabase.getDatabase(ctx)` line), add:

```kotlin
        if (!settings.endOfDayEnabled) {
            Log.d(TAG, "End-of-day messages disabled; skipping")
            return Result.success()
        }
```

(`settings` is the `WatchSettingsCache(ctx)` already constructed at the top of `doWork`; `Log` and `TAG` are already in scope.)

- [ ] **Step 2: Compile and verify formatting**

Run: `./gradlew :wear:compileDebugKotlin && ./gradlew :wear:spotlessCheck`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add wear/src/main/java/com/meatsack/motivator/trigger/EndOfDayWorker.kt
git commit -m "feat(wear): skip EndOfDayWorker delivery when end-of-day disabled"
```

---

### Task 5: Full build + on-device smoke + PR

**Files:** none (verification only).

- [ ] **Step 1: Full build + all tests**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL (all modules compile, ktlint clean, all unit tests pass).

- [ ] **Step 2: Install on the paired emulators / devices**

```bash
./gradlew :mobile:assembleDebug :wear:assembleDebug
adb -s <phone-id> install -r mobile/build/outputs/apk/debug/mobile-debug.apk
adb -s <watch-id> install -r wear/build/outputs/apk/debug/wear-debug.apk
```

- [ ] **Step 3: Verify the toggle propagates phone → watch**

On the phone app: open **Settings**, toggle **Behind-pace messages** off. Within ~1–2 s, confirm the watch applied it:

```bash
adb -s <watch-id> logcat -d -s WatchSettingsReceiver | grep -i "behindPaceEnabled=false"
```

Expected: a log line `Applied settings ... behindPaceEnabled=false ...`. Repeat for **End-of-day messages** → expect `endOfDayEnabled=false`.

- [ ] **Step 4: Verify the worker honors the flag (optional, log-based)**

Because the workers fire on a daily schedule, the practical confirmation is the cache value above plus the gate log. If you want to force a run, the gate emits at `DEBUG`:

```bash
adb -s <watch-id> logcat -d -s BehindPaceWorker EndOfDayWorker | grep -i "disabled; skipping"
```

Expected at the next scheduled run (or a manually triggered run): `Behind-pace messages disabled; skipping` / `End-of-day messages disabled; skipping`. Toggle both back on and confirm the skip log stops on the following run.

- [ ] **Step 5: Push and open the PR**

```bash
git push -u origin feature/pace-message-toggles
gh pr create --fill --base main
```

Then review the PR with `pr-review-toolkit:review-pr` before merging (per project workflow).

---

## Notes for the implementer

- **Why Task 1 is one big commit:** `SettingsSnapshot` keeps all fields *required* (no default args) by design, so the compiler forces every reader/writer to acknowledge a new field. Adding the two fields breaks `:mobile` and `:wear` until their call sites are updated, and the pre-commit hook compiles + tests all three modules — so the change cannot be split into smaller green commits. Do not "fix" this by giving the new fields default values; the compile-time fan-out is the intended safety net.
- **Defaults are `true` everywhere** (`SettingsDefaults`, repository fallback, watch-cache fallback, `SettingsSnapshot.defaults`). An older phone that never sends these keys leaves both messages enabled — `DataMap.getBoolean(key, default)` returns the default for absent keys. Verified by `fromDataMap_missingKeys_returnsDefaults`.
- **Disable = skip, not cancel.** The gate sits after the self-reschedule, so toggling off never cancels WorkManager and toggling back on resumes at the next scheduled run with no app re-open. A disable therefore takes effect at the next daily cycle, not instantly — acceptable since each worker fires at most once per day.
- **Inactivity messages are untouched** — neither flag is read by `MeatsackWearService` / `EscalationManager`.
- **Do not commit to `main`** — work stays on `feature/pace-message-toggles`; PR into `main`.
