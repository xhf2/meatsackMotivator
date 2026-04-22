# meatsackMotivator v2 — "Intelligence" — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship v2 ("Intelligence") — wire context-aware tone, add scheduled triggers (BEHIND_PACE + END_OF_DAY), expand health data collection, and integrate the Anthropic Claude API for AI-generated insults that learn from the user's upvotes.

**Architecture:** Three shippable phases on one branch. Phase 1 is cheap plumbing (SyncChannel extract, tone wiring, extended Health Services subscriptions). Phase 2 introduces WorkManager for hourly pace checks and daily end-of-day reckoning. Phase 3 adds the phone-only Claude API layer (EncryptedSharedPreferences for key storage, Anthropic Kotlin SDK for calls, a "Generate Now" button that writes to Room and auto-syncs to watch).

**Tech Stack:** Kotlin, Jetpack Compose, Room, Wear Health Services, WorkManager (new in v2), `androidx.security:security-crypto` for key storage (new), `com.anthropic:anthropic-java` Kotlin SDK (new).

---

## Spec Coverage

From `docs/superpowers/specs/2026-04-18-meatsack-motivator-design.md` section "v2 — Intelligence":

| Spec bullet | Task(s) |
|---|---|
| Full health data | 3 (expand health tracker) |
| Claude API integration | 10–15 (key store, client, prompts, generator) |
| Upvoted messages as style examples | 14 (prompt builder), 18 (DAO top-upvoted query) |
| Context-aware language toggle | 2 (wire setting into selection) |
| Scheduled check-ins (hourly pace vs goal) | 4–6 (WorkManager, pace calc, schedule) |
| End-of-day reckoning | 7–8 (daily trigger, evening-hour setting) |

Also closes tracked issues from v1 review:
- #3 — Hoist sync wire constants into `shared.sync.SyncChannel` (Task 1)
- #7 — MessageSerializer should log dropped lines + validate on serialize (Task 1)
- #5 — Hoist settings defaults into `SettingsDefaults` (Task 2)

---

## File Structure

### Modified

- `shared/src/main/java/com/meatsack/shared/sync/MessageSerializer.kt` — add logging on parse failure, validate on serialize
- `shared/src/main/java/com/meatsack/shared/db/MessageDao.kt` — add `getTopUpvoted(limit: Int)` query
- `wear/src/main/java/com/meatsack/motivator/MeatsackWearService.kt` — schedule WorkManager jobs on service start
- `wear/src/main/java/com/meatsack/motivator/messages/MessageRepository.kt` — accept tone parameter from settings
- `wear/src/main/java/com/meatsack/motivator/health/StepTracker.kt` → rename to `HealthTracker.kt`; subscribe to more data types
- `mobile/src/main/java/com/meatsack/motivator/mobile/data/SettingsRepository.kt` — add `endOfDayHour` + `claudeApiKeyPresent` Flow
- `mobile/src/main/java/com/meatsack/motivator/mobile/ui/settings/SettingsScreen.kt` — add API key row, end-of-day picker, Generate Now button
- `shared/src/main/java/com/meatsack/shared/data/SeedData.kt` — seed BEHIND_PACE + END_OF_DAY messages

### New

**`:shared`**
- `shared/src/main/java/com/meatsack/shared/sync/SyncChannel.kt` — DataItem path + key constants (closes #3)

**`:wear`**
- `wear/src/main/java/com/meatsack/motivator/trigger/TriggerScheduler.kt` — wraps WorkManager scheduling
- `wear/src/main/java/com/meatsack/motivator/trigger/BehindPaceWorker.kt` — hourly check of steps vs. pace-needed
- `wear/src/main/java/com/meatsack/motivator/trigger/EndOfDayWorker.kt` — daily reckoning at configured hour

**`:mobile`**
- `mobile/src/main/java/com/meatsack/motivator/mobile/data/SettingsDefaults.kt` — hoisted defaults (closes #5)
- `mobile/src/main/java/com/meatsack/motivator/mobile/ai/ApiKeyStore.kt` — EncryptedSharedPreferences wrapper
- `mobile/src/main/java/com/meatsack/motivator/mobile/ai/Prompts.kt` — prompt template builder
- `mobile/src/main/java/com/meatsack/motivator/mobile/ai/ClaudeApiClient.kt` — thin wrapper over Anthropic SDK
- `mobile/src/main/java/com/meatsack/motivator/mobile/ai/AiMessageGenerator.kt` — end-to-end generate→persist→sync
- `mobile/src/main/java/com/meatsack/motivator/mobile/ai/GenerationResult.kt` — sealed class for UI feedback

### Tests

- `shared/src/test/java/com/meatsack/shared/sync/MessageSerializerTest.kt` — add cases for the new validation + logging behavior
- `wear/src/test/java/com/meatsack/motivator/trigger/TriggerSchedulerTest.kt` — WorkManager test harness
- `mobile/src/test/java/com/meatsack/motivator/mobile/ai/PromptsTest.kt` — prompt rendering (pure function)
- `mobile/src/test/java/com/meatsack/motivator/mobile/ai/AiMessageGeneratorTest.kt` — mock client, verify DB inserts

---

## Phase 1 — Foundations

### Task 1: Extract SyncChannel and harden MessageSerializer

**Files:**
- Create: `shared/src/main/java/com/meatsack/shared/sync/SyncChannel.kt`
- Modify: `shared/src/main/java/com/meatsack/shared/sync/MessageSerializer.kt`
- Modify: `mobile/src/main/java/com/meatsack/motivator/mobile/sync/PhoneSyncSender.kt` (remove local constants)
- Modify: `wear/src/main/java/com/meatsack/motivator/sync/WatchSyncReceiver.kt` (remove local constants)
- Modify: `shared/src/test/java/com/meatsack/shared/sync/MessageSerializerTest.kt` (add validation tests)

- [ ] **Step 1: Create `SyncChannel.kt`**

```kotlin
package com.meatsack.shared.sync

/**
 * Single source of truth for Wear Data Layer paths and keys used by
 * the phone→watch sync pipe. Duplicating these on both sides is a
 * drift footgun (closes GH #3).
 */
object SyncChannel {
    const val PATH_MESSAGES = "/messages"
    const val KEY_MESSAGE_DATA = "message_data"
    const val KEY_TIMESTAMP = "timestamp"
}
```

- [ ] **Step 2: Update `MessageSerializer.serialize` to validate invariants**

Replace the body of `serialize`:

```kotlin
fun serialize(messages: List<Message>): String =
    messages.joinToString(LINE_SEPARATOR) { m ->
        require(!m.text.contains(FIELD_SEPARATOR) && !m.text.contains(LINE_SEPARATOR)) {
            "Message text cannot contain '|' or newline; id=${m.id}"
        }
        listOf(
            m.id, m.text, m.level.value, m.triggerType.name, m.tone.name,
            m.source.name, m.votesUp, m.votesDown, m.lastShownTimestamp,
            if (m.isActive) 1 else 0,
        ).joinToString(FIELD_SEPARATOR)
    }
```

- [ ] **Step 3: Update `parseLine` to log dropped lines**

Replace `parseLine`:

```kotlin
private fun parseLine(line: String): Message? {
    val parts = line.split(FIELD_SEPARATOR)
    if (parts.size != FIELD_COUNT) {
        android.util.Log.w(
            "MessageSerializer",
            "Dropped line with ${parts.size} fields (expected $FIELD_COUNT): ${line.take(80)}",
        )
        return null
    }
    return runCatching {
        Message(
            id = parts[0].toLong(),
            text = parts[1],
            level = EscalationLevel.fromValue(parts[2].toInt()),
            triggerType = TriggerType.valueOf(parts[3]),
            tone = MessageTone.valueOf(parts[4]),
            source = MessageSource.valueOf(parts[5]),
            votesUp = parts[6].toInt(),
            votesDown = parts[7].toInt(),
            lastShownTimestamp = parts[8].toLong(),
            isActive = parts[9].toInt() != 0,
        )
    }.onFailure { error ->
        android.util.Log.w("MessageSerializer", "Dropped malformed line: ${line.take(80)}", error)
    }.getOrNull()
}
```

Note: pulling `android.util.Log` into `:shared` adds an Android dep to the module, which already has Android deps (Room). Verified OK.

- [ ] **Step 4: Replace local constants in `PhoneSyncSender`**

In `mobile/src/main/java/com/meatsack/motivator/mobile/sync/PhoneSyncSender.kt`:

- Delete the `PATH_MESSAGES` and `KEY_MESSAGE_DATA` companion constants
- Add `import com.meatsack.shared.sync.SyncChannel`
- Replace usages: `PATH_MESSAGES` → `SyncChannel.PATH_MESSAGES`, `KEY_MESSAGE_DATA` → `SyncChannel.KEY_MESSAGE_DATA`
- Replace `"timestamp"` string with `SyncChannel.KEY_TIMESTAMP`

- [ ] **Step 5: Same swap in `WatchSyncReceiver`**

Same find-and-replace in `wear/src/main/java/com/meatsack/motivator/sync/WatchSyncReceiver.kt`.

- [ ] **Step 6: Add test for serialize-time validation**

Append to `MessageSerializerTest.kt`:

```kotlin
@Test(expected = IllegalArgumentException::class)
fun `serialize rejects text containing pipe`() {
    val bad = sampleMessage(text = "text with | pipe")
    MessageSerializer.serialize(listOf(bad))
}

@Test(expected = IllegalArgumentException::class)
fun `serialize rejects text containing newline`() {
    val bad = sampleMessage(text = "text with\nnewline")
    MessageSerializer.serialize(listOf(bad))
}
```

- [ ] **Step 7: Build + run tests**

```bash
./gradlew :shared:testDebugUnitTest :mobile:compileDebugKotlin :wear:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL, 18+ tests pass in `:shared`.

- [ ] **Step 8: Commit**

```bash
git add shared/src/main/java/com/meatsack/shared/sync/ \
        mobile/src/main/java/com/meatsack/motivator/mobile/sync/PhoneSyncSender.kt \
        wear/src/main/java/com/meatsack/motivator/sync/WatchSyncReceiver.kt \
        shared/src/test/java/com/meatsack/shared/sync/MessageSerializerTest.kt
git commit -m "refactor: hoist sync constants to SyncChannel + harden serializer

Closes #3 and #7."
```

---

### Task 2: Wire context-aware tone + hoist settings defaults

**Files:**
- Create: `mobile/src/main/java/com/meatsack/motivator/mobile/data/SettingsDefaults.kt`
- Modify: `mobile/src/main/java/com/meatsack/motivator/mobile/data/SettingsRepository.kt`
- Modify: `mobile/src/main/java/com/meatsack/motivator/mobile/ui/settings/SettingsViewModel.kt`
- Modify: `wear/src/main/java/com/meatsack/motivator/messages/MessageRepository.kt`
- Modify: `wear/src/main/java/com/meatsack/motivator/MeatsackWearService.kt`

- [ ] **Step 1: Create `SettingsDefaults.kt`**

```kotlin
package com.meatsack.motivator.mobile.data

object SettingsDefaults {
    const val DAILY_STEP_GOAL = 10_000
    const val INACTIVITY_THRESHOLD_MIN = 30
    const val ACTIVE_HOURS_START = 7
    const val ACTIVE_HOURS_END = 22
    const val QUIET_HOURS_START = 22
    const val QUIET_HOURS_END = 7
    const val CONTEXT_AWARE_ENABLED = false
    const val END_OF_DAY_HOUR = 21 // default 9pm
}
```

- [ ] **Step 2: Update `SettingsRepository` to use defaults + add endOfDayHour**

Replace the defaults inline with the new object and add `endOfDayHour`:

```kotlin
val dailyStepGoal: Flow<Int> = context.dataStore.data.map {
    it[DAILY_STEP_GOAL] ?: SettingsDefaults.DAILY_STEP_GOAL
}
val inactivityThreshold: Flow<Int> = context.dataStore.data.map {
    it[INACTIVITY_THRESHOLD] ?: SettingsDefaults.INACTIVITY_THRESHOLD_MIN
}
val activeHoursStart: Flow<Int> = context.dataStore.data.map {
    it[ACTIVE_HOURS_START] ?: SettingsDefaults.ACTIVE_HOURS_START
}
val activeHoursEnd: Flow<Int> = context.dataStore.data.map {
    it[ACTIVE_HOURS_END] ?: SettingsDefaults.ACTIVE_HOURS_END
}
val quietHoursStart: Flow<Int> = context.dataStore.data.map {
    it[QUIET_HOURS_START] ?: SettingsDefaults.QUIET_HOURS_START
}
val quietHoursEnd: Flow<Int> = context.dataStore.data.map {
    it[QUIET_HOURS_END] ?: SettingsDefaults.QUIET_HOURS_END
}
val contextAwareEnabled: Flow<Boolean> = context.dataStore.data.map {
    it[CONTEXT_AWARE_ENABLED] ?: SettingsDefaults.CONTEXT_AWARE_ENABLED
}
val endOfDayHour: Flow<Int> = context.dataStore.data.map {
    it[END_OF_DAY_HOUR] ?: SettingsDefaults.END_OF_DAY_HOUR
}

suspend fun setEndOfDayHour(hour: Int) {
    require(hour in 0..23) { "Hour must be 0..23" }
    context.dataStore.edit { it[END_OF_DAY_HOUR] = hour }
}
```

Add the key:
```kotlin
val END_OF_DAY_HOUR = intPreferencesKey("end_of_day_hour")
```

- [ ] **Step 3: Update `SettingsViewModel` to expose endOfDayHour + use defaults**

```kotlin
val endOfDayHour = repo.endOfDayHour.stateIn(
    viewModelScope, SharingStarted.WhileSubscribed(), SettingsDefaults.END_OF_DAY_HOUR,
)

fun updateEndOfDayHour(hour: Int) = viewModelScope.launch { repo.setEndOfDayHour(hour) }
```

Replace all the hardcoded `stateIn(..., 10_000)` etc. with `SettingsDefaults.*`.

- [ ] **Step 4: Change `MessageRepository.selectMessage` to accept tone from caller**

The signature already accepts `tone: MessageTone`. Verify it's respected by the DAO query — it already filters by tone. No change to `MessageRepository` itself.

- [ ] **Step 5: Create a `ToneResolver` helper on watch**

Create `wear/src/main/java/com/meatsack/motivator/messages/ToneResolver.kt`:

```kotlin
package com.meatsack.motivator.messages

import com.meatsack.shared.constants.MessageTone
import java.util.Calendar

/**
 * Picks the right tone given the user's context-aware preference and
 * the current time-of-day. When the toggle is on and we're inside the
 * user's "active hours" window (which v1 treats as a proxy for work
 * hours), we use WORK_SAFE; otherwise FULL_SEND.
 */
object ToneResolver {
    fun resolve(
        contextAwareEnabled: Boolean,
        activeHoursStart: Int,
        activeHoursEnd: Int,
        hour: Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY),
    ): MessageTone {
        if (!contextAwareEnabled) return MessageTone.FULL_SEND
        val inActiveWindow = if (activeHoursStart <= activeHoursEnd) {
            hour in activeHoursStart until activeHoursEnd
        } else {
            hour >= activeHoursStart || hour < activeHoursEnd
        }
        return if (inActiveWindow) MessageTone.WORK_SAFE else MessageTone.FULL_SEND
    }
}
```

- [ ] **Step 6: Add `ToneResolver` test**

`wear/src/test/java/com/meatsack/motivator/messages/ToneResolverTest.kt`:

```kotlin
package com.meatsack.motivator.messages

import com.meatsack.shared.constants.MessageTone
import org.junit.Assert.assertEquals
import org.junit.Test

class ToneResolverTest {
    @Test fun `full send when toggle off, regardless of time`() {
        assertEquals(MessageTone.FULL_SEND, ToneResolver.resolve(false, 7, 22, hour = 10))
        assertEquals(MessageTone.FULL_SEND, ToneResolver.resolve(false, 7, 22, hour = 23))
    }

    @Test fun `work safe during active hours when toggle on`() {
        assertEquals(MessageTone.WORK_SAFE, ToneResolver.resolve(true, 7, 22, hour = 10))
        assertEquals(MessageTone.WORK_SAFE, ToneResolver.resolve(true, 7, 22, hour = 21))
    }

    @Test fun `full send outside active hours when toggle on`() {
        assertEquals(MessageTone.FULL_SEND, ToneResolver.resolve(true, 7, 22, hour = 6))
        assertEquals(MessageTone.FULL_SEND, ToneResolver.resolve(true, 7, 22, hour = 23))
    }

    @Test fun `wraparound window like 22 to 6 treats midnight correctly`() {
        // Active window 22-06 (night shift); toggle on
        assertEquals(MessageTone.WORK_SAFE, ToneResolver.resolve(true, 22, 6, hour = 23))
        assertEquals(MessageTone.WORK_SAFE, ToneResolver.resolve(true, 22, 6, hour = 3))
        assertEquals(MessageTone.FULL_SEND, ToneResolver.resolve(true, 22, 6, hour = 12))
    }
}
```

- [ ] **Step 7: Wire tone into `MeatsackWearService.checkInactivity`**

The service currently hardcodes `val tone = MessageTone.FULL_SEND`. The watch doesn't have DataStore access (settings live on phone). For v2, we'll also push settings over the Data Layer alongside messages — but that's infrastructure for Task 16.

For v2 Phase 1, read settings from a new lightweight local cache on watch:

Create `wear/src/main/java/com/meatsack/motivator/settings/WatchSettingsCache.kt`:

```kotlin
package com.meatsack.motivator.settings

import android.content.Context

/**
 * Cache of user settings mirrored from the phone. Populated by a
 * future settings-sync DataItem (Task 16); reads default values until
 * the first sync.
 */
class WatchSettingsCache(context: Context) {
    private val prefs = context.getSharedPreferences("watch_settings", Context.MODE_PRIVATE)

    var contextAwareEnabled: Boolean
        get() = prefs.getBoolean(KEY_CONTEXT_AWARE, false)
        set(value) = prefs.edit().putBoolean(KEY_CONTEXT_AWARE, value).apply()

    var activeHoursStart: Int
        get() = prefs.getInt(KEY_ACTIVE_START, 7)
        set(value) = prefs.edit().putInt(KEY_ACTIVE_START, value).apply()

    var activeHoursEnd: Int
        get() = prefs.getInt(KEY_ACTIVE_END, 22)
        set(value) = prefs.edit().putInt(KEY_ACTIVE_END, value).apply()

    companion object {
        private const val KEY_CONTEXT_AWARE = "context_aware_enabled"
        private const val KEY_ACTIVE_START = "active_hours_start"
        private const val KEY_ACTIVE_END = "active_hours_end"
    }
}
```

Update `MeatsackWearService.onCreate` to hold a reference:

```kotlin
private lateinit var settings: WatchSettingsCache

override fun onCreate() {
    super.onCreate()
    // ... existing setup ...
    settings = WatchSettingsCache(applicationContext)
}
```

And in `checkInactivity`, replace the hardcoded tone:

```kotlin
val tone = ToneResolver.resolve(
    contextAwareEnabled = settings.contextAwareEnabled,
    activeHoursStart = settings.activeHoursStart,
    activeHoursEnd = settings.activeHoursEnd,
)
```

- [ ] **Step 8: Build + run tests**

```bash
./gradlew :shared:testDebugUnitTest :wear:testDebugUnitTest :mobile:testDebugUnitTest :wear:assembleDebug :mobile:assembleDebug
```

Expected: BUILD SUCCESSFUL, ToneResolverTest (4 cases) passes, nothing regressed.

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "feat: wire context-aware tone via ToneResolver + hoist settings defaults

Watch reads from a local WatchSettingsCache (phone→watch settings sync
lands in Task 16). Closes #5."
```

---

### Task 3: Expand StepTracker → HealthTracker (multiple data types)

**Files:**
- Rename: `wear/src/main/java/com/meatsack/motivator/health/StepTracker.kt` → `HealthTracker.kt` (also rename the class)
- Modify: `wear/src/main/java/com/meatsack/motivator/MeatsackWearService.kt` (update import/type)

- [ ] **Step 1: Rename the file and class**

```bash
git mv wear/src/main/java/com/meatsack/motivator/health/StepTracker.kt \
       wear/src/main/java/com/meatsack/motivator/health/HealthTracker.kt
```

Rename the class and update `MeatsackWearService` accordingly. Keep the existing step-tracking logic intact.

- [ ] **Step 2: Expand passive data subscriptions**

Replace `HealthTracker.startTracking()`:

```kotlin
fun startTracking() {
    val config = PassiveListenerConfig.builder()
        .setDataTypes(
            setOf(
                DataType.STEPS_DAILY,
                DataType.FLOORS_DAILY,
                DataType.CALORIES_DAILY,
                DataType.HEART_RATE_BPM,
            ),
        )
        .build()
    val future = client.setPassiveListenerCallback(config, callback)
    future.addListener(
        {
            runCatching { future.get() }.onFailure {
                Log.e(TAG, "Failed to register passive listener", it)
            }
        },
        Runnable::run,
    )
    Log.d(TAG, "Health tracking started (steps, floors, calories, HR)")
}
```

Note the `future.addListener` pattern closes GH #9 (registration failures are now logged).

- [ ] **Step 3: Expose additional fields**

Add StateFlows for the new metrics:

```kotlin
private val _floorsToday = MutableStateFlow(0)
val floorsToday: StateFlow<Int> = _floorsToday.asStateFlow()

private val _caloriesToday = MutableStateFlow(0)
val caloriesToday: StateFlow<Int> = _caloriesToday.asStateFlow()

private val _lastHeartRate = MutableStateFlow<Int?>(null)
val lastHeartRate: StateFlow<Int?> = _lastHeartRate.asStateFlow()
```

Update the callback to populate them:

```kotlin
private val callback = object : PassiveListenerCallback {
    override fun onNewDataPointsReceived(dataPoints: DataPointContainer) {
        try {
            dataPoints.getData(DataType.STEPS_DAILY).lastOrNull()?.let {
                val count = it.value.toInt()
                _totalStepsToday.value = count
                trackMovement(count)
            }
            dataPoints.getData(DataType.FLOORS_DAILY).lastOrNull()?.let {
                _floorsToday.value = it.value.toInt()
            }
            dataPoints.getData(DataType.CALORIES_DAILY).lastOrNull()?.let {
                _caloriesToday.value = it.value.toInt()
            }
            dataPoints.getData(DataType.HEART_RATE_BPM).lastOrNull()?.let {
                _lastHeartRate.value = it.value.toInt()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to process health data point", t)
        }
    }
}
```

- [ ] **Step 4: Add `BODY_SENSORS_BACKGROUND` permission for HR on API 34+**

In `wear/src/main/AndroidManifest.xml`, add:

```xml
<uses-permission android:name="android.permission.BODY_SENSORS_BACKGROUND"
    android:maxSdkVersion="32" />
```

(Heart rate in PASSIVE mode works without this on API 33+ via Health Services' mediation; the permission is only needed when reading the raw sensor.)

- [ ] **Step 5: Build**

```bash
./gradlew :wear:assembleDebug
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat: expand StepTracker to HealthTracker — floors, calories, HR

Closes #9 (logs health service registration failures)."
```

---

## Phase 2 — Scheduled Triggers

### Task 4: Add WorkManager + TriggerScheduler scaffold

**Files:**
- Modify: `wear/build.gradle.kts` (add WorkManager dep)
- Modify: `gradle/libs.versions.toml`
- Create: `wear/src/main/java/com/meatsack/motivator/trigger/TriggerScheduler.kt`

- [ ] **Step 1: Add WorkManager to the version catalog**

In `gradle/libs.versions.toml`, under `[versions]`:

```toml
workManager = "2.10.0"
```

Under `[libraries]`:

```toml
androidx-work-runtime-ktx = { group = "androidx.work", name = "work-runtime-ktx", version.ref = "workManager" }
androidx-work-testing = { group = "androidx.work", name = "work-testing", version.ref = "workManager" }
```

- [ ] **Step 2: Add to `wear/build.gradle.kts` dependencies**

```kotlin
implementation(libs.androidx.work.runtime.ktx)
testImplementation(libs.androidx.work.testing)
```

- [ ] **Step 3: Create `TriggerScheduler.kt`**

```kotlin
package com.meatsack.motivator.trigger

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Owns the WorkManager scheduling for the two time-driven insult
 * triggers added in v2: an hourly pace-vs-goal check and a once-daily
 * end-of-day reckoning.
 */
class TriggerScheduler(context: Context) {
    private val wm = WorkManager.getInstance(context)

    fun scheduleHourlyPaceCheck() {
        val request = PeriodicWorkRequestBuilder<BehindPaceWorker>(1, TimeUnit.HOURS)
            .build()
        wm.enqueueUniquePeriodicWork(UNIQUE_PACE, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun scheduleEndOfDay(hour: Int) {
        val delayMs = millisUntilNextHour(hour)
        val request = OneTimeWorkRequestBuilder<EndOfDayWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .build()
        wm.enqueueUniqueWork(UNIQUE_END_OF_DAY, ExistingWorkPolicy.REPLACE, request)
    }

    fun cancelAll() {
        wm.cancelUniqueWork(UNIQUE_PACE)
        wm.cancelUniqueWork(UNIQUE_END_OF_DAY)
    }

    internal fun millisUntilNextHour(hour: Int, now: Calendar = Calendar.getInstance()): Long {
        val target = (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (before(now)) add(Calendar.DAY_OF_MONTH, 1)
        }
        return target.timeInMillis - now.timeInMillis
    }

    companion object {
        const val UNIQUE_PACE = "meatsack_pace_check"
        const val UNIQUE_END_OF_DAY = "meatsack_end_of_day"
    }
}
```

- [ ] **Step 4: Build**

```bash
./gradlew :wear:compileDebugKotlin
```

Expected: `BehindPaceWorker` + `EndOfDayWorker` unresolved references (they don't exist yet — Tasks 5 and 7 create them).

- [ ] **Step 5: Stub the two workers so compile succeeds**

Temporarily stub both:

`wear/src/main/java/com/meatsack/motivator/trigger/BehindPaceWorker.kt`:

```kotlin
package com.meatsack.motivator.trigger

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class BehindPaceWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = Result.success() // filled in Task 5
}
```

`wear/src/main/java/com/meatsack/motivator/trigger/EndOfDayWorker.kt`:

```kotlin
package com.meatsack.motivator.trigger

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class EndOfDayWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = Result.success() // filled in Task 7
}
```

- [ ] **Step 6: Build to confirm compile**

```bash
./gradlew :wear:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat(trigger): add WorkManager + TriggerScheduler scaffolding for v2"
```

---

### Task 5: Implement pace calculation + `BehindPaceWorker`

**Files:**
- Create: `wear/src/main/java/com/meatsack/motivator/trigger/PaceCalculator.kt`
- Create: `wear/src/test/java/com/meatsack/motivator/trigger/PaceCalculatorTest.kt`
- Modify: `wear/src/main/java/com/meatsack/motivator/trigger/BehindPaceWorker.kt`

- [ ] **Step 1: Create `PaceCalculator.kt`**

```kotlin
package com.meatsack.motivator.trigger

import com.meatsack.shared.constants.EscalationLevel

/**
 * Computes how behind (or ahead) the user is on their step goal given
 * the current time of day. Used by BehindPaceWorker to decide whether
 * to fire a BEHIND_PACE insult and at what escalation level.
 */
object PaceCalculator {

    /**
     * Linear pace expectation. Between activeStart and activeEnd hours,
     * the user is "expected" to accumulate steps proportionally. Outside
     * that window we don't expect progress.
     */
    fun expectedStepsByHour(
        hour: Int,
        minute: Int,
        goal: Int,
        activeStart: Int,
        activeEnd: Int,
    ): Int {
        if (hour < activeStart) return 0
        if (hour >= activeEnd) return goal
        val hoursInDay = activeEnd - activeStart
        val elapsedHours = (hour - activeStart) + minute / 60.0
        return (goal * (elapsedHours / hoursInDay)).toInt().coerceAtLeast(0)
    }

    /**
     * Returns null if on pace or ahead; otherwise returns an escalation
     * level matching how far behind the user is.
     */
    fun escalationIfBehind(
        currentSteps: Int,
        expectedSteps: Int,
    ): EscalationLevel? {
        if (currentSteps >= expectedSteps) return null
        val shortfallPct = 1.0 - (currentSteps.toDouble() / expectedSteps.coerceAtLeast(1))
        return when {
            shortfallPct > 0.75 -> EscalationLevel.EXISTENTIAL
            shortfallPct > 0.50 -> EscalationLevel.NUCLEAR
            shortfallPct > 0.25 -> EscalationLevel.SAVAGE
            else -> EscalationLevel.AGGRESSIVE
        }
    }
}
```

- [ ] **Step 2: Add tests**

```kotlin
package com.meatsack.motivator.trigger

import com.meatsack.shared.constants.EscalationLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PaceCalculatorTest {

    @Test fun `expected zero before active start`() {
        assertEquals(0, PaceCalculator.expectedStepsByHour(6, 0, 10_000, 7, 22))
    }

    @Test fun `expected full goal after active end`() {
        assertEquals(10_000, PaceCalculator.expectedStepsByHour(23, 0, 10_000, 7, 22))
    }

    @Test fun `linear progression mid-day`() {
        // 15h active window, goal 10000 → ~667 steps/hour
        // At 14:30, 7.5 hours elapsed → expected ~5000
        val expected = PaceCalculator.expectedStepsByHour(14, 30, 10_000, 7, 22)
        assertEquals(5000, expected)
    }

    @Test fun `no escalation when on pace`() {
        assertNull(PaceCalculator.escalationIfBehind(5000, 5000))
        assertNull(PaceCalculator.escalationIfBehind(6000, 5000))
    }

    @Test fun `aggressive when under 25 pct behind`() {
        assertEquals(EscalationLevel.AGGRESSIVE, PaceCalculator.escalationIfBehind(4000, 5000))
    }

    @Test fun `savage when 26-50 pct behind`() {
        assertEquals(EscalationLevel.SAVAGE, PaceCalculator.escalationIfBehind(2400, 5000))
    }

    @Test fun `nuclear when 51-75 pct behind`() {
        assertEquals(EscalationLevel.NUCLEAR, PaceCalculator.escalationIfBehind(1200, 5000))
    }

    @Test fun `existential when over 75 pct behind`() {
        assertEquals(EscalationLevel.EXISTENTIAL, PaceCalculator.escalationIfBehind(500, 5000))
    }
}
```

- [ ] **Step 3: Implement `BehindPaceWorker.doWork()`**

```kotlin
package com.meatsack.motivator.trigger

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.meatsack.motivator.messages.MessageRepository
import com.meatsack.motivator.messages.ToneResolver
import com.meatsack.motivator.notification.InsultNotificationService
import com.meatsack.motivator.settings.WatchSettingsCache
import com.meatsack.shared.constants.TriggerType
import com.meatsack.shared.db.AppDatabase
import java.util.Calendar

class BehindPaceWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val settings = WatchSettingsCache(ctx)
        val db = AppDatabase.getDatabase(ctx)
        val repo = MessageRepository(db.messageDao())
        val notifier = InsultNotificationService(ctx)

        // v2 assumption: we don't have access to current step count here
        // (HealthTracker lives in the foreground service). For v2 we query
        // the most recent cached value via a SharedPreferences hand-off.
        val currentSteps = ctx.getSharedPreferences("watch_health", Context.MODE_PRIVATE)
            .getInt("steps_today", 0)
        val goal = settings.dailyStepGoal
        val now = Calendar.getInstance()

        val expected = PaceCalculator.expectedStepsByHour(
            now.get(Calendar.HOUR_OF_DAY),
            now.get(Calendar.MINUTE),
            goal,
            settings.activeHoursStart,
            settings.activeHoursEnd,
        )
        val level = PaceCalculator.escalationIfBehind(currentSteps, expected) ?: run {
            Log.d(TAG, "On pace ($currentSteps / $expected). Skipping.")
            return Result.success()
        }

        val tone = ToneResolver.resolve(
            settings.contextAwareEnabled,
            settings.activeHoursStart,
            settings.activeHoursEnd,
        )
        val message = repo.selectMessage(level, TriggerType.BEHIND_PACE, tone) ?: run {
            Log.w(TAG, "No message for level=$level tone=$tone — falling back to inactivity pool")
            repo.selectMessage(level, TriggerType.INACTIVITY, tone)
        } ?: return Result.success()

        val stats = "$currentSteps / $expected steps. Behind pace."
        notifier.deliverInsult(message, stats)
        return Result.success()
    }

    companion object { private const val TAG = "BehindPaceWorker" }
}
```

- [ ] **Step 4: Expose steps to SharedPreferences from HealthTracker**

Inside the `callback.onNewDataPointsReceived` in `HealthTracker.kt`, after updating `_totalStepsToday.value`, also persist:

```kotlin
context.getSharedPreferences("watch_health", Context.MODE_PRIVATE)
    .edit().putInt("steps_today", count).apply()
```

Add `Context` to `HealthTracker`'s constructor if not already there (it is).

Also add `dailyStepGoal` to `WatchSettingsCache` with a default of 10_000:

```kotlin
var dailyStepGoal: Int
    get() = prefs.getInt(KEY_GOAL, 10_000)
    set(value) = prefs.edit().putInt(KEY_GOAL, value).apply()

companion object {
    // ... existing ...
    private const val KEY_GOAL = "daily_step_goal"
}
```

- [ ] **Step 5: Run tests**

```bash
./gradlew :wear:testDebugUnitTest
```

Expected: 8 new `PaceCalculatorTest` cases pass, existing tests unaffected.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat(trigger): implement BehindPaceWorker + PaceCalculator"
```

---

### Task 6: Schedule the hourly pace check on service start

**Files:**
- Modify: `wear/src/main/java/com/meatsack/motivator/MeatsackWearService.kt`

- [ ] **Step 1: Schedule on service start**

In `onStartCommand`, after `startForeground` and `startTracking`:

```kotlin
TriggerScheduler(this).scheduleHourlyPaceCheck()
```

- [ ] **Step 2: Cancel on destroy**

In `onDestroy`:

```kotlin
TriggerScheduler(this).cancelAll()
```

- [ ] **Step 3: Build + install**

```bash
./gradlew :wear:assembleDebug
adb -s <watch> install -r wear/build/outputs/apk/debug/wear-debug.apk
```

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "feat(trigger): schedule hourly pace check from wear service startup"
```

---

### Task 7: Implement `EndOfDayWorker` + reschedule loop

**Files:**
- Modify: `wear/src/main/java/com/meatsack/motivator/trigger/EndOfDayWorker.kt`
- Modify: `wear/src/main/java/com/meatsack/motivator/MeatsackWearService.kt`

- [ ] **Step 1: Implement `EndOfDayWorker`**

```kotlin
package com.meatsack.motivator.trigger

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.meatsack.motivator.messages.MessageRepository
import com.meatsack.motivator.messages.ToneResolver
import com.meatsack.motivator.notification.InsultNotificationService
import com.meatsack.motivator.settings.WatchSettingsCache
import com.meatsack.shared.constants.EscalationLevel
import com.meatsack.shared.constants.TriggerType
import com.meatsack.shared.db.AppDatabase

class EndOfDayWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val settings = WatchSettingsCache(ctx)
        val db = AppDatabase.getDatabase(ctx)
        val repo = MessageRepository(db.messageDao())
        val notifier = InsultNotificationService(ctx)

        val currentSteps = ctx.getSharedPreferences("watch_health", Context.MODE_PRIVATE)
            .getInt("steps_today", 0)
        val goal = settings.dailyStepGoal

        // Level depends on outcome. Missed by a lot → nuclear. Near miss → savage.
        val level = when {
            currentSteps >= goal -> return Result.success() // hit the goal; no reckoning
            currentSteps >= goal * 0.75 -> EscalationLevel.SAVAGE
            currentSteps >= goal * 0.5 -> EscalationLevel.NUCLEAR
            else -> EscalationLevel.EXISTENTIAL
        }

        val tone = ToneResolver.resolve(
            settings.contextAwareEnabled,
            settings.activeHoursStart,
            settings.activeHoursEnd,
        )
        val message = repo.selectMessage(level, TriggerType.END_OF_DAY, tone)
            ?: repo.selectMessage(level, TriggerType.INACTIVITY, tone)
            ?: return Result.success()

        val stats = "$currentSteps / $goal. Day's over."
        notifier.deliverInsult(message, stats)

        // Reschedule for tomorrow
        TriggerScheduler(ctx).scheduleEndOfDay(settings.endOfDayHour)
        return Result.success()
    }
}
```

- [ ] **Step 2: Add `endOfDayHour` to `WatchSettingsCache`**

```kotlin
var endOfDayHour: Int
    get() = prefs.getInt(KEY_END_OF_DAY, 21)
    set(value) = prefs.edit().putInt(KEY_END_OF_DAY, value).apply()

// companion object:
private const val KEY_END_OF_DAY = "end_of_day_hour"
```

- [ ] **Step 3: Schedule end-of-day on service start**

In `MeatsackWearService.onStartCommand`:

```kotlin
TriggerScheduler(this).scheduleEndOfDay(settings.endOfDayHour)
```

- [ ] **Step 4: Build**

```bash
./gradlew :wear:assembleDebug
```

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat(trigger): implement EndOfDayWorker with self-rescheduling"
```

---

### Task 8: Seed BEHIND_PACE + END_OF_DAY messages

**Files:**
- Modify: `shared/src/main/java/com/meatsack/shared/data/SeedData.kt`

- [ ] **Step 1: Add new message lists**

Append to `SeedData`:

```kotlin
private val behindPaceLevel1Full = listOf(
    msg("You're behind. Stop coasting.", EscalationLevel.AGGRESSIVE, TriggerType.BEHIND_PACE),
    msg("At this rate you'll hit your goal sometime next week.", EscalationLevel.AGGRESSIVE, TriggerType.BEHIND_PACE),
    msg("Every hour you fall behind is an hour you owe tomorrow-you.", EscalationLevel.AGGRESSIVE, TriggerType.BEHIND_PACE),
)

private val behindPaceLevel2Full = listOf(
    msg("You're not even halfway. The day is half over. Math.", EscalationLevel.SAVAGE, TriggerType.BEHIND_PACE),
    msg("You chose comfort over the goal you set. You weak, soft meatsack.", EscalationLevel.SAVAGE, TriggerType.BEHIND_PACE),
)

private val behindPaceLevel3Full = listOf(
    msg("Quarter of the steps. Triple the excuses. You're choosing to fail.", EscalationLevel.NUCLEAR, TriggerType.BEHIND_PACE),
    msg("Your goal is not decorative. You set it. You're failing it. LIVE with that.", EscalationLevel.NUCLEAR, TriggerType.BEHIND_PACE),
)

private val behindPaceLevel4Full = listOf(
    msg("Your step counter is embarrassed. Your ancestors are embarrassed. I am embarrassed.", EscalationLevel.EXISTENTIAL, TriggerType.BEHIND_PACE),
)

private val endOfDayLevel2Full = listOf(
    msg("Close. Not close enough. Tomorrow you work harder.", EscalationLevel.SAVAGE, TriggerType.END_OF_DAY),
    msg("You got within shouting distance. Shouting isn't reaching. Try again tomorrow.", EscalationLevel.SAVAGE, TriggerType.END_OF_DAY),
)

private val endOfDayLevel3Full = listOf(
    msg("Half a goal. Half effort. Pathetic symmetry.", EscalationLevel.NUCLEAR, TriggerType.END_OF_DAY),
)

private val endOfDayLevel4Full = listOf(
    msg("You chose to lose today. Sleep on that.", EscalationLevel.EXISTENTIAL, TriggerType.END_OF_DAY),
    msg("A whole day. Wasted. And for what?", EscalationLevel.EXISTENTIAL, TriggerType.END_OF_DAY),
)
```

And add matching WorkSafe variants (shorter lists — 1 each) to keep the context-aware pool non-empty.

- [ ] **Step 2: Include them in `getPreWrittenMessages`**

```kotlin
addAll(behindPaceLevel1Full)
addAll(behindPaceLevel2Full)
addAll(behindPaceLevel3Full)
addAll(behindPaceLevel4Full)
addAll(endOfDayLevel2Full)
addAll(endOfDayLevel3Full)
addAll(endOfDayLevel4Full)
// plus WorkSafe variants
```

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "feat(seed): add BEHIND_PACE and END_OF_DAY message pools

Addresses the 'seed distribution audit' concern from #8 for the two
new trigger types — each level × trigger bucket has at least 1 message."
```

---

## Phase 3 — Claude API Integration

### Task 9: Add Anthropic SDK + security-crypto dependencies

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `mobile/build.gradle.kts`

- [ ] **Step 1: Version catalog entries**

```toml
# versions:
anthropic = "1.0.0"
securityCrypto = "1.1.0-alpha06"

# libraries:
anthropic-java = { group = "com.anthropic", name = "anthropic-java", version.ref = "anthropic" }
androidx-security-crypto = { group = "androidx.security", name = "security-crypto", version.ref = "securityCrypto" }
```

- [ ] **Step 2: Add to mobile dependencies**

In `mobile/build.gradle.kts`:

```kotlin
implementation(libs.anthropic.java)
implementation(libs.androidx.security.crypto)
```

- [ ] **Step 3: Add INTERNET permission**

In `mobile/src/main/AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.INTERNET" />
```

- [ ] **Step 4: Build**

```bash
./gradlew :mobile:assembleDebug
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "chore: add anthropic-java SDK and security-crypto deps for Claude API"
```

---

### Task 10: `ApiKeyStore` — EncryptedSharedPreferences wrapper

**Files:**
- Create: `mobile/src/main/java/com/meatsack/motivator/mobile/ai/ApiKeyStore.kt`

- [ ] **Step 1: Create the file**

```kotlin
package com.meatsack.motivator.mobile.ai

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Device-backed encrypted store for the user's Anthropic API key.
 * Key bytes live in the Android Keystore; ciphertext in prefs.
 */
class ApiKeyStore(context: Context) {
    private val prefs: SharedPreferences = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "meatsack_secure",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun save(apiKey: String) {
        prefs.edit().putString(KEY, apiKey.trim()).apply()
    }

    fun read(): String? = prefs.getString(KEY, null)?.takeIf { it.isNotBlank() }

    fun hasKey(): Boolean = read() != null

    fun clear() {
        prefs.edit().remove(KEY).apply()
    }

    companion object { private const val KEY = "anthropic_api_key" }
}
```

- [ ] **Step 2: Commit**

```bash
git add -A
git commit -m "feat(ai): add ApiKeyStore backed by EncryptedSharedPreferences"
```

---

### Task 11: `Prompts.kt` — prompt builder

**Files:**
- Create: `mobile/src/main/java/com/meatsack/motivator/mobile/ai/Prompts.kt`
- Create: `mobile/src/test/java/com/meatsack/motivator/mobile/ai/PromptsTest.kt`

- [ ] **Step 1: Create `Prompts.kt`**

```kotlin
package com.meatsack.motivator.mobile.ai

import com.meatsack.shared.constants.EscalationLevel
import com.meatsack.shared.constants.MessageTone
import com.meatsack.shared.constants.TriggerType

/**
 * Builds the user-prompt string sent to Claude. The system-level style
 * ("David Goggins as angry drill sergeant") is baked in; the per-call
 * variables are the trigger context and the user's top-upvoted messages
 * as style examples.
 */
object Prompts {

    fun buildUserPrompt(
        currentSteps: Int,
        hourOfDay: Int,
        level: EscalationLevel,
        trigger: TriggerType,
        tone: MessageTone,
        topVoted: List<String>,
        count: Int = 10,
    ): String {
        val toneLine = when (tone) {
            MessageTone.FULL_SEND -> "Full send. Swear. Be brutal."
            MessageTone.WORK_SAFE -> "Keep it clean. No profanity. Still savage."
        }
        val triggerLine = when (trigger) {
            TriggerType.INACTIVITY -> "They have been sitting still too long."
            TriggerType.BEHIND_PACE -> "They are behind their step pace for the day."
            TriggerType.END_OF_DAY -> "The day is ending and they missed the goal."
            TriggerType.NO_WORKOUT -> "They didn't work out today."
        }
        val timeLabel = if (hourOfDay < 12) "${hourOfDay}am" else "${hourOfDay - 12}pm"
        val examples = topVoted.take(5).ifEmpty { listOf("(no examples yet)") }
            .joinToString("\n") { "- $it" }

        return """
            Generate $count short (1-2 sentence, max 20 words each) motivational insults.
            Style: David Goggins as an angry, disgusted drill sergeant.
            $triggerLine Steps so far: $currentSteps. Time: $timeLabel.
            Level: ${level.name} — crank the venom accordingly.
            Tone: $toneLine
            Use creative medical/anatomical compound insults like 'sarcopenic motherfucker',
            'osteopenic jello mold', 'arthritic waste of a skeleton', 'osteoporotic coward'.
            Never use 'fat' as an insult.

            Here are messages this user loved — match this voice:
            $examples

            Return ONE message per line. No numbering, no bullets, no quotes.
        """.trimIndent()
    }

    const val SYSTEM_PROMPT =
        "You are a pitiless motivational drill sergeant. No softening, no hedging. " +
        "Every response is raw motivational insults, one per line. No preamble."
}
```

- [ ] **Step 2: Create `PromptsTest.kt`**

```kotlin
package com.meatsack.motivator.mobile.ai

import com.meatsack.shared.constants.EscalationLevel
import com.meatsack.shared.constants.MessageTone
import com.meatsack.shared.constants.TriggerType
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptsTest {

    @Test fun `includes top voted examples`() {
        val prompt = Prompts.buildUserPrompt(
            currentSteps = 500, hourOfDay = 14,
            level = EscalationLevel.SAVAGE,
            trigger = TriggerType.INACTIVITY,
            tone = MessageTone.FULL_SEND,
            topVoted = listOf("GET UP.", "Your chair knows you."),
        )
        assertTrue(prompt.contains("GET UP."))
        assertTrue(prompt.contains("Your chair knows you."))
    }

    @Test fun `work safe tone changes language line`() {
        val prompt = Prompts.buildUserPrompt(
            0, 10, EscalationLevel.AGGRESSIVE, TriggerType.INACTIVITY,
            MessageTone.WORK_SAFE, emptyList(),
        )
        assertTrue(prompt.contains("Keep it clean"))
    }

    @Test fun `behind pace trigger changes trigger line`() {
        val prompt = Prompts.buildUserPrompt(
            500, 14, EscalationLevel.SAVAGE, TriggerType.BEHIND_PACE,
            MessageTone.FULL_SEND, emptyList(),
        )
        assertTrue(prompt.contains("behind their step pace"))
    }

    @Test fun `handles empty examples gracefully`() {
        val prompt = Prompts.buildUserPrompt(
            0, 10, EscalationLevel.AGGRESSIVE, TriggerType.INACTIVITY,
            MessageTone.FULL_SEND, emptyList(),
        )
        assertTrue(prompt.contains("no examples yet"))
    }
}
```

- [ ] **Step 3: Run tests**

```bash
./gradlew :mobile:testDebugUnitTest
```

Expected: 4 Prompts tests pass.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "feat(ai): add Prompts builder with tone/trigger variants and upvote examples"
```

---

### Task 12: `ClaudeApiClient` — thin wrapper over Anthropic SDK

**Files:**
- Create: `mobile/src/main/java/com/meatsack/motivator/mobile/ai/ClaudeApiClient.kt`
- Create: `mobile/src/main/java/com/meatsack/motivator/mobile/ai/GenerationResult.kt`

- [ ] **Step 1: Create `GenerationResult.kt`**

```kotlin
package com.meatsack.motivator.mobile.ai

sealed class GenerationResult {
    data class Success(val messages: List<String>) : GenerationResult()
    data object NoApiKey : GenerationResult()
    data class HttpError(val status: Int, val body: String?) : GenerationResult()
    data class Failed(val error: Throwable) : GenerationResult()
}
```

- [ ] **Step 2: Create `ClaudeApiClient.kt`**

```kotlin
package com.meatsack.motivator.mobile.ai

import android.util.Log
import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.Model
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ClaudeApiClient(private val apiKeyStore: ApiKeyStore) {

    suspend fun generate(
        systemPrompt: String,
        userPrompt: String,
        model: String = "claude-3-5-haiku-latest",
        maxTokens: Int = 1024,
    ): GenerationResult {
        val key = apiKeyStore.read() ?: return GenerationResult.NoApiKey

        return withContext(Dispatchers.IO) {
            try {
                val client: AnthropicClient = AnthropicOkHttpClient.builder()
                    .apiKey(key)
                    .build()

                val params = MessageCreateParams.builder()
                    .model(Model.of(model))
                    .maxTokens(maxTokens.toLong())
                    .system(systemPrompt)
                    .addUserMessage(userPrompt)
                    .build()

                val response = client.messages().create(params)
                val text = response.content()
                    .mapNotNull { it.text().orElse(null)?.text() }
                    .joinToString("\n")

                val messages = text.lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith("#") }
                    .toList()

                GenerationResult.Success(messages)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Log.e(TAG, "Claude API call failed", t)
                GenerationResult.Failed(t)
            }
        }
    }

    companion object { private const val TAG = "ClaudeApiClient" }
}
```

Note: the exact SDK API (builder pattern, model constants) may need adjustment when you pin the version — the Anthropic Java SDK has been moving. Check the SDK's examples if `claude-3-5-haiku-latest` or the `Model.of(...)` path are named differently.

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "feat(ai): add ClaudeApiClient wrapper + GenerationResult sealed class"
```

---

### Task 13: `AiMessageGenerator` — orchestration

**Files:**
- Create: `mobile/src/main/java/com/meatsack/motivator/mobile/ai/AiMessageGenerator.kt`
- Modify: `shared/src/main/java/com/meatsack/shared/db/MessageDao.kt`

- [ ] **Step 1: Add DAO query for top-upvoted messages**

```kotlin
@Query("""
    SELECT text FROM messages
    WHERE isActive = 1
    ORDER BY (votesUp - votesDown) DESC
    LIMIT :limit
""")
suspend fun getTopUpvotedTexts(limit: Int): List<String>
```

- [ ] **Step 2: Create `AiMessageGenerator.kt`**

```kotlin
package com.meatsack.motivator.mobile.ai

import android.content.Context
import android.util.Log
import com.meatsack.motivator.mobile.sync.PhoneSyncSender
import com.meatsack.shared.constants.EscalationLevel
import com.meatsack.shared.constants.MessageSource
import com.meatsack.shared.constants.MessageTone
import com.meatsack.shared.constants.TriggerType
import com.meatsack.shared.db.AppDatabase
import com.meatsack.shared.model.Message

/**
 * Orchestrates a full "Generate Now" cycle:
 * 1. Read the user's top-upvoted messages as style examples
 * 2. Ask Claude for N new insults at the given level+trigger+tone
 * 3. Filter invalid lines (contain '|' or '\n' — our sync wire constraint)
 * 4. Insert into Room tagged as AI_GENERATED
 * 5. Trigger a phone→watch sync so new messages are available immediately
 */
class AiMessageGenerator(
    private val context: Context,
    private val client: ClaudeApiClient = ClaudeApiClient(ApiKeyStore(context)),
) {

    suspend fun generateBatch(
        level: EscalationLevel,
        trigger: TriggerType,
        tone: MessageTone,
        hourOfDay: Int,
        currentSteps: Int,
        count: Int = 10,
    ): GenerationResult {
        val db = AppDatabase.getDatabase(context)
        val topVoted = db.messageDao().getTopUpvotedTexts(5)

        val userPrompt = Prompts.buildUserPrompt(
            currentSteps, hourOfDay, level, trigger, tone, topVoted, count,
        )

        return when (val result = client.generate(Prompts.SYSTEM_PROMPT, userPrompt)) {
            is GenerationResult.Success -> {
                val valid = result.messages
                    .filter { it.length <= 200 && !it.contains('|') && !it.contains('\n') }
                if (valid.isEmpty()) {
                    Log.w(TAG, "Claude returned no valid messages after filtering")
                    return GenerationResult.Success(emptyList())
                }
                val now = System.currentTimeMillis()
                val entities = valid.map { text ->
                    Message(
                        text = text, level = level, triggerType = trigger,
                        tone = tone, source = MessageSource.AI_GENERATED,
                        lastShownTimestamp = 0, isActive = true,
                    )
                }
                db.messageDao().insertAll(entities)
                Log.d(TAG, "Inserted ${entities.size} AI messages; syncing to watch")
                PhoneSyncSender(context).syncMessagesToWatch()
                GenerationResult.Success(valid)
            }
            else -> result
        }
    }

    companion object { private const val TAG = "AiMessageGenerator" }
}
```

- [ ] **Step 3: Build**

```bash
./gradlew :mobile:assembleDebug
```

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "feat(ai): add AiMessageGenerator — prompt → claude → room → sync"
```

---

### Task 14: Settings UI — API key input + Generate Now button

**Files:**
- Modify: `mobile/src/main/java/com/meatsack/motivator/mobile/ui/settings/SettingsScreen.kt`
- Modify: `mobile/src/main/java/com/meatsack/motivator/mobile/ui/settings/SettingsViewModel.kt`

- [ ] **Step 1: Add API key state + generation logic to the ViewModel**

```kotlin
class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    // ... existing ...
    private val apiKeyStore = ApiKeyStore(application)
    private val _apiKeyPresent = MutableStateFlow(apiKeyStore.hasKey())
    val apiKeyPresent: StateFlow<Boolean> = _apiKeyPresent.asStateFlow()

    private val _generationStatus = MutableStateFlow<GenerationResult?>(null)
    val generationStatus: StateFlow<GenerationResult?> = _generationStatus.asStateFlow()

    fun saveApiKey(key: String) {
        if (key.isBlank()) apiKeyStore.clear() else apiKeyStore.save(key)
        _apiKeyPresent.value = apiKeyStore.hasKey()
    }

    fun generateNow() = viewModelScope.launch {
        _generationStatus.value = null
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val generator = AiMessageGenerator(getApplication())
        // v2 scope: generate a batch at SAVAGE/INACTIVITY/FULL_SEND — tone
        // variation is out of scope for the manual button. Users who want
        // different axes can run multiple generations.
        _generationStatus.value = generator.generateBatch(
            level = EscalationLevel.SAVAGE,
            trigger = TriggerType.INACTIVITY,
            tone = MessageTone.FULL_SEND,
            hourOfDay = hour,
            currentSteps = 0, // phone doesn't track steps in v2
        )
    }
}
```

- [ ] **Step 2: Add UI rows to `SettingsScreen`**

Near the bottom of the Column, before the context-aware toggle, add:

```kotlin
Spacer(Modifier.height(24.dp))
Text("Anthropic API key", style = MaterialTheme.typography.titleMedium)

val apiKeyPresent by viewModel.apiKeyPresent.collectAsState()
var draftKey by remember { mutableStateOf("") }
OutlinedTextField(
    value = draftKey,
    onValueChange = { draftKey = it },
    label = { Text(if (apiKeyPresent) "Replace key" else "Paste key") },
    singleLine = true,
    visualTransformation = PasswordVisualTransformation(),
    modifier = Modifier.fillMaxWidth(),
)
Row {
    Button(
        onClick = { viewModel.saveApiKey(draftKey); draftKey = "" },
        enabled = draftKey.isNotBlank(),
    ) { Text("Save") }
    Spacer(Modifier.width(8.dp))
    TextButton(onClick = { viewModel.saveApiKey("") }) { Text("Clear") }
    Spacer(Modifier.weight(1f))
    Text(
        if (apiKeyPresent) "✓ Saved" else "Not set",
        style = MaterialTheme.typography.bodySmall,
    )
}

Spacer(Modifier.height(16.dp))
val genStatus by viewModel.generationStatus.collectAsState()
Button(
    onClick = { viewModel.generateNow() },
    enabled = apiKeyPresent,
    modifier = Modifier.fillMaxWidth(),
) {
    Text("Generate 10 new insults")
}
genStatus?.let {
    val text = when (it) {
        is GenerationResult.Success -> "Generated ${it.messages.size} messages"
        GenerationResult.NoApiKey -> "No API key set"
        is GenerationResult.HttpError -> "HTTP ${it.status}"
        is GenerationResult.Failed -> "Error: ${it.error.message}"
    }
    Text(text, style = MaterialTheme.typography.bodySmall)
}
```

- [ ] **Step 3: Build + install**

```bash
./gradlew :mobile:assembleDebug
adb -s <phone> install -r mobile/build/outputs/apk/debug/mobile-debug.apk
```

Open the app, paste a test API key (get one from https://console.anthropic.com/), tap Generate.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "feat(ai): add API key input + Generate Now button in Settings"
```

---

## Phase 4 — Integration + Docs

### Task 15: Update CLAUDE.md + README.md

**Files:**
- Modify: `CLAUDE.md`
- Modify: `README.md`

- [ ] **Step 1: CLAUDE.md — add v2 architecture notes**

Under the Modules section, add:

- `mobile/ai/` — Claude API integration. `ApiKeyStore` wraps EncryptedSharedPreferences for key storage. `Prompts.buildUserPrompt` constructs the per-call prompt using the user's top-upvoted messages as style examples. `ClaudeApiClient` is a thin wrapper over the Anthropic Kotlin SDK. `AiMessageGenerator` orchestrates generate → filter → persist → sync.
- `wear/trigger/` — `TriggerScheduler` + `BehindPaceWorker` (hourly) + `EndOfDayWorker` (daily at configurable hour). Both workers read step count from a SharedPreferences hand-off updated by HealthTracker, then select a message for the appropriate trigger type.

- [ ] **Step 2: README.md — add v2 section**

Under the "How it works" section, add a subsection:

```markdown
### v2 intelligence additions

- **Scheduled triggers.** Every hour during your active window, the watch compares your step count against the pace needed to hit your daily goal. Behind? Aggressive → Existential escalation based on how far. At your configured end-of-day hour, a final reckoning fires if you missed the goal.
- **AI-generated insults.** Paste an Anthropic API key in Settings and tap "Generate 10 new insults" — the app sends your top-upvoted messages as style examples so new content drifts toward what actually lands for you. AI messages are tagged `AI_GENERATED`, persist in your library, vote-able.
- **Context-aware tone.** Toggle "Context-aware language" in Settings. When on: work-safe wording during your active hours, full-send outside.
```

- [ ] **Step 3: Commit**

```bash
git add CLAUDE.md README.md
git commit -m "docs: v2 architecture notes and user-facing feature summary"
```

---

### Task 16: End-to-end verification + release

**Files:** none — this is testing + tagging.

- [ ] **Step 1: Full test sweep**

```bash
./gradlew clean
./gradlew :shared:testDebugUnitTest :wear:testDebugUnitTest :mobile:testDebugUnitTest
./gradlew :wear:assembleDebug :mobile:assembleDebug
./gradlew spotlessCheck
```

All BUILD SUCCESSFUL.

- [ ] **Step 2: Install on real hardware**

```bash
adb -s <phone> install -r mobile/build/outputs/apk/debug/mobile-debug.apk
adb -s <watch> install -r wear/build/outputs/apk/debug/wear-debug.apk
```

Manually verify:
- Paste API key → Generate Now → observe new messages appear in Library
- Tap Sync to Watch (or confirm auto-sync happened) → watch DB has new messages
- Change end-of-day hour to 5 minutes from now → wait → end-of-day insult fires
- Set step goal higher than you'll hit → wait for hourly → BEHIND_PACE fires
- Toggle context-aware → watch receives setting → firing at 10am uses WORK_SAFE tone

- [ ] **Step 3: Open PR for v2**

```bash
git push -u origin feature/v2-implementation
gh pr create --base main --title "v2 — Intelligence" \
  --body-file docs/superpowers/plans/2026-04-19-meatsack-motivator-v2.md
```

- [ ] **Step 4: After CI green, merge**

```bash
gh pr merge --squash --delete-branch
```

- [ ] **Step 5: Tag v2.0.0 and release**

```bash
git checkout main && git pull
git tag -a v2.0.0 -m "meatsackMotivator v2.0.0 — Intelligence"
git push origin v2.0.0
gh release create v2.0.0 \
  --title "v2.0.0 — Intelligence" \
  --notes "..." \
  mobile/build/outputs/apk/debug/mobile-debug.apk \
  wear/build/outputs/apk/debug/wear-debug.apk
```

---

## Deferred to v3 (explicitly NOT in this plan)

- Workout detection via `ExerciseClient` (spec lists under v2 but v3 makes more sense)
- Positive reinforcement (grudging respect, rare genuine, streaks)
- Watch-side AI generation when phone disconnected
- Phone→watch settings sync DataItem (v2 uses static `WatchSettingsCache` defaults)
- Stats & history dashboard
- Share button on library entries
- User-custom messages ("write your own")
- Scheduled AI generation (cron-like weekly)
