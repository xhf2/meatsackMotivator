# Pace-message enable toggles (behind-pace + end-of-day)

**Date:** 2026-06-21
**Branch:** `feature/pace-message-toggles` (to be created)
**Status:** Designed — awaiting implementation plan

## Problem

The watch fires three independent kinds of motivational message:

- **Inactivity** ("regular") — `MeatsackWearService` polls and fires when idle past a threshold.
- **Behind-pace** — `BehindPaceWorker` fires once daily (default noon) if step count is behind the expected pace for that hour.
- **End-of-day** — `EndOfDayWorker` fires once daily (default 9pm) if the daily step goal was missed.

Receiving inactivity *and* behind-pace messages together is confusing — it's hard to tell which subsystem fired and why. The user wants to silence the pace-based messages independently while keeping the inactivity messages.

## Goal

Add **two independent on/off toggles** to the phone Settings screen:

- **Behind-pace messages** — on/off (default **on**).
- **End-of-day messages** — on/off (default **on**).

When a toggle is off, that worker stops delivering insults on the watch. Inactivity messages are unaffected by either toggle. Defaults preserve today's behavior (everything on).

Out of scope: a toggle for inactivity messages; surfacing an end-of-day *hour* slider in the UI (only the enable toggle is added; the hour remains at its synced/default value).

## Final design

Two new boolean settings ride the **existing** phone→watch settings pipeline. No new DataItem path and no new sync infrastructure are introduced.

### Data flow (unchanged pipeline)

```
SettingsScreen (Switch)
  → SettingsViewModel.toggleBehindPaceEnabled() / toggleEndOfDayEnabled()
  → SettingsRepository (DataStore write)
  → RepositorySettingsSource.combine(...)            // builds SettingsSnapshot
  → PhoneSettingsSyncer (500 ms debounce, distinctUntilChanged)
  → /settings DataItem (Wear Data Layer)
  → WatchSettingsReceiver.applySnapshot → cacheAdapter
  → WatchSettingsCache (SharedPreferences "watch_settings")
  → BehindPaceWorker / EndOfDayWorker read the flag at run time
```

`SettingsSnapshot` (shared module) is the single wire-shape choke point: adding a field there forces both the phone writer and the watch reader to handle it, so the two sides cannot drift.

### The two settings

| Setting | Key | Default | Consumed by |
| --- | --- | --- | --- |
| `behindPaceEnabled` | `behind_pace_enabled` | `true` | `BehindPaceWorker.doWork()` |
| `endOfDayEnabled` | `end_of_day_enabled` | `true` | `EndOfDayWorker.doWork()` |

Defaults live once in `SettingsDefaults` (shared) and are mirrored by the phone repository fallbacks, the watch cache fallbacks, and `SettingsSnapshot.defaults`. A default of `true` means an older phone that never sends these keys leaves both messages enabled (backward compatible) — `DataMap.getBoolean(key, default)` returns the default for absent keys.

### Worker gate: disable = skip, not cancel

Each worker already reschedules itself for tomorrow at the **top** of `doWork()` — before any early-return — specifically so a skipped run cannot break the daily chain. The enable check is inserted **immediately after that reschedule** and before the health-data read:

```kotlin
// BehindPaceWorker.doWork(), after the scheduleBehindPaceCheck(...) try/catch:
if (!settings.behindPaceEnabled) {
    Log.d(TAG, "Behind-pace messages disabled; skipping")
    return Result.success()
}
```

```kotlin
// EndOfDayWorker.doWork(), after the scheduleEndOfDay(...) try/catch:
if (!settings.endOfDayEnabled) {
    Log.d(TAG, "End-of-day messages disabled; skipping")
    return Result.success()
}
```

Consequences (intended):

- Disabling does **not** cancel the WorkManager job. The worker keeps waking daily and silently returns `Result.success()` without delivering.
- Re-enabling resumes automatically at the next scheduled run — no need to reopen the watch app, because the daily reschedule chain was never broken.
- The gate sits before message selection, so the worker's INACTIVITY-pool fallback also does not fire when the trigger is disabled.

This is deliberately simpler than reacting to the setting change on the watch to cancel/re-enqueue WorkManager. The cost is that a disable takes effect at the next daily cycle rather than instantly — acceptable, since these triggers fire at most once per day anyway.

## Components

| Module | File | Change |
| --- | --- | --- |
| shared | `sync/SettingsDefaults.kt` | Add `BEHIND_PACE_ENABLED = true`, `END_OF_DAY_ENABLED = true` |
| shared | `sync/SettingsKeys.kt` | Add `KEY_BEHIND_PACE_ENABLED`, `KEY_END_OF_DAY_ENABLED` |
| shared | `sync/SettingsSnapshot.kt` | Add `behindPaceEnabled`, `endOfDayEnabled`; wire `toDataMap`, `fromDataMap`, `defaults` |
| mobile | `data/SettingsRepository.kt` | Add 2 `booleanPreferencesKey`, 2 `Flow<Boolean>`, 2 setters |
| mobile | `ui/settings/SettingsViewModel.kt` | Add 2 `stateIn` flows, 2 toggle functions |
| mobile | `ui/settings/SettingsScreen.kt` | Add 2 `Switch` rows (mirroring the "Context-aware language" row) |
| mobile | `sync/PhoneSettingsSyncer.kt` | Add 2 flows to `RepositorySettingsSource.combine(...)` and to `current()` |
| wear | `settings/WatchSettingsCache.kt` | Add 2 boolean properties + 2 private keys |
| wear | `sync/WatchSettingsReceiver.kt` | Add 2 entries to `ApplySink`, `applySnapshot`, `cacheAdapter` |
| wear | `trigger/BehindPaceWorker.kt` | Insert enable gate after reschedule |
| wear | `trigger/EndOfDayWorker.kt` | Insert enable gate after reschedule |

### UI placement

- **Behind-pace toggle:** a `Switch` row placed with the existing "Behind-pace check hour" slider section in `SettingsScreen`. Label "Behind-pace messages", with bodySmall help text (e.g. "When off, the watch won't nag you for falling behind your step pace during the day.").
- **End-of-day toggle:** a new `Switch` row (no accompanying hour slider). Label "End-of-day messages", help text (e.g. "When off, the watch won't nag you at the end of the day for missing your step goal.").

Both rows follow the existing pattern: `Row` with `Text(..., Modifier.weight(1f))` + `Switch(checked, onCheckedChange)`, followed by a bodySmall help `Text`.

## Testing

- **`SettingsSnapshot` round-trip (unit):** `toDataMap` then `fromDataMap` preserves both booleans for `true` and `false`; both default to `true` when the key is absent from the `DataMap`. Extend the existing snapshot test if one exists.
- **`WatchSettingsReceiver.applySnapshot` (unit):** both flags are forwarded to the `ApplySink`. Extend the existing receiver test if one exists.
- **Worker gate:** the gate is a trivial boolean branch reading `WatchSettingsCache`. Covered by the existing `:wear` unit suite where feasible; otherwise verified by manual on-device check (toggle off on phone → confirm the worker logs "disabled; skipping" and no notification posts; toggle on → next run delivers).

## Notes / decisions

- **Backward compatibility:** default `true` everywhere means a phone or watch that predates these keys behaves exactly as today.
- **Instant vs next-cycle:** a disable is honored at the next daily worker run, not instantly. These triggers fire at most once per day, so there is no user-visible downside.
- **No WorkManager cancellation on the watch:** chosen for simplicity; the gate is sufficient and keeps the self-healing daily reschedule chain intact.
- **Inactivity unaffected:** neither toggle touches `MeatsackWearService` / `EscalationManager`.
