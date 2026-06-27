# User-Configurable Inactivity Step Threshold Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the user set, from the phone app, how many steps count as "moving" for inactivity detection, with the measurement window unified to the existing inactivity-threshold setting and the step counter fixed to count real steps.

**Architecture:** A new synced integer setting `movementStepThreshold` travels phone → watch over the existing `/settings` DataItem channel (Task 1). The Android-free counting/timing logic is extracted from `HealthTracker` into a new `MovementDetector` class so it is unit-testable on the plain JVM; `HealthTracker` delegates to it and feeds it the two settings via provider lambdas (Task 2, mirroring how `EscalationManager` already takes `thresholdProvider`). The phone UI gains a slider (Task 3).

**Tech Stack:** Kotlin, Android (Compose, DataStore), Wear Data Layer (`DataMap`/`DataItem`), Health Services (`STEPS_DAILY`), JUnit4, kotlinx-coroutines-test.

## Global Constraints

- Do NOT commit to `main`. Work is on branch `feature/configurable-step-threshold` (already created). PR into `main`.
- Pre-commit hook runs `./gradlew spotlessCheck` (ktlint) + unit tests. Every commit must pass. Run `./gradlew spotlessApply && git add -u` to auto-fix formatting before committing.
- `JAVA_HOME` must point at Android Studio's JDK (`/c/Program Files/Android/Android Studio/jbr`).
- Module/JVM target is Java 11 (`jvmTarget = "11"`).
- Setting identity is fixed: Kotlin name `movementStepThreshold`, wire/pref key `"movement_step_threshold"`, default `50`.
- Phone UI slider range is `10..500` in increments of 10 (`steps = 48`).
- `fromDataMap` coerces the value `>= 1`; the phone setter rejects `<= 0` via `validatePositive`.
- Unified-window rule: the measurement window equals the synced `inactivityThreshold` (no separate window setting). `EscalationLevel.MOVEMENT_RESET_STEPS` and `EscalationLevel.MOVEMENT_RESET_WINDOW_MINUTES` are deleted.
- Behavior is otherwise unchanged: the tumbling-window model and the existing `hasSignificantMovement()`/idle-clock mechanics are preserved (see spec Non-Goals).

---

### Task 1: Sync the new setting end-to-end (plumbing, no behavior change)

Adds `movementStepThreshold` to the wire shape and both sync ends so the value travels phone → watch and is stored in `WatchSettingsCache`. No consumer yet — `HealthTracker` still uses the old constant, which is removed in Task 2. Because `SettingsSnapshot` is a default-free data class, every construction site is updated in this task so all three modules compile and all unit tests pass.

**Files:**
- Modify: `shared/src/main/java/com/meatsack/shared/sync/SettingsDefaults.kt`
- Modify: `shared/src/main/java/com/meatsack/shared/sync/SettingsKeys.kt`
- Modify: `shared/src/main/java/com/meatsack/shared/sync/SettingsSnapshot.kt`
- Test: `shared/src/test/java/com/meatsack/shared/sync/SettingsSnapshotTest.kt`
- Modify: `mobile/src/main/java/com/meatsack/motivator/mobile/data/SettingsRepository.kt`
- Modify: `mobile/src/main/java/com/meatsack/motivator/mobile/sync/PhoneSettingsSyncer.kt` (the `RepositorySettingsSource` class)
- Modify: `wear/src/main/java/com/meatsack/motivator/settings/WatchSettingsCache.kt`
- Modify: `wear/src/main/java/com/meatsack/motivator/sync/WatchSettingsReceiver.kt`
- Test: `wear/src/test/java/com/meatsack/motivator/sync/WatchSettingsReceiverTest.kt`

**Interfaces:**
- Produces:
  - `SettingsDefaults.MOVEMENT_STEP_THRESHOLD: Int = 50`
  - `SettingsKeys.KEY_MOVEMENT_STEP_THRESHOLD: String = "movement_step_threshold"`
  - `SettingsSnapshot.movementStepThreshold: Int` (new field, round-trips through `toDataMap`/`fromDataMap`, coerced `>= 1`)
  - `SettingsRepository.movementStepThreshold: Flow<Int>` and `suspend fun setMovementStepThreshold(steps: Int)`
  - `WatchSettingsCache.movementStepThreshold: Int` (read/write SharedPreferences property)
  - `WatchSettingsReceiver.ApplySink.setMovementStepThreshold(v: Int)`

- [ ] **Step 1: Update `SettingsSnapshotTest` to drive the new field (failing test)**

In `shared/src/test/java/com/meatsack/shared/sync/SettingsSnapshotTest.kt`:

Add one assertion at the end of `defaults_matchSettingsDefaults`:

```kotlin
        assertEquals(SettingsDefaults.MOVEMENT_STEP_THRESHOLD, snap.movementStepThreshold)
```

Add `movementStepThreshold = 75,` to the `SettingsSnapshot(...)` constructor in `toDataMap_fromDataMap_roundTrip` (place it after `contextAwareEnd = 16,`):

```kotlin
            contextAwareEnd = 16,
            movementStepThreshold = 75,
        )
```

Add a new test for the `>= 1` coercion:

```kotlin
    @Test
    fun fromDataMap_movementStepThresholdBelowOne_coercedToOne() {
        val dm = DataMap()
        dm.putInt(SettingsKeys.KEY_MOVEMENT_STEP_THRESHOLD, 0)
        val parsed = SettingsSnapshot.fromDataMap(dm)
        assertEquals(1, parsed.movementStepThreshold)
    }
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :shared:testDebugUnitTest --tests "com.meatsack.shared.sync.SettingsSnapshotTest"`
Expected: FAIL — `movementStepThreshold` is unresolved (and `MOVEMENT_STEP_THRESHOLD` / `KEY_MOVEMENT_STEP_THRESHOLD` unresolved).

- [ ] **Step 3: Add the shared default and key**

In `shared/src/main/java/com/meatsack/shared/sync/SettingsDefaults.kt`, add inside the object (after `INACTIVITY_THRESHOLD_MIN`):

```kotlin
    const val MOVEMENT_STEP_THRESHOLD = 50
```

In `shared/src/main/java/com/meatsack/shared/sync/SettingsKeys.kt`, add inside the object (after `KEY_INACTIVITY_THRESHOLD`):

```kotlin
    const val KEY_MOVEMENT_STEP_THRESHOLD = "movement_step_threshold"
```

- [ ] **Step 4: Add the field to `SettingsSnapshot`**

In `shared/src/main/java/com/meatsack/shared/sync/SettingsSnapshot.kt`:

Add the constructor field after `contextAwareEnd: Int,`:

```kotlin
    val contextAwareEnd: Int,
    val movementStepThreshold: Int,
) {
```

Add to `toDataMap` (after the `contextAwareEnd` line):

```kotlin
        dm.putInt(SettingsKeys.KEY_MOVEMENT_STEP_THRESHOLD, movementStepThreshold)
```

Add to the `defaults` companion value (after `contextAwareEnd = SettingsDefaults.CONTEXT_AWARE_END,`):

```kotlin
            movementStepThreshold = SettingsDefaults.MOVEMENT_STEP_THRESHOLD,
```

Add to `fromDataMap` (after the `contextAwareEnd = ...` line), coerced `>= 1`:

```kotlin
            movementStepThreshold = dm.getInt(
                SettingsKeys.KEY_MOVEMENT_STEP_THRESHOLD,
                defaults.movementStepThreshold,
            ).coerceAtLeast(1),
```

- [ ] **Step 5: Run the shared test to verify it passes**

Run: `./gradlew :shared:testDebugUnitTest --tests "com.meatsack.shared.sync.SettingsSnapshotTest"`
Expected: PASS (4 tests).

- [ ] **Step 6: Add the phone read flow + setter**

In `mobile/src/main/java/com/meatsack/motivator/mobile/data/SettingsRepository.kt`:

Add the key in the companion (after `INACTIVITY_THRESHOLD`):

```kotlin
        val MOVEMENT_STEP_THRESHOLD = intPreferencesKey("movement_step_threshold")
```

Add the flow (after the `inactivityThreshold` flow):

```kotlin
    val movementStepThreshold: Flow<Int> = context.dataStore.data.map {
        it[MOVEMENT_STEP_THRESHOLD] ?: SharedDefaults.MOVEMENT_STEP_THRESHOLD
    }
```

Add the setter (after `setInactivityThreshold`):

```kotlin
    suspend fun setMovementStepThreshold(steps: Int) {
        validatePositive("Movement step threshold", steps)
        context.dataStore.edit { it[MOVEMENT_STEP_THRESHOLD] = steps }
    }
```

- [ ] **Step 7: Wire the field into `RepositorySettingsSource`**

In `mobile/src/main/java/com/meatsack/motivator/mobile/sync/PhoneSettingsSyncer.kt`, in the `RepositorySettingsSource` class:

Add `repo.movementStepThreshold,` as the 11th flow in the `combine(...)` call (after `repo.contextAwareEnd,`), then add the field to the snapshot built in the transform (after `contextAwareEnd = values[9] as Int,`):

```kotlin
        repo.contextAwareEnd,
        repo.movementStepThreshold,
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
            movementStepThreshold = values[10] as Int,
        )
    }
```

In `current()`, add the field (after `contextAwareEnd = repo.contextAwareEnd.first(),`):

```kotlin
            movementStepThreshold = repo.movementStepThreshold.first(),
```

- [ ] **Step 8: Add the watch cache property**

In `wear/src/main/java/com/meatsack/motivator/settings/WatchSettingsCache.kt`:

Add the property (after the `inactivityThreshold` property):

```kotlin
    var movementStepThreshold: Int
        get() = prefs.getInt(KEY_MOVEMENT_STEP_THRESHOLD, SettingsDefaults.MOVEMENT_STEP_THRESHOLD)
        set(value) = prefs.edit().putInt(KEY_MOVEMENT_STEP_THRESHOLD, value).apply()
```

Add the key in the companion (after `KEY_INACTIVITY_THRESHOLD`):

```kotlin
        private const val KEY_MOVEMENT_STEP_THRESHOLD = "movement_step_threshold"
```

- [ ] **Step 9: Update `WatchSettingsReceiverTest` to expect the new field (failing test)**

In `wear/src/test/java/com/meatsack/motivator/sync/WatchSettingsReceiverTest.kt`:

In `FakeApplySink`, add a backing field (after `recordedContextAwareEnd`):

```kotlin
        var recordedMovementStepThreshold: Int = -1
```

and the override method (after `setContextAwareEnd`):

```kotlin
        override fun setMovementStepThreshold(v: Int) {
            recordedMovementStepThreshold = v
        }
```

In `applySnapshot_writesEveryField`, add `movementStepThreshold = 75,` to the `SettingsSnapshot(...)` constructor (after `contextAwareEnd = 16,`) and an assertion at the end:

```kotlin
            contextAwareEnd = 16,
            movementStepThreshold = 75,
        )
        WatchSettingsReceiver.applySnapshot(snap, sink)
```

```kotlin
        assertEquals(75, sink.recordedMovementStepThreshold)
```

- [ ] **Step 10: Run the wear receiver test to verify it fails**

Run: `./gradlew :wear:testDebugUnitTest --tests "com.meatsack.motivator.sync.WatchSettingsReceiverTest"`
Expected: FAIL — `ApplySink` has no `setMovementStepThreshold` and `SettingsSnapshot` has no `movementStepThreshold` arg in this module's view (compile error) once the override is added but the interface/applier are not.

- [ ] **Step 11: Apply the field in `WatchSettingsReceiver`**

In `wear/src/main/java/com/meatsack/motivator/sync/WatchSettingsReceiver.kt`:

Add to `applySnapshot` (after `sink.setContextAwareEnd(snap.contextAwareEnd)`):

```kotlin
            sink.setMovementStepThreshold(snap.movementStepThreshold)
```

Add to the `ApplySink` interface (after `fun setContextAwareEnd(v: Int)`):

```kotlin
        fun setMovementStepThreshold(v: Int)
```

Add to `cacheAdapter` (after the `setContextAwareEnd` override):

```kotlin
        override fun setMovementStepThreshold(v: Int) {
            cache.movementStepThreshold = v
        }
```

- [ ] **Step 12: Run the wear receiver test to verify it passes**

Run: `./gradlew :wear:testDebugUnitTest --tests "com.meatsack.motivator.sync.WatchSettingsReceiverTest"`
Expected: PASS.

- [ ] **Step 13: Compile all modules + run the full shared/wear suites**

Run: `./gradlew :shared:compileDebugKotlin :wear:compileDebugKotlin :mobile:compileDebugKotlin :shared:testDebugUnitTest :wear:testDebugUnitTest`
Expected: BUILD SUCCESSFUL. (Confirms `PhoneSettingsSyncerTest`, which builds snapshots via `SettingsSnapshot.defaults.copy(...)`, still compiles — `defaults` now supplies the new field.)

- [ ] **Step 14: Format + commit**

```bash
./gradlew spotlessApply && git add -u
git add shared/src/main/java/com/meatsack/shared/sync/SettingsDefaults.kt \
  shared/src/main/java/com/meatsack/shared/sync/SettingsKeys.kt \
  shared/src/main/java/com/meatsack/shared/sync/SettingsSnapshot.kt \
  shared/src/test/java/com/meatsack/shared/sync/SettingsSnapshotTest.kt \
  mobile/src/main/java/com/meatsack/motivator/mobile/data/SettingsRepository.kt \
  mobile/src/main/java/com/meatsack/motivator/mobile/sync/PhoneSettingsSyncer.kt \
  wear/src/main/java/com/meatsack/motivator/settings/WatchSettingsCache.kt \
  wear/src/main/java/com/meatsack/motivator/sync/WatchSettingsReceiver.kt \
  wear/src/test/java/com/meatsack/motivator/sync/WatchSettingsReceiverTest.kt
git commit -m "feat(settings): sync movementStepThreshold phone->watch"
```

---

### Task 2: Consume the setting in movement detection + fix step counting

Extracts the Android-free counting/timing logic into `MovementDetector` (JVM-testable, injectable clock), makes `HealthTracker` delegate to it and feed it the two settings via providers, fixes the counter to accumulate real step deltas, deletes the two now-unused `EscalationLevel` constants, and wires the providers in the service.

**Files:**
- Create: `wear/src/main/java/com/meatsack/motivator/health/MovementDetector.kt`
- Test: `wear/src/test/java/com/meatsack/motivator/health/MovementDetectorTest.kt`
- Modify (rewrite): `wear/src/main/java/com/meatsack/motivator/health/HealthTracker.kt`
- Modify: `shared/src/main/java/com/meatsack/shared/constants/EscalationLevel.kt` (delete two constants)
- Modify: `wear/src/main/java/com/meatsack/motivator/MeatsackWearService.kt:53-56` (wire providers)

**Interfaces:**
- Consumes: `WatchSettingsCache.movementStepThreshold` and `WatchSettingsCache.inactivityThreshold` (Task 1 / existing).
- Produces:
  - `MovementDetector(stepThresholdProvider: () -> Int, windowMinutesProvider: () -> Int, now: () -> Long = System::currentTimeMillis)` with `fun onStepTotal(currentTotal: Int)`, `fun minutesSinceLastMovement(): Int`, `fun hasSignificantMovement(): Boolean`.
  - `HealthTracker(context: Context, stepThresholdProvider: () -> Int, windowMinutesProvider: () -> Int)` — public API `getMinutesSinceLastMovement(): Int` and `hasSignificantMovement(): Boolean` unchanged.

- [ ] **Step 1: Write the failing `MovementDetectorTest`**

Create `wear/src/test/java/com/meatsack/motivator/health/MovementDetectorTest.kt`:

```kotlin
package com.meatsack.motivator.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class MovementDetectorTest {

    private var clock = 0L
    private var stepThreshold = 50
    private var windowMinutes = 30

    private fun newDetector() = MovementDetector(
        stepThresholdProvider = { stepThreshold },
        windowMinutesProvider = { windowMinutes },
        now = { clock },
    )

    @Test
    fun firstReading_setsBaseline_doesNotCountWholeDailyTotal() {
        clock = 0
        val d = newDetector()
        d.onStepTotal(5000) // first reading: huge cumulative total must NOT count as movement
        assertFalse(d.hasSignificantMovement())
        clock = 30 * 60_000L // 30 min later, no further steps
        assertEquals(30, d.minutesSinceLastMovement())
    }

    @Test
    fun deltasAccumulateAcrossCallbacks_crossingThreshold_resetsIdleClock() {
        clock = 0
        val d = newDetector()
        d.onStepTotal(1000) // baseline
        clock = 60_000L
        d.onStepTotal(1040) // +40, below 50
        assertFalse(d.hasSignificantMovement())
        clock = 120_000L
        d.onStepTotal(1055) // +15 => window total 55 >= 50 => movement, idle clock resets to now
        assertEquals(0, d.minutesSinceLastMovement())
    }

    @Test
    fun dailyRollover_doesNotProduceNegativeOrHugeDelta() {
        clock = 0
        val d = newDetector()
        d.onStepTotal(9000) // baseline late in the day
        clock = 60_000L
        d.onStepTotal(20) // midnight rollover: cumulative dropped => delta treated as 0
        clock = 120_000L
        d.onStepTotal(30) // +10 only
        assertFalse(d.hasSignificantMovement())
        assertEquals(2, d.minutesSinceLastMovement()) // idle clock NOT reset by the rollover
    }

    @Test
    fun windowTumble_clearsPartialCount() {
        clock = 0
        val d = newDetector()
        d.onStepTotal(1000) // baseline, window opens at t=0
        clock = 60_000L
        d.onStepTotal(1040) // +40 in window 1
        clock = 31 * 60_000L // > 30-min window since t=0 => tumble, partial 40 discarded
        d.onStepTotal(1075) // +35 in a fresh window => 35, still below 50
        assertFalse(d.hasSignificantMovement())
        assertEquals(31, d.minutesSinceLastMovement()) // never crossed => idle clock never reset
    }

    @Test
    fun stepThresholdProviderIsReadLive() {
        clock = 0
        stepThreshold = 100
        val d = newDetector()
        d.onStepTotal(1000) // baseline
        clock = 1_000L
        d.onStepTotal(1060) // +60, below 100
        assertFalse(d.hasSignificantMovement())
        stepThreshold = 50 // lower the bar mid-stream
        clock = 2_000L
        d.onStepTotal(1070) // +10 => window total 70 >= 50 now => movement
        assertEquals(0, d.minutesSinceLastMovement())
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :wear:testDebugUnitTest --tests "com.meatsack.motivator.health.MovementDetectorTest"`
Expected: FAIL — `MovementDetector` is unresolved.

- [ ] **Step 3: Create `MovementDetector`**

Create `wear/src/main/java/com/meatsack/motivator/health/MovementDetector.kt`:

```kotlin
package com.meatsack.motivator.health

/**
 * Android-free movement / idle detection, extracted from [HealthTracker] so it can be
 * unit-tested on the plain JVM. It consumes the cumulative STEPS_DAILY total via
 * [onStepTotal] and reads the user's step threshold and window length through provider
 * lambdas, so a settings change synced from the phone takes effect on the next step
 * update with no re-wiring.
 *
 * The window is *tumbling* (a fixed box reset at each boundary), matching the prior
 * behavior — steps in an expired window are discarded rather than rolled forward.
 *
 * [now] is injectable so tests can drive time deterministically; production uses the
 * system clock.
 */
class MovementDetector(
    private val stepThresholdProvider: () -> Int,
    private val windowMinutesProvider: () -> Int,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private var lastMovementTimestamp: Long = now()
    private var windowStartTimestamp: Long = now()
    private var stepsInCurrentWindow: Int = 0

    // Baseline for turning the cumulative STEPS_DAILY total into per-update deltas.
    // <0 means "no reading yet"; it is re-baselined (delta 0) on the first reading and
    // on a daily rollover (when the cumulative count drops at midnight).
    private var lastStepTotal: Int = -1

    @Synchronized
    fun onStepTotal(currentTotal: Int) {
        val nowMs = now()
        val windowMs = windowMinutesProvider().toLong() * 60_000L

        val delta = when {
            lastStepTotal < 0 -> 0 // first reading: set baseline, count nothing
            currentTotal < lastStepTotal -> 0 // midnight rollover: re-baseline, count nothing
            else -> currentTotal - lastStepTotal
        }
        lastStepTotal = currentTotal

        if (nowMs - windowStartTimestamp > windowMs) {
            stepsInCurrentWindow = 0
            windowStartTimestamp = nowMs
        }
        stepsInCurrentWindow += delta

        if (stepsInCurrentWindow >= stepThresholdProvider()) {
            lastMovementTimestamp = nowMs
            stepsInCurrentWindow = 0
            windowStartTimestamp = nowMs
        }
    }

    @Synchronized
    fun minutesSinceLastMovement(): Int =
        ((now() - lastMovementTimestamp) / 60_000L).toInt()

    @Synchronized
    fun hasSignificantMovement(): Boolean =
        stepsInCurrentWindow >= stepThresholdProvider()
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :wear:testDebugUnitTest --tests "com.meatsack.motivator.health.MovementDetectorTest"`
Expected: PASS (5 tests).

- [ ] **Step 5: Rewrite `HealthTracker` to delegate to `MovementDetector`**

Replace the entire contents of `wear/src/main/java/com/meatsack/motivator/health/HealthTracker.kt` with:

```kotlin
package com.meatsack.motivator.health

import android.content.Context
import android.util.Log
import androidx.health.services.client.HealthServices
import androidx.health.services.client.PassiveListenerCallback
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.PassiveListenerConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class HealthTracker(
    private val context: Context,
    stepThresholdProvider: () -> Int,
    windowMinutesProvider: () -> Int,
) {
    private val client = HealthServices.getClient(context).passiveMonitoringClient

    // Android-free movement/idle logic; fed the user's step threshold and the
    // (unified) window = inactivity threshold via the provider lambdas above.
    private val detector = MovementDetector(stepThresholdProvider, windowMinutesProvider)

    private val _totalStepsToday = MutableStateFlow(0)
    val totalStepsToday: StateFlow<Int> = _totalStepsToday.asStateFlow()

    private val _floorsToday = MutableStateFlow(0)
    val floorsToday: StateFlow<Int> = _floorsToday.asStateFlow()

    private val _caloriesToday = MutableStateFlow(0)
    val caloriesToday: StateFlow<Int> = _caloriesToday.asStateFlow()

    // Heart rate intentionally not subscribed in v2: Wear OS 5+ Health Services
    // requires the Health Connect permission android.permission.health.READ_HEART_RATE
    // even in PASSIVE mode (the v2 plan's assumption that PASSIVE bypasses this on
    // API 33+ turned out to be wrong on real Wear OS 5 emulators). Re-add in v3
    // alongside proper Health Connect integration.

    companion object {
        private const val TAG = "HealthTracker"

        /** SharedPreferences file for the worker hand-off (steps + freshness). */
        const val WATCH_HEALTH_PREFS = "watch_health"
        const val KEY_STEPS_TODAY = "steps_today"
        const val KEY_LAST_UPDATED = "last_updated"

        /**
         * If a worker reads step data older than this, it should treat the
         * SharedPreferences mirror as untrustworthy (e.g., post-process-death,
         * post-day-rollover before the first datapoint arrives).
         */
        const val STALE_THRESHOLD_MS = 60 * 60 * 1000L // 1 hour
    }

    private val callback = object : PassiveListenerCallback {
        override fun onNewDataPointsReceived(dataPoints: DataPointContainer) {
            try {
                dataPoints.getData(DataType.STEPS_DAILY).lastOrNull()?.let {
                    val count = it.value.toInt()
                    _totalStepsToday.value = count
                    detector.onStepTotal(count)
                    // Hand-off for WorkManager workers (BehindPaceWorker, EndOfDayWorker)
                    // that don't have direct access to HealthServices. The timestamp
                    // is what lets workers reject stale or never-written values
                    // (otherwise default-0 reads would falsely flag the user as
                    // behind pace on first launch / after process death).
                    context.getSharedPreferences(WATCH_HEALTH_PREFS, Context.MODE_PRIVATE)
                        .edit()
                        .putInt(KEY_STEPS_TODAY, count)
                        .putLong(KEY_LAST_UPDATED, System.currentTimeMillis())
                        .apply()
                }
                dataPoints.getData(DataType.FLOORS_DAILY).lastOrNull()?.let {
                    _floorsToday.value = it.value.toInt()
                }
                dataPoints.getData(DataType.CALORIES_DAILY).lastOrNull()?.let {
                    _caloriesToday.value = it.value.toInt()
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to process health data point", t)
            }
        }
    }

    fun startTracking() {
        val config = PassiveListenerConfig.builder()
            .setDataTypes(
                setOf(
                    DataType.STEPS_DAILY,
                    DataType.FLOORS_DAILY,
                    DataType.CALORIES_DAILY,
                ),
            )
            .build()
        // setPassiveListenerCallback is fire-and-forget (returns void) on
        // health-services-client 1.0.0-rc02; only setPassiveListenerServiceAsync
        // returns a ListenableFuture. runCatching surfaces any synchronous
        // registration error (e.g., Health Services unavailable on device).
        runCatching { client.setPassiveListenerCallback(config, callback) }
            .onSuccess { Log.d(TAG, "Health tracking started (steps, floors, calories)") }
            .onFailure { Log.e(TAG, "Failed to register passive listener", it) }
    }

    fun stopTracking() {
        val future = client.clearPassiveListenerCallbackAsync()
        future.addListener(
            {
                try {
                    future.get()
                    Log.d(TAG, "Health tracking stopped")
                } catch (ie: InterruptedException) {
                    // Restore interrupt flag so callers up the stack can observe it.
                    Thread.currentThread().interrupt()
                    Log.e(TAG, "Interrupted while clearing passive listener callback", ie)
                } catch (ce: java.util.concurrent.CancellationException) {
                    Log.w(TAG, "Clear passive listener callback was cancelled", ce)
                } catch (ee: java.util.concurrent.ExecutionException) {
                    Log.e(TAG, "Failed to clear passive listener callback", ee.cause ?: ee)
                }
            },
            // Direct executor: listener does only cheap log calls. future.get() returns
            // immediately because the listener contract guarantees completion. Do not
            // add blocking work here without switching to a real executor.
            Runnable::run,
        )
    }

    fun getMinutesSinceLastMovement(): Int = detector.minutesSinceLastMovement()

    fun hasSignificantMovement(): Boolean = detector.hasSignificantMovement()
}
```

- [ ] **Step 6: Delete the two unused constants from `EscalationLevel`**

In `shared/src/main/java/com/meatsack/shared/constants/EscalationLevel.kt`, delete these two lines from the companion object:

```kotlin
        const val MOVEMENT_RESET_STEPS = 50
        const val MOVEMENT_RESET_WINDOW_MINUTES = 5
```

Leave `INACTIVITY_THRESHOLD_MINUTES_DEFAULT` and `ESCALATION_INTERVAL_MINUTES` untouched.

- [ ] **Step 7: Wire the providers in `MeatsackWearService`**

In `wear/src/main/java/com/meatsack/motivator/MeatsackWearService.kt`, replace lines 53-56:

```kotlin
        val db = AppDatabase.getDatabase(applicationContext)
        healthTracker = HealthTracker(applicationContext)
        settings = WatchSettingsCache(applicationContext)
        escalationManager = EscalationManager(thresholdProvider = { settings.inactivityThreshold })
```

with (note `settings` is now created before `healthTracker` so the providers can reference it):

```kotlin
        val db = AppDatabase.getDatabase(applicationContext)
        settings = WatchSettingsCache(applicationContext)
        healthTracker = HealthTracker(
            applicationContext,
            stepThresholdProvider = { settings.movementStepThreshold },
            windowMinutesProvider = { settings.inactivityThreshold },
        )
        escalationManager = EscalationManager(thresholdProvider = { settings.inactivityThreshold })
```

- [ ] **Step 8: Compile + run the full shared/wear suites**

Run: `./gradlew :shared:compileDebugKotlin :wear:compileDebugKotlin :mobile:compileDebugKotlin :shared:testDebugUnitTest :wear:testDebugUnitTest`
Expected: BUILD SUCCESSFUL — including `MovementDetectorTest`, `EscalationManagerTest`, and the Task 1 settings tests. A compile error about `MOVEMENT_RESET_STEPS`/`MOVEMENT_RESET_WINDOW_MINUTES` means a reference was missed (only `HealthTracker` used them; it was rewritten).

- [ ] **Step 9: Format + commit**

```bash
./gradlew spotlessApply && git add -u
git add wear/src/main/java/com/meatsack/motivator/health/MovementDetector.kt \
  wear/src/test/java/com/meatsack/motivator/health/MovementDetectorTest.kt \
  wear/src/main/java/com/meatsack/motivator/health/HealthTracker.kt \
  shared/src/main/java/com/meatsack/shared/constants/EscalationLevel.kt \
  wear/src/main/java/com/meatsack/motivator/MeatsackWearService.kt
git commit -m "feat(wear): drive movement detection from movementStepThreshold; count real steps"
```

---

### Task 3: Phone UI — slider to set the step threshold

Adds the ViewModel state/update and a `Slider` in `SettingsScreen`, completing the user-facing path. The repository setter already exists (Task 1).

**Files:**
- Modify: `mobile/src/main/java/com/meatsack/motivator/mobile/ui/settings/SettingsViewModel.kt`
- Modify: `mobile/src/main/java/com/meatsack/motivator/mobile/ui/settings/SettingsScreen.kt`

**Interfaces:**
- Consumes: `SettingsRepository.movementStepThreshold` / `setMovementStepThreshold` (Task 1), `SettingsDefaults.MOVEMENT_STEP_THRESHOLD` (Task 1).
- Produces: `SettingsViewModel.movementStepThreshold: StateFlow<Int>` and `fun updateMovementStepThreshold(steps: Int)`.

- [ ] **Step 1: Add ViewModel state + update**

In `mobile/src/main/java/com/meatsack/motivator/mobile/ui/settings/SettingsViewModel.kt`:

Add the state (after the `inactivityThreshold` `stateIn` block):

```kotlin
    val movementStepThreshold = repo.movementStepThreshold.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(),
        SharedDefaults.MOVEMENT_STEP_THRESHOLD,
    )
```

Add the update function (after `updateInactivityThreshold`):

```kotlin
    fun updateMovementStepThreshold(steps: Int) =
        viewModelScope.launch { repo.setMovementStepThreshold(steps) }
```

- [ ] **Step 2: Add the slider to `SettingsScreen`**

In `mobile/src/main/java/com/meatsack/motivator/mobile/ui/settings/SettingsScreen.kt`:

Add the collected state near the other `by viewModel...collectAsState()` declarations at the top of `SettingsScreen` (after `val inactivityThreshold by ...`):

```kotlin
    val movementSteps by viewModel.movementStepThreshold.collectAsState()
```

Insert this block immediately after the Inactivity Threshold slider's trailing `Spacer(Modifier.height(16.dp))` (i.e., right before the `Text("Active hours: ...")` block):

```kotlin
        Text(
            "Movement threshold: $movementSteps steps",
            style = MaterialTheme.typography.titleMedium,
        )
        Slider(
            value = movementSteps.toFloat(),
            onValueChange = { viewModel.updateMovementStepThreshold(it.toInt()) },
            valueRange = 10f..500f,
            steps = 48, // 10..500 in increments of 10
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "Steps needed within your inactivity window to count as moving and reset the timer.",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(16.dp))
```

- [ ] **Step 3: Compile the mobile module**

Run: `./gradlew :mobile:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Build the phone APK and manually verify the UI**

Run: `./gradlew :mobile:assembleDebug`
Then install on the phone emulator and open Settings:

```bash
adb -s emulator-5556 install -r mobile/build/outputs/apk/debug/mobile-debug.apk
```

Expected: a "Movement threshold: 50 steps" slider appears directly below "Inactivity Threshold"; dragging it updates the number in increments of 10 within 10–500; the helper text reads "Steps needed within your inactivity window to count as moving and reset the timer." Tapping "Sync to Watch" (Library tab) sends the value (verifiable in Task 1's path; the watch stores it in `WatchSettingsCache`).

- [ ] **Step 5: Format + commit**

```bash
./gradlew spotlessApply && git add -u
git add mobile/src/main/java/com/meatsack/motivator/mobile/ui/settings/SettingsViewModel.kt \
  mobile/src/main/java/com/meatsack/motivator/mobile/ui/settings/SettingsScreen.kt
git commit -m "feat(mobile): add movement step threshold slider to Settings"
```

---

## Self-Review

**Spec coverage:**
- New synced setting `movementStepThreshold` (name/key/default/validation/coercion) → Task 1 Steps 3–4, 6 + Global Constraints. ✓
- Unified window = inactivity threshold → Task 2 Step 7 (`windowMinutesProvider = { settings.inactivityThreshold }`). ✓
- Count real steps (delta from cumulative, with first-reading + rollover guards) → Task 2 Step 3 (`MovementDetector.onStepTotal`) + tests Step 1. ✓
- Approach A provider lambdas, mirroring `EscalationManager` → Task 2 Steps 3, 5, 7. ✓
- Remove `MOVEMENT_RESET_STEPS` / `MOVEMENT_RESET_WINDOW_MINUTES` → Task 2 Step 6. ✓
- Full sync plumbing (shared/phone/watch + both ends) → Task 1. ✓
- Phone UI slider (10–500/10, below inactivity threshold, helper text) → Task 3 Step 2 + Global Constraints. ✓
- Tests: `MovementDetectorTest` (delta sum, first reading, rollover, tumble, live provider), `SettingsSnapshotTest` (new field round-trip + coercion), `WatchSettingsReceiverTest` (apply new field) → Tasks 1–2. ✓
- Tumbling-window behavior preserved (non-goal) → `MovementDetector` doc + unchanged window logic. ✓

**Refinement vs spec:** the spec named `HealthTrackerTest`; because `HealthTracker`'s constructor touches `HealthServices.getClient(context)` (not constructible in this repo's pure-JVM unit tests), the testable logic is extracted into `MovementDetector` and tested via `MovementDetectorTest`. Same coverage, JVM-only, no new test dependencies. Behavior and all spec decisions are unchanged.

**Placeholder scan:** No TBD/TODO/"handle edge cases"/"similar to Task N". Every code step shows complete code. ✓

**Type consistency:** `movementStepThreshold` (field/flow/cache/snapshot), `MOVEMENT_STEP_THRESHOLD` (default), `KEY_MOVEMENT_STEP_THRESHOLD` / `"movement_step_threshold"` (key), `setMovementStepThreshold`, `updateMovementStepThreshold`, `MovementDetector.onStepTotal`/`minutesSinceLastMovement`/`hasSignificantMovement`, and `HealthTracker(context, stepThresholdProvider, windowMinutesProvider)` are used identically across Tasks 1–3. ✓
