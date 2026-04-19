# CLAUDE.md

## Project Overview

**meatsackMotivator** — Samsung Galaxy Watch (Wear OS 3+) app that delivers aggressive, David Goggins–style motivational insults triggered by inactivity detected via Health Services. A phone companion app manages the settings and insult library, syncing the active message set to the watch over the Wear Data Layer.

## Commands

All Gradle tasks assume `JAVA_HOME` points at Android Studio's bundled JDK (`/c/Program Files/Android/Android Studio/jbr` on Windows).

```bash
# Build everything
./gradlew build

# Compile individual modules (fast iteration)
./gradlew :shared:compileDebugKotlin
./gradlew :wear:compileDebugKotlin
./gradlew :mobile:compileDebugKotlin

# Package APKs
./gradlew :wear:assembleDebug     # → wear/build/outputs/apk/debug/wear-debug.apk
./gradlew :mobile:assembleDebug   # → mobile/build/outputs/apk/debug/mobile-debug.apk

# Tests
./gradlew :wear:testDebugUnitTest          # EscalationManager unit tests
./gradlew :shared:connectedAndroidTest     # Room DAO tests (needs emulator)
./gradlew :wear:connectedAndroidTest       # MessageRepository tests (needs emulator)

# Install onto the paired emulators
./gradlew :wear:installDebug
./gradlew :mobile:installDebug
```

## Modules

Three Gradle modules:

- **`shared/`** — Room `AppDatabase`, `Message` entity, `MessageDao`, enum constants (`EscalationLevel`, `TriggerType`, `MessageTone`, `MessageSource`). Room is declared `api` so its types leak to dependents (because `AppDatabase : RoomDatabase()`).
- **`wear/`** (`com.meatsack.motivator`) — Watch app. Foreground service `MeatsackWearService` that polls `StepTracker` every 60 s, asks `EscalationManager` whether to fire, routes through `MessageRepository` to pick a message, then delivers via `InsultNotificationService` (haptic + full-screen `InsultActivity`). `WatchSyncReceiver` (`WearableListenerService`) receives `/messages` DataItems from the phone.
- **`mobile/`** (`com.meatsack.motivator.mobile`) — Phone companion. `MainActivity` hosts a Compose `NavGraph` with bottom nav for Library and Settings. `SettingsRepository` backs a DataStore. `LibraryViewModel` reads from the shared Room DB; `LibraryScreen` has a Sync-to-Watch button that calls `PhoneSyncSender` (serializes the active low-hate messages and writes a DataItem at `/messages`).

## Data Flow (v1)

1. Phone seeds the Room DB on first launch (`MeatsackMobileApp` → `SeedData`).
2. User opens the phone app's **Library** tab and taps **Sync to Watch** — `PhoneSyncSender` writes a `/messages` DataItem.
3. Watch's `WatchSyncReceiver` fires `onDataChanged`, deserializes, and inserts into the watch's Room DB.
4. User opens the watch app (tap launcher icon) — `MainActivity` requests `ACTIVITY_RECOGNITION` then starts `MeatsackWearService`.
5. `MeatsackWearService` polls every minute: `StepTracker.getMinutesSinceLastMovement()` → `EscalationManager.shouldTrigger(...)` → `MessageRepository.selectMessage(...)` → `InsultNotificationService.deliverInsult(...)`.
6. Full-screen `InsultActivity` wakes the screen. User taps 👍/👎 — vote hits local DAO.

## Emulator Dev Loop

Requires **two** running emulators (phone + Wear OS) paired via Android Studio's Device Manager **Pair Wearable** wizard. The wizard installs `com.google.android.apps.wear.companion` on the phone, sets up an `adb forward tcp:5601` tunnel, and handles the GMS pairing handshake. Verify with:

```bash
adb -s emulator-5554 shell dumpsys activity service com.google.android.gms/.wearable.service.WearableService | grep "connected out of"
# → "1 connected out of 1" when healthy
```

The forward rule is **ephemeral** — if either emulator or adb restarts, re-run the pairing wizard (or manually: `adb -s emulator-5554 forward tcp:5601 tcp:5601`).

Because the watch foreground service has `type="health"`, `ACTIVITY_RECOGNITION` must be *granted*, not just declared. For automated test runs:

```bash
adb -s emulator-5556 shell pm grant com.meatsack.motivator android.permission.ACTIVITY_RECOGNITION
```

## Git Workflow

- **Do not commit to `main`.** Work lives on feature branches (e.g., `feature/v1-implementation`). PR into `main`.
- Branch naming: `fix/<desc>`, `feature/<desc>`.

## Known v1 Limitations

- Message serialization uses a custom `|`-delimited string. Fine for v1 (≤50 messages × ~200 chars). If we ever need nested structure or multi-line text with `|`, switch to JSON (`kotlinx.serialization`) and bump the DataItem path (e.g. `/messages/v2`).
- Sync is one-way (phone → watch). Votes recorded on the watch don't propagate back. A back-sync path will need a new DataItem path and a phone-side `WearableListenerService`.
- Service relies on `StepTracker` daily-step deltas as a movement proxy. Emulators emit a synthetic step stream at ~2/sec, so "inactivity" never triggers naturally during development — drop `INACTIVITY_THRESHOLD_MINUTES_DEFAULT` to 1 temporarily or pause the emulator's Health Services to test the escalation path.
