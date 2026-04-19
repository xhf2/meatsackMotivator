# meatsackMotivator

<img src="mobile/src/main/ic_launcher-playstore.png" width="160" align="right" alt="meatsackMotivator logo" />

> *"GET UP, you osteopenic jello mold."*

A Wear OS motivator that punishes inactivity with aggressive, Goggins-style insults delivered straight to your wrist. The watch tracks your movement; every 30 minutes you've been stationary, it buzzes, takes over your screen, and tells you exactly what it thinks of you. Get nastier every 30 minutes until you move.

Tap 👍 or 👎 on each insult. The algorithm learns which ones land.

---

## How it works

1. **Watch** passively watches your step count via Wear Health Services.
2. After `INACTIVITY_THRESHOLD_MINUTES` (default **30 min**) of zero meaningful movement, it fires an **insult**:
   - Half-second **haptic buzz** on your wrist
   - **Full-screen takeover** with the insult text and a stats line (step count, time of day, degree of pathetic)
   - Two circular buttons: 👍 if it hit, 👎 if it whiffed
3. Every 30 minutes you're still idle, it **escalates**: AGGRESSIVE → SAVAGE → NUCLEAR → EXISTENTIAL.
4. Move (50+ steps within a 5-minute window) → the idle timer resets and the escalation level drops back to AGGRESSIVE.
5. The **phone app** manages the insult library: browse messages, see vote tallies, sync new messages to the watch.

---

## Requirements

| Side | Needs |
|---|---|
| **Watch** | Samsung Galaxy Watch 4 or newer (Wear OS 3+). Tizen-era Galaxy Watches (Watch 3 and earlier) **won't work** — wrong OS entirely. |
| **Phone** | Any Android phone running Android 8 (API 26) or higher |
| **Pairing** | **Galaxy Wearable** app from Play Store — even on non-Samsung phones — for Bluetooth pairing + keeping the Data Layer bridge alive |

---

## Installation

### Prebuilt APKs (easiest)

Download the latest signed APKs from the [Releases page](https://github.com/xhf2/meatsackMotivator/releases).

**Phone:** enable USB debugging → `adb install mobile-debug.apk`.
**Watch:** enable ADB-over-Wi-Fi on the watch (see [dev loop below](#dev-loop)), then `adb pair` + `adb install wear-debug.apk`.

### Build from source

```bash
./gradlew :mobile:assembleDebug :wear:assembleDebug
```

APKs land at `mobile/build/outputs/apk/debug/mobile-debug.apk` and `wear/build/outputs/apk/debug/wear-debug.apk`.

---

## Using the phone app

![companion app](#)

Two tabs:

- **Library** — every message in the local DB, with vote tallies, level/trigger/source tags, and a **Sync to Watch** button. Tap Sync to push the active, low-hate messages (top 50 by net vote score) to the paired watch.
- **Settings** — sliders for: daily step goal, inactivity threshold, active hours, quiet hours, and a context-aware-language toggle (for cleaner wording during work hours — disabled by default; full send all day).

All settings are persisted with Jetpack DataStore and survive reboots.

---

## Using the watch app

1. Tap the launcher icon — a red knuckles-up fist.
2. On first launch, grant two permissions:
   - **Activity recognition** (required — the foreground service can't start without it)
   - **Notifications** (required on API 33+; otherwise insults are silently suppressed)
3. The service starts and finishes the launcher screen. You'll see a **persistent notification** in the watch's notification shade: *"Watching you, you lazy meatsack."* That's the foreground service — keep it active.
4. Wait 30 minutes while sitting still. Your wrist will buzz and a full-screen insult will appear. Tap 👍 or 👎 to dismiss.

### Escalation curve

| Minutes idle | Level | Vibe |
|---|---|---|
| 0–29 | (nothing) | grace period |
| 30–59 | AGGRESSIVE | "You lazy meatsack." |
| 60–89 | SAVAGE | "Your chair knows you by name." |
| 90–119 | NUCLEAR | "Your ancestors are disappointed." |
| 120+ | EXISTENTIAL | "The void called. Asked for you back." |

Move 50 steps within any 5-minute window → everything resets.

---

## Architecture

Three Gradle modules, one Kotlin project:

```
meatsackMotivator/
├── shared/          # Room DB, Message entity, DAO, enums, wire serializer
├── wear/            # Watch app — foreground service, step tracker, escalation, UI
└── mobile/          # Phone companion — library browser, settings, sync sender
```

- **`shared`** exposes the `AppDatabase` (Room + KSP), `Message` entity, and `MessageSerializer` used by both sides of the sync pipe. `RoomDatabase` leaks through the public API, so Room is declared `api` not `implementation`.
- **`wear`** drives everything on the watch: `StepTracker` reads `STEPS_DAILY` via `androidx.health.services.client` and tracks "minutes since last movement"; `EscalationManager` decides whether to fire based on time-since-last-fire (resistant to poll-drift); `MessageRepository` picks a message (30% chance of showing an unvoted one to bootstrap ratings; otherwise weighted by net votes); `InsultNotificationService` vibrates and shows the full-screen takeover; `MeatsackWearService` is the foreground service that ties it all together and polls every 60 seconds.
- **`mobile`** is a Compose app: `MainActivity` hosts a `NavGraph` with bottom nav for Library and Settings. `SettingsRepository` wraps `DataStore<Preferences>`. `PhoneSyncSender` serializes up to 50 active messages as a `|`-delimited payload and writes a DataItem at `/messages`.
- **Phone ↔ Watch sync**: `PhoneSyncSender` writes a DataItem → Wear Data Layer propagates it via the paired Galaxy Wearable bridge → `WatchSyncReceiver` (a `WearableListenerService` on the watch) deserializes and inserts into the watch's local Room DB.

---

## Dev loop

Full developer documentation lives in [`CLAUDE.md`](CLAUDE.md). The short version:

```bash
# Build + install on devices
./gradlew :mobile:installDebug               # phone
./gradlew :wear:installDebug                 # watch

# Run tests
./gradlew :shared:testDebugUnitTest          # serializer round-trip + regression tests
./gradlew :wear:testDebugUnitTest            # escalation manager
./gradlew :shared:connectedAndroidTest       # Room DAO (needs emulator)
./gradlew :wear:connectedAndroidTest         # message repository (needs emulator)

# Formatting
./gradlew spotlessCheck                      # validate
./gradlew spotlessApply                      # auto-fix
```

### Pre-commit hook

```bash
./.githooks/install.sh
```

One-time per clone/worktree. Enables the hook at `.githooks/pre-commit`, which runs `spotlessCheck` + unit tests before every commit. Bypass for emergencies with `git commit --no-verify`.

### Emulator pairing

Two emulators: phone AVD + Wear OS AVD. Pair via **Android Studio → Device Manager → Pair Wearable** (the wizard installs the Wear companion and sets up `adb forward tcp:5601`). The forward rule is ephemeral — re-run the wizard (or `adb -s emulator-5554 forward tcp:5601 tcp:5601`) after any emulator or `adb` restart.

### Deploying to real Galaxy Watch

Watches don't have USB, so ADB-over-Wi-Fi:

1. On watch: **Settings → About watch → Software information → Software version** — tap 7× to unlock Developer options.
2. Developer options → **ADB debugging ON**, **Debug over Wi-Fi ON**. Then look for **"Pair new device"** inside Wireless debugging — it shows an IP, port, and 6-digit code.
3. On PC:
   ```bash
   adb pair <ip>:<pairing-port> <6-digit-code>
   ```
4. Back on the watch, note the **connection** port (different from the pairing port):
   ```bash
   adb connect <ip>:<connect-port>
   adb install wear-debug.apk
   ```

### Testing the insult UI without waiting for real inactivity

Debug builds include a `TestFireActivity` that launches the full-screen `InsultActivity` directly:

```bash
adb shell am start \
  -n com.meatsack.motivator/.debug.TestFireActivity \
  --es text "GET UP." \
  --es stats "42 steps. Pathetic."
```

Ships only in debug variants (lives in `wear/src/debug/`).

---

## CI

GitHub Actions workflow at [`.github/workflows/ci.yml`](.github/workflows/ci.yml) runs on every push and PR:

- Spotless + ktlint check
- Unit tests across all three modules
- Assembles debug APKs for `wear` and `mobile`

Instrumented tests (`connectedAndroidTest`) run locally when you have an emulator paired; CI skips them to keep runs fast and hermetic.

---

## Known limitations

- **`|`-delimited wire format for sync.** Fine for ≤50 short messages with no pipes or newlines in the text. If you ever add multi-line user-generated messages, see [#7](https://github.com/xhf2/meatsackMotivator/issues/7).
- **One-way sync** (phone → watch). Votes recorded on the watch don't currently propagate back to the phone.
- **Emulator Health Services** auto-generates a synthetic step stream at ~2 steps/second, so real inactivity never triggers in the emulator. Use `TestFireActivity` for UI testing or drop `INACTIVITY_THRESHOLD_MINUTES_DEFAULT` to 1 in `shared/constants/EscalationLevel.kt` temporarily.
- **Samsung battery optimization** is aggressive. If insults stop firing on real hardware after a few hours, whitelist meatsackMotivator on both phone and watch via Settings → Battery → Background usage limits → Never sleeping apps.

Open follow-up work is tracked under [Issues](https://github.com/xhf2/meatsackMotivator/issues).

---

## License

Personal project. No OSS license granted. If you want to fork / adapt, open an issue.
