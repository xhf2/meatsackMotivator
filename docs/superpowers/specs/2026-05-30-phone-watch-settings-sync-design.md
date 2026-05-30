# Phone → Watch Settings Sync — Design

> **Status:** approved design, pre-implementation.
> **Closes:** [#23](https://github.com/xhf2/meatsackMotivator/issues/23).
> **Partially addresses:** [#25](https://github.com/xhf2/meatsackMotivator/issues/25) (defense-in-depth `coerceIn`).

## Problem

The watch reads user settings from `WatchSettingsCache` (a watch-local `SharedPreferences` cache), but nothing populates that cache from the phone. The `WatchSettingsCache` KDoc still references a "future settings-sync DataItem (Task 16)" that was never built. Today the user sees:

| Setting | Phone UI lets user change it? | Watch honors it? |
|---|---|---|
| `dailyStepGoal` | yes | no — watch uses default `10_000` |
| `endOfDayHour` | yes | no — watch uses default `21` |
| `activeHoursStart` | yes | no — watch uses default `7` |
| `activeHoursEnd` | yes | no — watch uses default `22` |
| `contextAwareEnabled` | yes | no — watch uses default `false` |
| `behindPaceCheckHour` | not exposed in phone UI | watch uses default `12` |
| `inactivityThreshold` | yes | not consumed on watch at all (`EscalationManager.kt:57,67` reads a hardcoded `EscalationLevel.INACTIVITY_THRESHOLD_MINUTES_DEFAULT`) |

The root architectural problem is *schema drift*: `SettingsRepository` (phone, 8 fields) and `WatchSettingsCache` (watch, 6 fields) evolved independently with no shared contract.

## Solution overview

Introduce a new Wear Data Layer path `/settings` carrying typed primitive keys in a `DataMap`. A single `SettingsSnapshot` data class in `:shared/sync/` is the source of truth for the field set; its `toDataMap`/`fromDataMap` pair is the source of truth for the wire encoding. A hoisted `:shared/sync/SettingsDefaults` is the source of truth for default values used by both modules.

Sync runs continuously while the phone app's process is alive (auto-push on change, debounced 500ms) and once on every phone-app foreground (full snapshot via `syncNow()` from `MainActivity.onResume`).

### Why DataMap-native (vs. `|`-delimited string or JSON)

- Zero serialization code; `DataMap` carries typed primitives natively.
- Adding a field is one `putInt` on phone + one `getInt` on watch.
- DataClient automatically dedupes by *bytes* — identical re-sends are no-ops.
- Idiomatic for small fixed-shape payloads. We use `|`-delimited only for `Message` because messages are a list of complex objects DataMap can't hold natively.

### Why shared `SettingsSnapshot` (vs. private to the syncer)

- *Compile-time* schema-drift protection — adding a field to the data class breaks both modules (writer + reader) until both handle it. That's the structural fix for the root cause of #23.
- One pure-function round-trip unit test catches key-name typos before runtime.
- Mirrors `MessageSerializer`'s placement in `:shared`.

### Why no timestamp in the payload

DataClient dedupes by content bytes. Including a per-send timestamp would make every send a unique payload and fire `onDataChanged` even when no setting changed. Settings have no "stale" semantics on the watch — the latest snapshot is always authoritative — so omitting timestamp is the right call. This is a deliberate divergence from `/messages` (which carries a timestamp for sync-recency UI).

## Architecture

```
                  PHONE                                  WATCH
   ┌─────────────────────────────────────┐    ┌──────────────────────────────┐
   │ SettingsRepository (DataStore Flows)│    │  WatchSettingsCache          │
   │  — 7 watch-relevant Flows           │    │   (SharedPreferences)        │
   └─────────────────┬───────────────────┘    └──────────────▲───────────────┘
                     │ combine(...)                          │
                     ▼                                       │
   ┌─────────────────────────────────────┐                   │
   │ PhoneSettingsSyncer                 │                   │
   │  — launched once from               │                   │
   │    MeatsackMobileApp.onCreate()     │                   │
   │  — collects combined Flow,          │                   │
   │    debounce(500ms),                 │                   │
   │    distinctUntilChanged(),          │                   │
   │    retryWhen(...),                  │                   │
   │    writeSnapshot(snap)              │                   │
   │  — exposes syncNow() called from    │                   │
   │    MainActivity.onResume()          │                   │
   └─────────────────┬───────────────────┘                   │
                     │ DataClient                            │
                     ▼                                       │
   ┌─────────────────────────────────────┐    ┌──────────────┴───────────────┐
   │ PutDataMapRequest(SettingsKeys.PATH)│───▶│ WatchSettingsReceiver        │
   │   putInt(KEY_DAILY_STEP_GOAL, ...)  │    │  (WearableListenerService)   │
   │   putInt(KEY_END_OF_DAY_HOUR, ...)  │    │   — onDataChanged filters     │
   │   ...                               │    │     SettingsKeys.PATH         │
   │                                     │    │   — SettingsSnapshot.from     │
   │                                     │    │     DataMap(dm) → apply       │
   │                                     │    │     field-by-field to cache   │
   └─────────────────────────────────────┘    └──────────────────────────────┘
```

## Decisions

### Trigger model — auto-push on change + on-resume full snapshot

- `PhoneSettingsSyncer` is constructed in `MeatsackMobileApp.onCreate()` and `start(applicationScope)` is called there too. Hosting in `Application` (not `MainActivity`) means it runs whether or not the user has the app open — important because users edit settings in one session and may not relaunch before the next inactivity event.
- `combine(...)` over the 7 watch-relevant `SettingsRepository` Flows produces a `SettingsSnapshot` each time any field changes. `debounce(500.milliseconds)` collapses slider drags into a single trailing write. `distinctUntilChanged()` suppresses redundant emissions.
- `MainActivity.onResume()` calls `lifecycleScope.launch { syncer.syncNow() }`. `syncNow()` reads `.first()` of each Flow, builds a snapshot, and writes — same code path as the auto-push collector. DataClient dedupes if nothing changed.
- The two paths cannot race: both call the same idempotent `writeSnapshot`; the worst case is two back-to-back writes where the second is a no-op.

### Scope — 7 watch-relevant settings, aligned schema

The sync payload covers 7 settings: the existing 6 watch fields (`contextAwareEnabled`, `activeHoursStart`, `activeHoursEnd`, `dailyStepGoal`, `endOfDayHour`, `behindPaceCheckHour`) plus `inactivityThreshold` (added to the watch and wired through `EscalationManager`).

In addition to syncing, this PR closes two pre-existing schema-drift bugs:

- **Add `behindPaceCheckHour` to phone:** new field on `SettingsRepository` + slider (0..23) on `SettingsScreen`. Currently watch-only with a hardcoded default.
- **Wire `inactivityThreshold` through to consumption:** `WatchSettingsCache` gains the field; `EscalationManager.kt:57,67` is rewired to read it instead of the hardcoded `EscalationLevel.INACTIVITY_THRESHOLD_MINUTES_DEFAULT`. The constructor takes a `thresholdProvider: () -> Int` to minimize Android coupling in tests.

Out of scope (file separate follow-ups if not already tracked):

- `quietHoursStart` / `quietHoursEnd` — phone exposes these but no consumer on the watch reads them; they're effectively phone-only state. Needs a separate feature discussion: do they suppress insults on the watch, or just shape the AI prompt? Track as a new issue.
- Surface validator exceptions to UI (issue #21).
- Worker silent-failure hardening (issue #24).

### Wire format — DataMap native typed keys, no timestamp

```kotlin
// shared/src/main/java/com/meatsack/shared/sync/SettingsKeys.kt
object SettingsKeys {
    const val PATH = "/settings"
    const val KEY_DAILY_STEP_GOAL = "daily_step_goal"
    const val KEY_INACTIVITY_THRESHOLD = "inactivity_threshold_min"
    const val KEY_ACTIVE_HOURS_START = "active_hours_start"
    const val KEY_ACTIVE_HOURS_END = "active_hours_end"
    const val KEY_CONTEXT_AWARE_ENABLED = "context_aware_enabled"
    const val KEY_END_OF_DAY_HOUR = "end_of_day_hour"
    const val KEY_BEHIND_PACE_CHECK_HOUR = "behind_pace_check_hour"
    // intentionally no KEY_TIMESTAMP — see "Why no timestamp"
}
```

### Defaults — hoisted to `:shared/sync/SettingsDefaults.kt`

```kotlin
// shared/src/main/java/com/meatsack/shared/sync/SettingsDefaults.kt
object SettingsDefaults {
    const val DAILY_STEP_GOAL = 10_000
    const val INACTIVITY_THRESHOLD_MIN = 30
    const val ACTIVE_HOURS_START = 7
    const val ACTIVE_HOURS_END = 22
    const val CONTEXT_AWARE_ENABLED = false
    const val END_OF_DAY_HOUR = 21
    const val BEHIND_PACE_CHECK_HOUR = 12
}
```

Phone's `mobile/.../data/SettingsDefaults` is kept but trimmed to hold only `QUIET_HOURS_START` and `QUIET_HOURS_END` (phone-only fields). All other constants move to `:shared/sync/SettingsDefaults`. `SettingsRepository` imports from both. `WatchSettingsCache`'s hardcoded literal defaults are replaced with references to the shared constants. Plus a new `BEHIND_PACE_CHECK_HOUR = 12` constant lands in `:shared/sync/SettingsDefaults` to back the new phone-side Flow.

## Components

### New files

| Path | Purpose |
|---|---|
| `shared/src/main/java/com/meatsack/shared/sync/SettingsKeys.kt` | DataMap path + key constants (see "Wire format"). |
| `shared/src/main/java/com/meatsack/shared/sync/SettingsDefaults.kt` | Default values used by phone and watch (see "Defaults"). |
| `shared/src/main/java/com/meatsack/shared/sync/SettingsSnapshot.kt` | `data class SettingsSnapshot(dailyStepGoal: Int, inactivityThreshold: Int, activeHoursStart: Int, activeHoursEnd: Int, contextAwareEnabled: Boolean, endOfDayHour: Int, behindPaceCheckHour: Int)`. Companion `defaults: SettingsSnapshot` built from `SettingsDefaults`. Companion `fromDataMap(dm: DataMap): SettingsSnapshot` reads each key with the matching default and applies `coerceIn(0..23)` to hour fields. Instance method `toDataMap(dm: DataMap)` writes each key. |
| `mobile/src/main/java/com/meatsack/motivator/mobile/sync/PhoneSettingsSyncer.kt` | See "Trigger model". Constructor `(context, repo, dataClient = Wearable.getDataClient(context))` for test injection. |
| `wear/src/main/java/com/meatsack/motivator/sync/WatchSettingsReceiver.kt` | `WearableListenerService`. `onDataChanged` filters `SettingsKeys.PATH`, calls `SettingsSnapshot.fromDataMap`, delegates to an `internal fun applySnapshot(snap, cache)` — the apply function is the unit-tested seam. |
| `shared/src/test/java/com/meatsack/shared/sync/SettingsSnapshotTest.kt` | Round-trip, defaults, coerceIn. |
| `mobile/src/test/java/com/meatsack/motivator/mobile/sync/PhoneSettingsSyncerTest.kt` | `syncNow` with fake DataClient; debounce + distinctUntilChanged via `kotlinx-coroutines-test`. |
| `wear/src/test/java/com/meatsack/motivator/sync/WatchSettingsReceiverTest.kt` | Pure-JVM test of `applySnapshot` against a fake cache. |

### Modified files

| Path | Change |
|---|---|
| `mobile/.../data/SettingsRepository.kt` | Add `behindPaceCheckHour: Flow<Int>` + `setBehindPaceCheckHour(hour: Int)` with `validateHour`. |
| `mobile/.../data/SettingsDefaults.kt` | Trim to only `QUIET_HOURS_START` and `QUIET_HOURS_END`. Other 6 constants move to `:shared/sync/SettingsDefaults` (with a new `BEHIND_PACE_CHECK_HOUR` added there). Update `SettingsRepository`, `SettingsViewModel`, and `SettingsScreen` imports to source the shared defaults from `:shared`. |
| `mobile/.../ui/settings/SettingsScreen.kt` | Add 0..23 slider for `behindPaceCheckHour`. |
| `mobile/.../ui/settings/SettingsViewModel.kt` | Expose `behindPaceCheckHour` + setter. |
| `mobile/.../MeatsackMobileApp.kt` | Construct `PhoneSettingsSyncer`; call `.start(applicationScope)` in `onCreate`. |
| `mobile/.../MainActivity.kt` | In `onResume`, `lifecycleScope.launch { syncer.syncNow() }`. |
| `wear/.../settings/WatchSettingsCache.kt` | Add `inactivityThreshold` field. Replace hardcoded literal defaults with `SettingsDefaults` references. |
| `wear/.../escalation/EscalationManager.kt` | Add constructor parameter `thresholdProvider: () -> Int = { SettingsDefaults.INACTIVITY_THRESHOLD_MIN }`. Replace `EscalationLevel.INACTIVITY_THRESHOLD_MINUTES_DEFAULT` reads with `thresholdProvider()`. |
| `wear/.../MeatsackWearService.kt` | Pass `{ watchSettingsCache.inactivityThreshold }` into `EscalationManager`. |
| `wear/src/main/AndroidManifest.xml` | Register `WatchSettingsReceiver` with `<intent-filter>` matching `SettingsKeys.PATH`. |
| `wear/src/test/.../escalation/EscalationManagerTest.kt` | Pass `{ 30 }` (or test-specific value) into the new constructor parameter. |

## Data flow

### Steady state — user changes a slider

1. User drags `Daily step goal` from 10000 → 12000 in `SettingsScreen`.
2. `SettingsViewModel.setDailyStepGoal(12000)` → `SettingsRepository.setDailyStepGoal(12000)` writes to DataStore.
3. The `dailyStepGoal: Flow<Int>` emits 12000.
4. `PhoneSettingsSyncer`'s `combine(...)` re-emits a `SettingsSnapshot`. `debounce(500.ms)` waits for quiescence (so slider drags collapse).
5. `distinctUntilChanged()` passes through (different from prior). `writeSnapshot(snap)` builds the `PutDataMapRequest(SettingsKeys.PATH)` with 7 typed keys via `snap.toDataMap(dm)`, calls `setUrgent()`, `await()`.
6. Wear Data Layer delivers to watch.
7. `WatchSettingsReceiver.onDataChanged` fires → `SettingsSnapshot.fromDataMap(dm)` → `applySnapshot(snap, cache)` writes each field.
8. Next time `EscalationManager.thresholdProvider()` or `BehindPaceWorker` reads via `cache`, it returns the new value.

### Phone app launch — on-resume snapshot

1. User opens `MainActivity`. `onResume` launches `syncer.syncNow()`.
2. `syncNow()` reads `.first()` of each Flow, builds a snapshot, calls `writeSnapshot`.
3. DataClient dedupes by content: if nothing changed since last sync, the watch sees no `onDataChanged` event. Free belt-and-suspenders for any change that landed while the phone process was killed.

### Watch boots before any sync ever

1. `MeatsackWearService` starts. `WatchSettingsCache(context)` instantiates.
2. `prefs.getInt(KEY_DAILY_STEP_GOAL, SettingsDefaults.DAILY_STEP_GOAL)` returns the shared default. Watch behavior is unchanged from today — defaults rule until first sync.
3. As soon as the phone runs `MainActivity.onResume` (or auto-pushes after any edit), the cache is populated.

## Error handling

| Scenario | Handling |
|---|---|
| `SettingsRepository` Flow throws (DataStore corruption) | Phone syncer: `.catch { e -> Log.e(TAG, "settings flow failure", e); emit(SettingsSnapshot.defaults) }` on the combined flow — don't kill the collector. |
| `DataClient.putDataItem` fails / watch disconnected | `try/catch (CancellationException) { throw }` + `catch (Exception) { Log.e }`. Mirror `PhoneSyncSender.kt:62-67`. DataClient queues locally and delivers on reconnect. |
| Collector pipeline dies after exception | Bounded `.retryWhen { cause, attempt -> attempt < 3 && delay((1 shl attempt).seconds).let { true } }` — three attempts with exponential backoff, then leave a sticky log. No infinite restart (corruption should surface, not be silently masked). |
| Receiver throws during `onDataChanged` | Same try/catch shape as `WatchSyncReceiver.kt:53-59`. |
| Missing key on watch (forward-compat: phone older than watch) | `dm.getInt(KEY, SettingsDefaults.X)` returns shared default. No crash. |
| Unknown extra keys on watch (backward-compat: phone newer than watch) | Ignored by `dm.getInt` — only known keys are read. Safe. |
| Out-of-range hour on the wire | `SettingsSnapshot.fromDataMap` applies `coerceIn(0, 23)` to hour fields. Phone's `validateHour` should already prevent this; defense in depth (also partially addresses issue #25). |
| Watch boots before any sync ever | Already handled today; behavior unchanged after the shared-defaults move. |
| Validation failure on phone (e.g., `setBehindPaceCheckHour(99)`) | Existing setters throw `IllegalArgumentException`; ViewModel currently swallows. Out of scope (tracked in #21). |

Deliberately *not* added: DataItem quota throttling, network failures, "watch not paired" detection. DataClient handles all of these silently and asynchronously. Adding code that suppresses these exceptions invites the same silent-failure anti-pattern issue #24 is fighting.

## Testing strategy

| Surface | Test type | What it asserts |
|---|---|---|
| `SettingsSnapshot.toDataMap` / `fromDataMap` | Pure-JVM if DataMap permits; else Robolectric in `:shared/src/test`. | Round-trip equality; missing keys → defaults; out-of-range hours clamped via `coerceIn(0..23)`; assertions that defaults match `SettingsDefaults` (catches drift). |
| `PhoneSettingsSyncer.syncNow` | Unit + fake DataClient (constructor-injected) | Builds `PutDataMapRequest` with correct path + 7 keys + correct values; rethrows `CancellationException`; logs+swallows other exceptions. |
| `PhoneSettingsSyncer.start` debouncing | Unit + `kotlinx-coroutines-test` + Turbine | 3 rapid Flow emissions within 500ms → 1 `writeSnapshot` call; identical consecutive snapshots → 1 call only (distinctUntilChanged); `.retryWhen` restarts after thrown exception up to 3 times then gives up. |
| Receiver apply logic | Pure-JVM | Extract `internal fun applySnapshot(snap, sink)`. `WatchSettingsCache` implements a small `SettingsSink` interface (or test uses a Map-backed fake). Verify every field gets written. |
| `WatchSettingsCache.inactivityThreshold` | Robolectric for SharedPreferences | Default returns 30 (from `SettingsDefaults`); setter persists. |
| `EscalationManager` post-rewire | Unit | Constructor takes `thresholdProvider: () -> Int`. Production passes `{ cache.inactivityThreshold }`; tests pass `{ 30 }`. Update existing test fixtures. |
| `SettingsRepository.behindPaceCheckHour` | Existing test infra | New Flow emits default 12; setter throws on invalid hour; setter persists valid value. |
| End-to-end on paired emulators | Manual | Documented in spec — change phone slider, observe watch logcat for `WatchSettingsReceiver` apply log within ~2 seconds. |

**DataMap unit-testability:** historical note — `com.google.android.gms.wearable.DataMap` is part of GMS but is a value class with no Android-platform dependencies in modern Play Services releases. The implementation plan should record "verify pure-JVM at write-time; if it requires Android classes, add Robolectric to `:shared/src/test`."

**Why no test for `onResume → syncNow` wiring:** that call is one line in `MainActivity.onResume`, and Activity lifecycle tests are heavyweight. The integration is verified by the manual end-to-end smoke; spend test budget where the logic lives, not where it's plumbed.

## Manual end-to-end checklist

To verify on paired emulators (after `./gradlew :mobile:installDebug` + `./gradlew :wear:installDebug`):

1. Grant `ACTIVITY_RECOGNITION` on the watch (`adb -s emulator-5556 shell pm grant com.meatsack.motivator android.permission.ACTIVITY_RECOGNITION`).
2. Launch phone app. Confirm logcat shows `PhoneSettingsSyncer: syncNow OK (...)` shortly after.
3. Open Settings tab on phone. Drag `Daily step goal` slider to a new value.
4. Within ~2 seconds, watch logcat should show `WatchSettingsReceiver: applied snapshot ...` with the new value.
5. Open the watch app (or trigger BehindPaceWorker manually via `adb shell` for a fast check) and verify the new goal is in effect.
6. Force-stop the phone app. Edit a watch-relevant setting via DataStore (or skip — covered by step 3). Relaunch — `onResume` should fire one `syncNow` even if nothing changed (DataClient dedupes, so the watch shouldn't see an event).
7. Pull the phone-watch Bluetooth pairing offline, change a setting, restore — DataClient should deliver the queued DataItem.

## Open follow-ups (out of scope for this PR)

- **Issue #21** — surface setter validation exceptions to UI.
- **Issue #24** — harden worker bodies against silent DAO/notifier exceptions.
- **Issue #25** — `WatchSettingsCache.coerceIn(0..23)` defense at read time (partially covered here by `fromDataMap`'s coerce, but the cache itself still trusts its stored values).
- **New issue (file separately):** decide whether `quietHoursStart` / `quietHoursEnd` should reach the watch and what they should mean there (suppress insults? shape AI prompt? both?).
- **Issue #27** — `TriggerScheduler.millisUntilNextHour` companion + tests.

## Spec acceptance criteria

- Phone slider edits propagate to watch within ~2 seconds when paired.
- Watch boot without any prior sync still works (defaults).
- No new behavior regressions in `EscalationManager` after the threshold-provider rewire.
- `./gradlew build` + `:wear:testDebugUnitTest` + `:shared:testDebugUnitTest` + `:mobile:testDebugUnitTest` all pass.
- New tests cover `SettingsSnapshot` round-trip, `PhoneSettingsSyncer` debounce/dedup, and `applySnapshot` correctness.
