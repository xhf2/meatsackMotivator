# User-Configurable Inactivity Step Threshold — Design

**Date:** 2026-06-27
**Branch:** `feature/configurable-step-threshold`
**Status:** Approved (brainstorm complete; ready for implementation plan)

## Goal

Let the user set, from the phone app, **how many steps count as "moving"** for inactivity
detection — replacing the hardcoded `MOVEMENT_RESET_STEPS = 50`. The measurement window
(currently a hardcoded 5 minutes) is **unified with the existing inactivity-threshold
setting**, so the whole rule becomes a single intuitive sentence:

> "Take at least *X* steps within every *[inactivity-threshold]* minutes, or get nagged."

## Background: how inactivity detection works today

`HealthTracker` (wear) subscribes to `DataType.STEPS_DAILY` in passive mode. Each callback
delivers the **cumulative** daily step count (monotonically increasing, resets to 0 at
midnight). It maintains three pieces of state:

- `lastMovementTimestamp` — when the user last "proved" movement; the basis of the idle clock.
- `stepsInCurrentWindow` — steps counted in the current measurement window.
- `windowStartTimestamp` — when the current window opened.

It answers two questions for `MeatsackWearService`:

- `getMinutesSinceLastMovement()` → `(now - lastMovementTimestamp) / 60000` (the idle clock).
- `hasSignificantMovement()` → `stepsInCurrentWindow >= MOVEMENT_RESET_STEPS`.

The service polls every 60 s: if `hasSignificantMovement()` it resets escalation; otherwise it
asks `EscalationManager.shouldTrigger(minutesIdle)`, which fires once `minutesIdle` reaches the
synced `inactivityThreshold` (default 30 min) and then every `ESCALATION_INTERVAL_MINUTES`.

So today there are **two clocks**: a short 5-min movement window inside `HealthTracker`, and
the longer inactivity threshold inside `EscalationManager`.

### Two existing issues this change addresses

1. **The window uses a *tumbling* window** — a fixed box discarded at each boundary, not a
   rolling "last N minutes." (Kept as-is; see Non-Goals.)
2. **The step counter counts callbacks, not steps.** `trackMovement` does
   `stepsInCurrentWindow++` once per sensor callback and ignores `currentTotal`. So "50" really
   means "50 sensor updates." Invisible while it's a private constant; **wrong** once the UI
   labels it "X steps." This design fixes it to accumulate the real step delta.

## Decisions (from brainstorm)

- **Unified window:** the measurement window equals the existing `inactivityThreshold` setting
  (one new setting, not two). Accepted trade-off: movement detection becomes more lenient than
  today's 5-min burst — steps may be spread across the whole threshold window.
- **Count real steps:** fix `trackMovement` so the user-facing number means literal steps.
- **Wiring:** Approach A — `HealthTracker` takes provider lambdas (mirrors how
  `EscalationManager` already takes `thresholdProvider`). Settings changes take effect on the
  next callback with no restart.

## Architecture

A new synced integer setting flows phone → watch over the existing `/settings` DataItem
channel and is consumed by `HealthTracker`.

```
SettingsScreen (slider)
   └─ SettingsViewModel.updateMovementStepThreshold
        └─ SettingsRepository (DataStore)  ──(Sync to Watch)──>  /settings DataItem
                                                                      │
WatchSettingsReceiver ── applies ──> WatchSettingsCache.movementStepThreshold
                                                                      │
MeatsackWearService wires providers ──> HealthTracker.stepThresholdProvider / windowMinutesProvider
```

### Setting definition

| Property | Value |
|---|---|
| Kotlin name | `movementStepThreshold` |
| Wire key | `movement_step_threshold` |
| Default | `50` (preserves today's constant) |
| Validation | `validatePositive` (same as `inactivityThreshold`); `fromDataMap` coerces `>= 1` defense-in-depth |
| UI range | 10–500, increments of 10 |

`EscalationLevel.MOVEMENT_RESET_STEPS` is removed (replaced by the setting/default).
`EscalationLevel.MOVEMENT_RESET_WINDOW_MINUTES` is removed (window now derives from
`inactivityThreshold`).

## Components to change

**shared/**
- `SettingsDefaults`: add `MOVEMENT_STEP_THRESHOLD = 50`.
- `SettingsKeys`: add `KEY_MOVEMENT_STEP_THRESHOLD = "movement_step_threshold"`.
- `SettingsSnapshot`: add `movementStepThreshold` field + `toDataMap`/`fromDataMap` line
  (coerced `>= 1`).
- `EscalationLevel`: delete `MOVEMENT_RESET_STEPS` and `MOVEMENT_RESET_WINDOW_MINUTES`.

**mobile/**
- `SettingsRepository`: `MOVEMENT_STEP_THRESHOLD` key, `movementStepThreshold` flow,
  `setMovementStepThreshold()` (validatePositive).
- `SettingsViewModel`: `movementStepThreshold` state + `updateMovementStepThreshold()`.
- `SettingsScreen`: a `Slider` below the Inactivity Threshold slider, same style.
- The phone settings syncer (`PhoneSettingsSyncer` / equivalent): include the new field when
  building `SettingsSnapshot`.

**wear/**
- `WatchSettingsCache`: `var movementStepThreshold` (key + default fallback).
- `WatchSettingsReceiver`: apply the new field from the incoming snapshot.
- `HealthTracker`: take `stepThresholdProvider` and `windowMinutesProvider`; compute the window
  from `windowMinutesProvider()`; fix counting (below).
- `MeatsackWearService`: wire `stepThresholdProvider = { settings.movementStepThreshold }` and
  `windowMinutesProvider = { settings.inactivityThreshold }`.

## HealthTracker rework (behavioral core)

```kotlin
class HealthTracker(
    private val context: Context,
    private val stepThresholdProvider: () -> Int,
    private val windowMinutesProvider: () -> Int,
) {
    private var lastStepTotal: Int = -1   // baseline for delta; <0 = not yet seen

    private fun trackMovement(currentTotal: Int) {
        synchronized(this) {
            val now = System.currentTimeMillis()
            val windowMs = windowMinutesProvider().toLong() * 60_000L

            val delta = when {
                lastStepTotal < 0            -> 0  // first reading: set baseline only
                currentTotal < lastStepTotal -> 0  // midnight rollover: rebaseline only
                else                         -> currentTotal - lastStepTotal
            }
            lastStepTotal = currentTotal

            if (now - windowStartTimestamp > windowMs) {
                stepsInCurrentWindow = 0
                windowStartTimestamp = now
            }
            stepsInCurrentWindow += delta

            if (stepsInCurrentWindow >= stepThresholdProvider()) {
                lastMovementTimestamp = now
                stepsInCurrentWindow = 0
                windowStartTimestamp = now
            }
        }
    }
}
```

`hasSignificantMovement()` uses `stepThresholdProvider()`. The two delta guards exist because
the source is cumulative: without them, the first callback of the day would register the entire
day's total and midnight would register a large negative number.

### Worked example (threshold 30 min, X 50 steps)

- 08:00 — first callback, total 1200 → baseline, delta 0, idle clock starts.
- 08:00–08:25 — real movement sums to +60; at the crossing of 50, `lastMovementTimestamp` → now,
  idle clock resets, no nag.
- 08:25–08:55 — deltas trickle to +20, never reaching 50; at 08:55 the window tumbles
  (`stepsInCurrentWindow` → 0) but `lastMovementTimestamp` is still ~08:25, so
  `getMinutesSinceLastMovement()` reads 30 → `shouldTrigger` fires → nag.

## Error handling

- Malformed/missing wire value → `fromDataMap` falls back to default and coerces `>= 1`
  (consistent with the existing hour-field coercion).
- Phone-side setter rejects `<= 0` via `validatePositive`.
- Cumulative-source edge cases (first reading, midnight rollover) handled by the delta guards.
- `HealthTracker` stays decoupled from settings — it only calls lambdas, so it is unit-testable
  with fakes and unaffected by how/where settings are stored.

## Testing

- **`HealthTrackerTest` (new, JVM unit, fake providers):**
  - multi-callback deltas sum correctly to cross the threshold;
  - first reading sets baseline and counts nothing;
  - midnight rollover (`currentTotal < lastStepTotal`) counts nothing, no negative delta;
  - crossing the step threshold resets `lastMovementTimestamp` (idle clock);
  - window tumble clears `stepsInCurrentWindow`;
  - changing the provider's returned value changes behavior on the next callback (live update).
- **`SettingsSnapshotTest`:** extend round-trip and missing-key-default coverage for the new
  field.
- **`SettingsRepositoryTest`** (if present): `setMovementStepThreshold` rejects `<= 0`.
- `:shared:testDebugUnitTest` + `:wear:testDebugUnitTest` green; `spotlessCheck` clean
  (pre-commit hook enforces both).

## Non-Goals

- **Sliding window.** The tumbling window is retained; worst-case inactivity detection can lag
  up to one window past the threshold. Unchanged from today. A sliding window (timestamped step
  buffer) is out of scope for this feature.
- No change to escalation levels, intervals, active-hours gating, or other triggers.
- No change to the `/settings` channel mechanics beyond adding one field.

## Self-review

- **Placeholders:** none.
- **Consistency:** the new setting follows the `inactivityThreshold` pattern end-to-end; window
  unification reuses that same value on both the `HealthTracker` and `EscalationManager` sides.
- **Scope:** single, focused implementation plan.
- **Ambiguity:** name (`movementStepThreshold`), default (50), range (10–500/10), and
  window=threshold semantics are all pinned explicitly.
