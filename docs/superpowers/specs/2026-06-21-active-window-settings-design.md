# Active-window settings redesign

**Date:** 2026-06-21
**Branch:** `feature/active-window-settings` (to be created)
**Status:** Designed — awaiting implementation plan

## Problem

The phone Settings screen has two hour-range controls that look like opposites of one
concept: **Active Hours** and **Quiet Hours** (each two sliders). They can drift out of
alignment, and the model is confusing. Investigation found:

- **Quiet Hours is dead code.** It is stored in DataStore, shown as two sliders, and
  editable — but read by **nothing** (not synced to the watch, no suppression logic
  anywhere). The 22:00–7:00 default implies a do-not-disturb window that was never wired up.
- **Active Hours is load-bearing but overloaded.** It is synced to the watch and drives
  (a) message **tone** via `ToneResolver` (inside window → WORK_SAFE, only when
  context-aware is on) and (b) the **pace-expectation** window in `PaceCalculator`. It does
  **not** gate whether insults fire — the inactivity nagger fires 24/7. There is no
  quiet-time suppression anywhere today.

The user wants the watch to be quiet outside a chosen window, and wants the tone ("work-safe
language") window controlled independently.

## Goal

Promote **Active Hours** to the single "active day" window and give **Context-aware**
language its own hours, decoupling the two meanings currently tangled on Active Hours.

- **Active Hours** = the hours the watch may insult you. Outside it, the watch is quiet.
- **Context-aware** = a separate work-safe-tone window with its own start/end.
- Delete the dead **Quiet Hours** controls and the separate **end-of-day hour** setting.

Defaults preserve current behavior as closely as possible (Active Hours 7:00–22:00; the
inactivity nagger effectively unrestricted during waking hours).

## Final design

### Active Hours = the master "active day" window

A single range (start ≤ end) that drives four behaviors:

| Trigger | Relationship to Active Hours |
| --- | --- |
| **Inactivity** | Fires **only inside** the window. Outside → suppressed (new behavior). |
| **Pace expectation** | Expected-step ramp spans the window (`PaceCalculator`, unchanged mechanism). |
| **Behind-pace** | Keeps its own check-hour, but the value is **clamped to `[activeStart, activeEnd]`**. |
| **End-of-day** | Fires at **`activeHoursEnd`**; the separate end-of-day-hour setting is removed. |

**Window boundary convention:** `start ≤ hour < end` — consistent with the existing
`ToneResolver`. So with Active Hours 7–22, hour 22 is *outside* (quiet). End-of-day is a
scheduled fire *at* `activeHoursEnd` (22:00), which is the intended bookend, not a
"contains" check.

### Context-aware = its own work-safe-tone window

- Keeps the existing on/off toggle.
- Adds two hour values `contextAwareStart` / `contextAwareEnd` (numeric 0–23 input boxes in
  the UI). Defaults 9 / 17.
- `ToneResolver` switches from Active Hours to these: when context-aware is **on** and the
  current hour is inside `[contextAwareStart, contextAwareEnd)`, tone is WORK_SAFE; otherwise
  FULL_SEND. When off, always FULL_SEND (unchanged).
- Boxes allow an overnight window (start > end); the windowing predicate handles wrap.

### One new pure function (the only genuinely new logic)

A windowing predicate in the **wear** module (`com.meatsack.motivator.messages`, alongside
`ToneResolver`), unit-tested:

```kotlin
object ActiveWindow {
    // True if `hour` falls in [start, end) with overnight-wrap support.
    fun contains(hour: Int, start: Int, end: Int): Boolean =
        if (start <= end) hour in start until end else hour >= start || hour < end
}
```

`ToneResolver` is refactored to reuse `ActiveWindow.contains` (passing the context-aware
hours) rather than inlining the same windowing arithmetic.

The behind-pace **clamp** does not warrant its own function — it is a plain
`hour.coerceIn(start, end)` performed inline inside `SettingsRepository.setActiveHours`, and
the UI slider's range already prevents picking an out-of-window value in the first place.

## Settings changes (wire-shape)

`SettingsSnapshot` (shared) — the synced field set changes:

- **Remove:** `endOfDayHour`.
- **Add:** `contextAwareStart: Int`, `contextAwareEnd: Int`.
- **Unchanged:** `dailyStepGoal`, `inactivityThreshold`, `activeHoursStart`, `activeHoursEnd`,
  `contextAwareEnabled`, `behindPaceCheckHour`, `behindPaceEnabled`, `endOfDayEnabled`.

Resulting snapshot is 10 fields; `RepositorySettingsSource.combine` goes from 9 to 10 flows.

Phone-only DataStore (`SettingsRepository`):

- **Remove:** `quietHoursStart/End` (keys, flows, `setQuietHours`) and `endOfDayHour`
  (key, flow, `setEndOfDayHour`).
- **Add:** `contextAwareStart/End` (keys, flows, setters).
- **Modify:** `setActiveHours(start, end)` also clamps the stored `behindPaceCheckHour` into
  the new window via an inline `coerceIn(start, end)`, so the data layer never holds an
  out-of-window behind-pace hour.

Defaults:

- `shared/SettingsDefaults`: remove `END_OF_DAY_HOUR`; add `CONTEXT_AWARE_START = 9`,
  `CONTEXT_AWARE_END = 17`; keep `ACTIVE_HOURS_START = 7`, `ACTIVE_HOURS_END = 22`.
- `mobile/SettingsDefaults`: remove `QUIET_HOURS_START/END`.

**Migration:** none needed. Orphaned `quiet_hours_*` / `end_of_day_hour` keys left in an
existing DataStore are simply ignored. Absent `context_aware_*` keys fall back to defaults
(9/17). `DataMap` get-with-default already handles a phone/watch version skew gracefully in
both directions.

## Watch behavior changes

- `ToneResolver.resolve(...)` — parameters change from `activeHoursStart/End` to
  `contextAwareStart/End`; internally calls `ActiveWindow.contains`.
- `MeatsackWearService.checkInactivity` — add an active-window gate near the top: if
  `!ActiveWindow.contains(currentHour, settings.activeHoursStart, settings.activeHoursEnd)`,
  return (no insult). Also update its `ToneResolver.resolve(...)` call to pass the
  context-aware hours.
- `EndOfDayWorker` — schedule and self-reschedule at `settings.activeHoursEnd` instead of
  `settings.endOfDayHour`; update its `ToneResolver.resolve(...)` call.
- `MeatsackWearService.onStartCommand` — schedule end-of-day at `settings.activeHoursEnd`.
- `BehindPaceWorker` — update its `ToneResolver.resolve(...)` call. The behind-pace hour it
  reads is already in-window (clamped on the phone). `PaceCalculator` continues to use the
  active hours, unchanged.
- `WatchSettingsCache` — remove `endOfDayHour`; add `contextAwareStart/End`.
- `WatchSettingsReceiver` — `ApplySink` / `applySnapshot` / `cacheAdapter`: drop the
  end-of-day-hour entry, add the two context-aware entries.

## Phone UI changes (`SettingsScreen`)

- **Active hours** — replace the two Start/End sliders with one Material3 **`RangeSlider`**
  (0–23, two thumbs, start ≤ end). Label "Active hours: H:00 – H:00"; help "Hours the watch
  can nag you. Outside this window it stays quiet."
- **Quiet hours** — remove both sliders entirely.
- **Behind-pace check hour** — slider `valueRange = activeStart..activeEnd`; relies on the
  repository clamp so a narrowed window snaps the value in.
- **Context-aware language** — keep the toggle; add two numeric hour **boxes**
  (`OutlinedTextField`, numeric, validated/clamped to 0–23) for start and end, enabled when
  the toggle is on. Label "Work-safe hours".
- `SettingsViewModel` — drop `quietHours*` and `endOfDayHour` exposures; add
  `contextAwareStart/End` flows + setters; `updateActiveHours` flows through the
  clamp-aware repository setter.

## Components

| Module | File | Change |
| --- | --- | --- |
| wear | `messages/ActiveWindow.kt` (new) | Pure `contains(hour, start, end)` predicate |
| wear | `messages/ToneResolver.kt` | Params → context-aware hours; reuse `ActiveWindow.contains` |
| wear | `MeatsackWearService.kt` | Active-window inactivity gate; end-of-day scheduled at `activeHoursEnd`; tone call update |
| wear | `trigger/EndOfDayWorker.kt` | Schedule/reschedule at `activeHoursEnd`; tone call update |
| wear | `trigger/BehindPaceWorker.kt` | Tone call update |
| wear | `settings/WatchSettingsCache.kt` | −`endOfDayHour`, +`contextAwareStart/End` |
| wear | `sync/WatchSettingsReceiver.kt` | Sink/apply/adapter field swap |
| shared | `sync/SettingsSnapshot.kt` | −`endOfDayHour`, +`contextAwareStart/End` |
| shared | `sync/SettingsKeys.kt` | −end-of-day-hour key, +context-aware keys |
| shared | `sync/SettingsDefaults.kt` | −`END_OF_DAY_HOUR`, +`CONTEXT_AWARE_START/END` |
| mobile | `data/SettingsRepository.kt` | −quiet/−endOfDayHour, +context-aware, clamp in `setActiveHours` |
| mobile | `data/SettingsDefaults.kt` | −`QUIET_HOURS_START/END` |
| mobile | `sync/PhoneSettingsSyncer.kt` | `combine` 9→10; `current()` field swap |
| mobile | `ui/settings/SettingsViewModel.kt` | −quiet/−endOfDayHour, +context-aware |
| mobile | `ui/settings/SettingsScreen.kt` | RangeSlider, remove quiet, clamp behind-pace range, context boxes |

## Testing

- **`ActiveWindow.contains`** unit tests: same-day window (`start < end`), boundary (`end`
  excluded, `start` included), overnight wrap (`start > end`), degenerate (`start == end`).
- **`ToneResolver`** — update `ToneResolverTest` to the context-aware-hours parameters
  (work-safe inside, full-send outside, full-send when disabled).
- **`SettingsSnapshot`** — update `SettingsSnapshotTest`: round-trip with non-default
  `contextAwareStart/End`, defaults assertions, drop `endOfDayHour`.
- **`WatchSettingsReceiver`** — update `WatchSettingsReceiverTest` fake sink + assertions.
- **Behind-pace clamp** — the inline `coerceIn` in `setActiveHours` has no unit harness
  (DataStore setter), consistent with the codebase; verified on-device. The clamp matters
  only when the window narrows past the saved value, since the slider range prevents picking
  out-of-window otherwise.
- **Manual on-device** (paired emulators): set a narrow Active window → confirm no inactivity
  insult fires outside it (`MeatsackWearService` logs a skip) and the `/settings` log shows
  the new `contextAwareStart/End` and absent `endOfDayHour`; confirm end-of-day schedules at
  `activeHoursEnd`. (Compose UI + workers have no unit harness in this project — verified
  on-device.)

## Out of scope / notes

- Overnight **Active Hours** (start > end) is not offered — the range slider enforces
  start ≤ end. The `ActiveWindow.contains` wrap branch exists for the context-aware boxes,
  which do allow an overnight work-safe window.
- The escalation state machine is unchanged: outside active hours we simply skip *delivery*;
  idle tracking continues. (No reset/complexity added.)
- Behind-pace and end-of-day **enable toggles** (shipped in PR #41) are unchanged; this work
  only changes *when* those triggers fire relative to the active window.
