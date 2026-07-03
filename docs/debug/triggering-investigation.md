# Triggering / escalation bug — investigation + debug-page plan

**Status:** Phase 1 (root-cause investigation) done from code reading. **Debug instrumentation
built** (2026-07-01, branch `fix/triggering-diagnostics`) — option A (full phone Debug screen),
evidence-first (no behavior fix yet). Builds + unit tests green; **awaiting install on the
physical devices, then a day of on-device evidence before fixing.**

---

## What was built (the diagnostics pipe)

Temporary, clearly marked "remove with the rest of the diagnostics pipe". Mirrors the existing
`/votes` back-sync pipe.

- **shared**
  - `sync/SyncChannel.kt` — `PATH_DIAGNOSTICS="/diagnostics"`, `KEY_DIAG_DATA`, `MAX_DIAG_ROWS=400`
    (wire window; sized < 100 KB DataItem limit).
  - `sync/DiagnosticsSerializer.kt` — newline wire format (+ test).
  - `diagnostics/DiagnosticsLineStore.kt` — pure-JVM persistent capped line buffer + `merge()`
    (overlap-merge of sliding windows). Per-path interned-string lock for cross-instance safety
    (+ test).
- **wear**
  - `MovementDetector.debugSnapshot()` → `MovementSnapshot` (idleMin, winSteps, winAge, lastTotal).
  - `diagnostics/WatchDiagnostics.kt` — timestamps + appends to `filesDir/watch_diagnostics.log`
    (survives service/process kills → catches H2).
  - `HealthTracker` — optional `WatchDiagnostics`; logs each `STEP` update.
  - `sync/WatchDiagnosticsSender.kt` — pushes recent window to phone over `/diagnostics`.
  - `MeatsackWearService` — logs `LIFECYCLE onCreate/onStartCommand/onDestroy`, logs **every poll**
    (`POLL hour=.. inWindow=.. idleMin=.. winSteps=.. total=.. move=.. -> SKIP(...)|FIRE level=..`),
    and pushes to phone each poll. **Triggering behavior deliberately unchanged.**
- **mobile**
  - `sync/PhoneDiagnostics.kt` + `sync/PhoneDiagnosticsReceiver.kt` (registered in manifest) —
    overlap-merges pushes into `filesDir/phone_diagnostics.log` (cap 10 000).
  - `ui/debug/DebugViewModel.kt` + `ui/debug/DebugScreen.kt` — newest-first monospace log with
    Refresh / Clear / Share. Reached via a "🐛 Trigger debug log" button at the bottom of Settings
    (route `debug` in `NavGraph`).

### How to read the log (what each hypothesis looks like)
- **H1 (7am level-4 nuke):** the overnight `POLL` lines show `idleMin` climbing into the hundreds
  while `inWindow=false`; the first `inWindow=true` poll at ~7am shows a huge `idleMin` → `FIRE
  level=4`. Confirms idle clock is *not* frozen overnight, only delivery is gated.
- **H2 (silent all day):** look for `LIFECYCLE onCreate` lines **without** a matching prior
  `onDestroy`, or bursts of them, during the day — each resets `idleMin` back near 0, so it never
  reaches the threshold. (Secondary: `STEP` lines with large deltas tripping `move=true` resets;
  or `POLL` showing a wrong `thr=`/window from stale synced settings.)

### Install (blocked on devices being connected)
```bash
adb -s ZY22KS3ML2 install -r mobile/build/outputs/apk/debug/mobile-debug.apk   # phone (moto g75)
adb -s <watch-id> install -r wear/build/outputs/apk/debug/wear-debug.apk        # watch (SM-L320, wireless adb)
```
Then open the watch app (grant ACTIVITY_RECOGNITION) so the service + polling start; open the phone
Settings → 🐛 Trigger debug log → Refresh.

---

## Symptoms (reported from the physical Galaxy Watch)

1. **No insults all of "yesterday"** even with the inactivity window set to 10 min and the
   movement threshold set to 400 steps, and even after adjusting those throughout the day.
2. **A level 4 (EXISTENTIAL) insult fired first thing at ~7am today** — no escalation ramp,
   straight to max.

Cannot reproduce on the emulator (emulator emits a synthetic ~2 steps/sec stream), so we need
on-device instrumentation.

---

## How triggering actually works (all on the **watch** / `wear` module)

`MeatsackWearService` (`wear/.../MeatsackWearService.kt`) runs a foreground service, polls every
60s → `checkInactivity()` (lines ~109–147):

1. `if (!ActiveWindow.contains(hour, activeHoursStart, activeHoursEnd)) return`
   — outside active hours (default 7–22) it **returns early**; comment says escalation is
   "left frozen (not advanced, not reset)".
2. `minutesIdle = healthTracker.getMinutesSinceLastMovement()`
   — = `(now - lastMovementTimestamp) / 60000`, i.e. **real wall-clock** (see `MovementDetector`).
3. `if (healthTracker.hasSignificantMovement()) { escalationManager.onMovementDetected(); return }`
4. `if (!escalationManager.shouldTrigger(minutesIdle)) return`
5. fire: `level = calculateLevel(minutesIdle)`, deliver notification.

**`EscalationManager`** (`wear/.../escalation/EscalationManager.kt`):
- `calculateLevel(minutesIdle)`: `minutesPast = max(0, minutesIdle - threshold)`,
  `escalations = minutesPast / 30`; `>=3 EXISTENTIAL, >=2 NUCLEAR, >=1 SAVAGE, else AGGRESSIVE`.
- `shouldTrigger`: fires when `minutesIdle >= threshold` and (first fire OR
  `minutesIdle - lastFiredAtIdle >= 30`).
- **All state is in-memory**: `lastFiredAtIdle`, `_currentLevel`, `isActive`, `inactivityStartTime`.

**`MovementDetector`** (`wear/.../health/MovementDetector.kt`):
- `lastMovementTimestamp` and `windowStartTimestamp` initialised to `now()` **at construction**.
- `onStepTotal(currentTotal)` (called only when a STEPS_DAILY passive datapoint arrives):
  computes delta from cumulative total, tumbling window (`windowMs = inactivityThreshold min`),
  `stepsInCurrentWindow += delta`; if `>= movementStepThreshold` → `lastMovementTimestamp = now`,
  reset window. Window only tumbles **inside `onStepTotal`** (i.e. driven by callback cadence).
- `minutesSinceLastMovement()` = `(now() - lastMovementTimestamp)/60000`.

**Lifecycle:** `MeatsackWearService.onCreate` constructs a fresh `HealthTracker` →
fresh `MovementDetector` (so `lastMovementTimestamp = now()`) and a fresh `EscalationManager`.
Service is `START_STICKY`. **Any service/process recreate resets all idle + escalation state.**

---

## FINDINGS (2026-07-02, one night + one day of on-device evidence) — ROOT CAUSE CONFIRMED

The 498-line log settles it. **Both reported symptoms are a single coupled bug; H2(a) "service
kills/restarts" is refuted.**

**Service was stable:** exactly one `LIFECYCLE onCreate`/`onStartCommand` (2026-07-01 22:04),
**no restarts** across ~18 h. So the all-day silence is NOT process death. Good thing we gathered
evidence instead of "fixing" battery optimization.

**Root cause A — H1 confirmed (overnight idle inflation → 7am level-4 nuke):**
`minutesIdle` is pure wall-clock (`now − lastMovementTimestamp`) and the active-hours gate only
skips *delivery* (early `return`), never the *measurement*. `lastMovementTimestamp` doesn't advance
overnight, so idle climbed monotonically all night (02:06 → 241, 03:20 → 315) and the first
in-window poll fired instantly at max:
```
07-02 07:02:50 POLL hour=7 inWindow=true idleMin=537 ... -> FIRE level=4   # (537-10)/30 = 17 escalations → EXISTENTIAL
07-02 07:32 / 08:03 / 08:33 / 09:03  → FIRE level=4 (every ~30 min, idleMin 567→658)
```

**Root cause B — the *real* "no insults all day" (stale `lastFiredAtIdle`, movement never resets
escalation state):**
- `EscalationManager.onMovementDetected()` was called **0 times all day** (grep `movement-reset` = 0)
  despite thousands of steps. Reason: `MovementDetector.onStepTotal` resets `stepsInCurrentWindow`
  to 0 the instant it crosses the threshold, so by the next ~poll `hasSignificantMovement()` is
  almost always false → the service never tells the EscalationManager the user moved.
- So after the 09:03 fire pinned `lastFiredAtIdle = 658`, `shouldTrigger` gates every later poll on
  `idleMin − 658 ≥ 30` (i.e. needs `idleMin ≥ 688`). The idle *clock* kept resetting on real walks
  (09:35 idleMin=8, 13:39 idleMin=0) but `lastFiredAtIdle` stayed 658, so from 09:03 to 16:20 the
  app went **silent** — every poll `SKIP(no-trigger)`, even at idleMin=204:
```
07-02 09:03 FIRE level=4 idleMin=658          # pins lastFiredAtIdle=658
07-02 09:51 idleMin=24  -> SKIP(no-trigger)   # new idle streak, but 24-658 < 30
07-02 12:51 idleMin=204 -> SKIP(no-trigger)   # still < 688
07-02 16:19 idleMin=160 -> SKIP(no-trigger)   # silent all day
```
The two symptoms are the same failure: overnight inflation both fires the 7am nuke **and** corrupts
`lastFiredAtIdle`, which then suppresses the whole day.

**Contributing factor (not root cause):** background `delay()` is throttled by Doze — poll gaps ran
13–36 min, not 60 s (e.g. 12:51 → 13:27). Coarsens resolution; note when designing the fix.

**STATUS 2026-07-02 17:52:** Fix IMPLEMENTED + committed (`fix/triggering-escalation`, db4517a,
TDD, all unit tests green) and INSTALLED on the watch. Root-cause-A wiring verified live — the
first in-window poll after install logged `REBASELINE active-window re-entry` and reset `idleMin`
to 0 instead of inheriting the old build's 250. Diagnostics pipe left in place. **Pending: the 7am
boundary + a normal day tomorrow (2026-07-03) to confirm no level-4 nuke and no all-day silence,
then strip diagnostics + open PR.** What to check tomorrow: a `REBASELINE ... hour=7` line at the
first in-window poll; the 7am poll showing small `idleMin` → NOT `FIRE level=4`; and daytime
`FIRE`s resuming after idle stretches (no endless `SKIP(no-trigger)`).

**Fix direction (Phase 4 — IMPLEMENTED as above; kept for reference):**
1. **Rebaseline on active-window re-entry:** on the first in-window poll after being out of window,
   reset the idle clock + escalation (`onMovementDetected()` / clamp) so the morning ramp starts
   fresh at AGGRESSIVE instead of EXISTENTIAL. Kills the 7am nuke.
2. **Reliably reset escalation on movement:** decouple "user moved" from the tumbling-window
   auto-reset so `lastFiredAtIdle`/`isActive` actually clear when the user walks — OR make
   `shouldTrigger` robust to a shrinking `idleMin` (treat `idleMin < lastFiredAtIdle` as a new
   streak). Kills the all-day silence.
Write a failing unit test in `EscalationManagerTest` / `MovementDetectorTest` reproducing each
before fixing.

## Hypotheses

### H1 — "Level 4 at 7am" — HIGH confidence (from code alone)
The active-hours gate skips *delivery* overnight, but `minutesIdle` is wall-clock and
`lastMovementTimestamp` does not advance while you sleep. At 7:00 the first in-window poll sees
`minutesIdle ≈ 540` (since ~last night's last movement) → `(540-10)/30 = 17` escalations →
**EXISTENTIAL fires instantly**. The "frozen" comment is wrong: the idle clock keeps running
through the inactive overnight window; only *delivery* is gated, not the measurement.

**Likely fix (after evidence):** when re-entering the active window (or when the gap since last
movement spans outside active hours), reset/clamp the idle baseline + escalation so the ramp
starts fresh at the start of the day. Candidate: on the first in-window poll after being out of
window, call `onMovementDetected()` (or rebaseline `lastMovementTimestamp`) so escalation begins
at AGGRESSIVE.

### H2 — "No insults all day" — needs on-device evidence (multiple candidates)
State is in-memory only. Candidates:
- **(a) Service kills/restarts (Samsung/Wear battery optimization — see README known limits).**
  Each recreate resets `lastMovementTimestamp = now()`, so `minutesIdle` never climbs to the
  threshold → nothing fires. Strongest candidate.
- (b) Batched/sparse STEPS_DAILY deltas landing in a fresh tumbling window and tripping false
  "movement" (>= 400 in one batch) → idle keeps resetting.
- (c) Synced settings not actually reaching the watch (`WatchSettingsCache` stale) → wrong
  threshold/window in effect.

H1 + H2 are consistent: overnight on a charger the service runs stably → idle accumulates →
level 4 at 7am; during the day on-wrist, battery opt churns the service → idle resets → silence.

---

## Plan: temporary debug instrumentation (Phase-1 evidence gathering)

**Key requirement:** the log MUST persist across service restarts (write each event to a file on
the watch immediately) — otherwise it can't catch H2 (a dying service takes its in-memory log
with it).

**Watch** — a `DiagnosticsLog` (persistent, capped ring buffer in a file under `filesDir`) records,
with timestamps:
- Service lifecycle: `onCreate` / `onStartCommand` / `onDestroy` (← reveals kills/restarts = H2)
- Every 60s poll: hour, in-active-window?, `minutesIdle`, steps-in-window, total steps today,
  `hasSignificantMovement`, `shouldTrigger`, computed level, and **fired or skip-reason**
- Every step update (`onStepTotal`): cumulative total, delta, window age, steps-in-window
- Every fire: level, trigger, idle
  (Will need to expose `MovementDetector` internal state via a small `debugSnapshot()`.)

**Sync** — new `/diagnostics` Data Layer channel mirroring the existing `/votes` pipe
(`WatchVoteSender` → `PhoneVoteReceiver`; constants in `shared/.../sync/SyncChannel.kt`).
Watch pushes recent lines → phone each poll.

**Phone** — `PhoneDiagnosticsReceiver` (WearableListenerService) stores received lines; a **Debug
screen** reached from a button at the bottom of Settings shows the log newest-first, monospace,
with **Refresh / Clear / Share**.

### Reuse / reference points
- Sync channel constants: `shared/src/main/java/com/meatsack/shared/sync/SyncChannel.kt`
  (`PATH_VOTES`, `KEY_VOTE_DATA`, `KEY_TIMESTAMP`, `MAX_VOTE_ROWS`). Add `PATH_DIAGNOSTICS`,
  `KEY_DIAG_DATA`, `MAX_DIAG_ROWS`.
- Sender pattern: `wear/.../sync/WatchVoteSender.kt`.
- Receiver pattern: `mobile/.../sync/PhoneVoteReceiver.kt` (and registered as a
  `WearableListenerService` in the mobile manifest — check `mobile/src/main/AndroidManifest.xml`).
- Phone nav/screens: `mobile/.../ui/navigation/NavGraph.kt`, `ui/settings/SettingsScreen.kt`.
- Serializer pattern (`|` + newline): `shared/.../sync/VoteSyncSerializer.kt` — though for
  diagnostics, preformatted single-line strings (timestamp + text) joined by `\n` are simplest.

---

## DECISIONS (resolved 2026-07-01 on resume)

1. **Scope of the debug tool: (A) Full** — watch persistent log + `/diagnostics` sync +
   phone Debug screen (refresh/clear/share). Reinstall BOTH watch and phone apps.
2. **H1 fix timing: (A) Evidence-first** — build debug tooling only now; fix H1 + H2 after
   a day of on-device logs confirms the mechanism. No escalation/idle behavior change yet.

---

## Context / where things stand
- On `main` (clean working tree apart from pre-existing untracked: `.agents/`, `skills-lock.json`,
  `docs/superpowers/plans/2026-06-25-externalize-insults.md`).
- Recently merged: PR #50 (insults archive), #51 (Vitals redesign), #52 (Bubblegum theme).
- Phone app (debug build) is installed and running on the physical phone (moto g75, `ZY22KS3ML2`).
- Emulator was shut down.
- Suspect recent commits that touched movement/escalation: `f4f2fde` (floor movement window >=1;
  re-arm + live window), `cdaa41c`/`384c701` (configurable movement step threshold). Worth a
  `git log -p` on `MovementDetector.kt` / `EscalationManager.kt` when resuming.

## Resume checklist
1. Re-read this file. Decisions resolved (A / evidence-first); tooling built on
   `fix/triggering-diagnostics`; builds + unit tests green.
2. **Next:** connect the physical phone + watch (wireless adb), run the two install commands above,
   open the watch app (grant ACTIVITY_RECOGNITION), confirm the phone Debug screen receives lines.
3. Let it run through an overnight + a normal day. Read the log per "How to read the log" above to
   confirm H1 and identify which H2 candidate is real.
4. Only then write the fix (Phase 4), on its own branch, with a failing test reproducing the
   confirmed root cause first.
5. Commit the diagnostics on `fix/triggering-diagnostics`; PR into `main` (or hold until the fix
   lands on top, then PR together). Never commit to `main`.
