# meatsackMotivator v1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a working Samsung Galaxy Watch app that detects inactivity, escalates through 4 levels of aggressive insults with haptic feedback, and a phone companion app that manages the insult library and syncs messages to the watch.

**Architecture:** Three Gradle modules — `shared` (data models, constants), `wear` (watch app with health monitoring, escalation, screen takeover), `mobile` (phone companion with settings, library browser, Data Layer sync). Room database on both sides. Pre-written message library seeded on first launch.

**Tech Stack:** Kotlin, Jetpack Compose for Wear OS, Jetpack Compose Material 3 (phone), Room, Health Services API, Wear OS Data Layer API, Gradle KTS

**Prerequisites:** Install Android Studio (latest stable), Android SDK 34+, Wear OS emulator image (API 30+). Pair a Wear OS emulator with a phone emulator for testing sync. See: https://developer.android.com/training/wearables/get-started/creating

---

## File Structure

```
meatsackMotivator/
├── build.gradle.kts                          # Root build file
├── settings.gradle.kts                       # Module declarations
├── gradle.properties                         # JVM + Android settings
├── shared/
│   ├── build.gradle.kts
│   └── src/main/java/com/meatsack/shared/
│       ├── model/
│       │   └── Message.kt                    # Message data class + Room entity
│       ├── db/
│       │   ├── MessageDao.kt                 # Room DAO for messages
│       │   └── AppDatabase.kt               # Room database definition
│       └── constants/
│           └── EscalationLevel.kt            # Level enum + defaults
├── wear/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/meatsack/wear/
│       │   ├── MeatsackWearApp.kt            # Application class
│       │   ├── presentation/
│       │   │   ├── InsultScreen.kt           # Full-screen takeover Compose UI
│       │   │   └── theme/
│       │   │       └── Theme.kt              # Watch theme (colors, typography)
│       │   ├── health/
│       │   │   └── StepTracker.kt            # Step counting + inactivity detection
│       │   ├── escalation/
│       │   │   └── EscalationManager.kt      # Timer, level tracking, reset logic
│       │   ├── messages/
│       │   │   └── MessageRepository.kt      # Local cache, selection algorithm
│       │   ├── notification/
│       │   │   └── InsultNotificationService.kt  # Notification + screen takeover
│       │   └── sync/
│       │       └── WatchSyncReceiver.kt      # Data Layer listener (receive from phone)
│       └── res/
│           └── values/
│               └── strings.xml
├── mobile/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/meatsack/mobile/
│       │   ├── MeatsackMobileApp.kt          # Application class
│       │   ├── MainActivity.kt               # Single-activity host
│       │   ├── ui/
│       │   │   ├── navigation/
│       │   │   │   └── NavGraph.kt           # Bottom nav routing
│       │   │   ├── settings/
│       │   │   │   ├── SettingsScreen.kt     # Goal, hours, thresholds
│       │   │   │   └── SettingsViewModel.kt
│       │   │   └── library/
│       │   │       ├── LibraryScreen.kt      # Browse/filter insults
│       │   │       └── LibraryViewModel.kt
│       │   ├── data/
│       │   │   ├── SettingsRepository.kt     # DataStore for user prefs
│       │   │   └── SeedData.kt              # Pre-written insult library
│       │   └── sync/
│       │       └── PhoneSyncSender.kt        # Data Layer sender (push to watch)
│       └── res/
│           └── values/
│               └── strings.xml
└── docs/
    └── superpowers/
        ├── specs/
        │   └── 2026-04-18-meatsack-motivator-design.md
        └── plans/
            └── 2026-04-18-meatsack-motivator-v1.md
```

---

## Task 0: Environment Setup

**Files:**
- Install: Android Studio, SDK, Wear OS emulator

This task has no code — it sets up the development environment.

- [ ] **Step 1: Install Android Studio**

Download and install Android Studio from https://developer.android.com/studio. Launch it and complete the first-run wizard (accept defaults).

- [ ] **Step 2: Install SDK components**

In Android Studio: Settings → SDK Manager → SDK Platforms tab → check "Android 14.0 (API 34)". SDK Tools tab → check "Android SDK Build-Tools 34", "Android Emulator", "Android SDK Platform-Tools".

- [ ] **Step 3: Create phone emulator**

Device Manager → Create Virtual Device → Phone → Pixel 7 → System Image: API 34 (UpsideDownCake) → Finish. Boot it once to verify.

- [ ] **Step 4: Create Wear OS emulator**

Device Manager → Create Virtual Device → Wear OS → Wear OS Large Round → System Image: API 30 (Wear OS 3) → Finish. Boot it once to verify.

- [ ] **Step 5: Pair emulators**

With both emulators running, in a terminal:

```bash
adb -s localhost:5554 forward tcp:5601 tcp:5601
```

On the phone emulator, install the "Wear OS" companion app from Play Store and pair to the watch. See: https://developer.android.com/training/wearables/get-started/connect-phone

---

## Task 1: Project Scaffolding

**Files:**
- Create: `build.gradle.kts` (root)
- Create: `settings.gradle.kts`
- Create: `gradle.properties`
- Create: `shared/build.gradle.kts`
- Create: `wear/build.gradle.kts`
- Create: `wear/src/main/AndroidManifest.xml`
- Create: `mobile/build.gradle.kts`
- Create: `mobile/src/main/AndroidManifest.xml`

- [ ] **Step 1: Create project via Android Studio**

File → New → New Project → Wear OS → Blank Activity (Compose). Set:
- Name: meatsackMotivator
- Package: com.meatsack.motivator
- Save location: `C:\Users\jaber\git_repos\meatsackMotivator`
- Language: Kotlin
- Min SDK: API 30 (Wear OS 3.0)

This generates the `wear` module. You'll restructure after.

- [ ] **Step 2: Add mobile module**

File → New → New Module → Phone & Tablet → No Activity. Set:
- Module name: mobile
- Package: com.meatsack.motivator
- Min SDK: API 26

- [ ] **Step 3: Add shared module**

File → New → New Module → Android Library. Set:
- Module name: shared
- Package: com.meatsack.shared
- Min SDK: API 26

- [ ] **Step 4: Configure root `settings.gradle.kts`**

Verify it includes all three modules:

```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolution {
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "meatsackMotivator"
include(":wear")
include(":mobile")
include(":shared")
```

- [ ] **Step 5: Configure `shared/build.gradle.kts`**

```kotlin
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.meatsack.shared"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
}
```

- [ ] **Step 6: Add shared dependency to wear and mobile modules**

In `wear/build.gradle.kts`, add to `dependencies`:
```kotlin
implementation(project(":shared"))
```

In `mobile/build.gradle.kts`, add to `dependencies`:
```kotlin
implementation(project(":shared"))
```

- [ ] **Step 7: Add Wear OS Data Layer dependency to both modules**

In `wear/build.gradle.kts`, add to `dependencies`:
```kotlin
implementation("com.google.android.gms:play-services-wearable:18.1.0")
```

In `mobile/build.gradle.kts`, add to `dependencies`:
```kotlin
implementation("com.google.android.gms:play-services-wearable:18.1.0")
```

- [ ] **Step 8: Sync and build**

Run: Android Studio → File → Sync Project with Gradle Files. Then Build → Make Project.
Expected: BUILD SUCCESSFUL with no errors.

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "feat: scaffold multi-module project (shared, wear, mobile)"
```

---

## Task 2: Shared Data Models

**Files:**
- Create: `shared/src/main/java/com/meatsack/shared/constants/EscalationLevel.kt`
- Create: `shared/src/main/java/com/meatsack/shared/model/Message.kt`
- Create: `shared/src/main/java/com/meatsack/shared/db/MessageDao.kt`
- Create: `shared/src/main/java/com/meatsack/shared/db/AppDatabase.kt`
- Test: `shared/src/androidTest/java/com/meatsack/shared/db/MessageDaoTest.kt`

- [ ] **Step 1: Create `EscalationLevel.kt`**

```kotlin
package com.meatsack.shared.constants

enum class EscalationLevel(val value: Int) {
    AGGRESSIVE(1),
    SAVAGE(2),
    NUCLEAR(3),
    EXISTENTIAL(4);

    companion object {
        const val INACTIVITY_THRESHOLD_MINUTES_DEFAULT = 30
        const val ESCALATION_INTERVAL_MINUTES = 30
        const val MOVEMENT_RESET_STEPS = 50
        const val MOVEMENT_RESET_WINDOW_MINUTES = 5

        fun fromValue(value: Int): EscalationLevel =
            entries.first { it.value == value }
    }
}

enum class TriggerType {
    INACTIVITY,
    BEHIND_PACE,
    END_OF_DAY,
    NO_WORKOUT
}

enum class MessageTone {
    FULL_SEND,
    WORK_SAFE
}

enum class MessageSource {
    PRE_WRITTEN,
    AI_GENERATED,
    USER_CUSTOM
}
```

- [ ] **Step 2: Create `Message.kt`**

```kotlin
package com.meatsack.shared.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.meatsack.shared.constants.EscalationLevel
import com.meatsack.shared.constants.MessageSource
import com.meatsack.shared.constants.MessageTone
import com.meatsack.shared.constants.TriggerType

@Entity(tableName = "messages")
data class Message(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val level: EscalationLevel,
    val triggerType: TriggerType,
    val tone: MessageTone,
    val source: MessageSource,
    val votesUp: Int = 0,
    val votesDown: Int = 0,
    val lastShownTimestamp: Long = 0,
    val isActive: Boolean = true
)
```

- [ ] **Step 3: Create `MessageDao.kt`**

```kotlin
package com.meatsack.shared.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.meatsack.shared.constants.EscalationLevel
import com.meatsack.shared.constants.MessageTone
import com.meatsack.shared.constants.TriggerType
import com.meatsack.shared.model.Message

@Dao
interface MessageDao {
    @Query("""
        SELECT * FROM messages 
        WHERE level = :level 
        AND triggerType = :triggerType 
        AND tone = :tone 
        AND isActive = 1
        AND votesDown < 3
        AND lastShownTimestamp < :cutoffTimestamp
        ORDER BY 
            CASE WHEN votesUp = 0 AND votesDown = 0 THEN 0 ELSE 1 END,
            (votesUp - votesDown) DESC
    """)
    suspend fun getEligibleMessages(
        level: EscalationLevel,
        triggerType: TriggerType,
        tone: MessageTone,
        cutoffTimestamp: Long
    ): List<Message>

    @Query("UPDATE messages SET votesUp = votesUp + 1 WHERE id = :messageId")
    suspend fun voteUp(messageId: Long)

    @Query("UPDATE messages SET votesDown = votesDown + 1 WHERE id = :messageId")
    suspend fun voteDown(messageId: Long)

    @Query("UPDATE messages SET lastShownTimestamp = :timestamp WHERE id = :messageId")
    suspend fun markShown(messageId: Long, timestamp: Long)

    @Query("UPDATE messages SET isActive = 0 WHERE id = :messageId")
    suspend fun deactivate(messageId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(messages: List<Message>)

    @Query("SELECT * FROM messages ORDER BY (votesUp - votesDown) DESC")
    suspend fun getAllMessages(): List<Message>

    @Query("SELECT COUNT(*) FROM messages")
    suspend fun getMessageCount(): Int
}
```

- [ ] **Step 4: Create `AppDatabase.kt`**

```kotlin
package com.meatsack.shared.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.meatsack.shared.model.Message

@Database(entities = [Message::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "meatsack_database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
```

- [ ] **Step 5: Add Room TypeConverters for enums**

Room doesn't know how to store enums by default. Add a converter. Create `shared/src/main/java/com/meatsack/shared/db/Converters.kt`:

```kotlin
package com.meatsack.shared.db

import androidx.room.TypeConverter
import com.meatsack.shared.constants.EscalationLevel
import com.meatsack.shared.constants.MessageSource
import com.meatsack.shared.constants.MessageTone
import com.meatsack.shared.constants.TriggerType

class Converters {
    @TypeConverter fun fromEscalationLevel(value: EscalationLevel): Int = value.value
    @TypeConverter fun toEscalationLevel(value: Int): EscalationLevel = EscalationLevel.fromValue(value)

    @TypeConverter fun fromTriggerType(value: TriggerType): String = value.name
    @TypeConverter fun toTriggerType(value: String): TriggerType = TriggerType.valueOf(value)

    @TypeConverter fun fromMessageTone(value: MessageTone): String = value.name
    @TypeConverter fun toMessageTone(value: String): MessageTone = MessageTone.valueOf(value)

    @TypeConverter fun fromMessageSource(value: MessageSource): String = value.name
    @TypeConverter fun toMessageSource(value: String): MessageSource = MessageSource.valueOf(value)
}
```

Then add `@TypeConverters(Converters::class)` to `AppDatabase`:

```kotlin
@Database(entities = [Message::class], version = 1, exportSchema = false)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
```

- [ ] **Step 6: Write instrumented test for MessageDao**

Create `shared/src/androidTest/java/com/meatsack/shared/db/MessageDaoTest.kt`:

```kotlin
package com.meatsack.shared.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.meatsack.shared.constants.EscalationLevel
import com.meatsack.shared.constants.MessageSource
import com.meatsack.shared.constants.MessageTone
import com.meatsack.shared.constants.TriggerType
import com.meatsack.shared.model.Message
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MessageDaoTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: MessageDao

    private fun testMessage(
        text: String = "Test insult",
        level: EscalationLevel = EscalationLevel.AGGRESSIVE,
        triggerType: TriggerType = TriggerType.INACTIVITY,
        tone: MessageTone = MessageTone.FULL_SEND,
    ) = Message(
        text = text,
        level = level,
        triggerType = triggerType,
        tone = tone,
        source = MessageSource.PRE_WRITTEN,
    )

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        dao = db.messageDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun insertAndRetrieveMessages() = runBlocking {
        val messages = listOf(
            testMessage("GET UP, you lazy sack of shit."),
            testMessage("Are you glued to that chair?"),
        )
        dao.insertAll(messages)
        assertEquals(2, dao.getMessageCount())
    }

    @Test
    fun getEligibleMessagesFiltersCorrectly() = runBlocking {
        dao.insertAll(listOf(
            testMessage("Level 1 inactivity", level = EscalationLevel.AGGRESSIVE),
            testMessage("Level 2 inactivity", level = EscalationLevel.SAVAGE),
            testMessage("Level 1 behind pace", triggerType = TriggerType.BEHIND_PACE),
        ))
        val results = dao.getEligibleMessages(
            EscalationLevel.AGGRESSIVE,
            TriggerType.INACTIVITY,
            MessageTone.FULL_SEND,
            cutoffTimestamp = 0
        )
        assertEquals(1, results.size)
        assertEquals("Level 1 inactivity", results[0].text)
    }

    @Test
    fun votesDownHidesMessage() = runBlocking {
        dao.insertAll(listOf(testMessage("Bad insult")))
        val msg = dao.getAllMessages().first()
        dao.voteDown(msg.id)
        dao.voteDown(msg.id)
        dao.voteDown(msg.id)
        val results = dao.getEligibleMessages(
            EscalationLevel.AGGRESSIVE,
            TriggerType.INACTIVITY,
            MessageTone.FULL_SEND,
            cutoffTimestamp = 0
        )
        assertTrue(results.isEmpty())
    }

    @Test
    fun recentlyShownMessagesExcluded() = runBlocking {
        dao.insertAll(listOf(testMessage("Recent insult")))
        val msg = dao.getAllMessages().first()
        val now = System.currentTimeMillis()
        dao.markShown(msg.id, now)
        val results = dao.getEligibleMessages(
            EscalationLevel.AGGRESSIVE,
            TriggerType.INACTIVITY,
            MessageTone.FULL_SEND,
            cutoffTimestamp = now + 1000
        )
        assertTrue(results.isEmpty())
    }

    @Test
    fun voteUpIncrementsCount() = runBlocking {
        dao.insertAll(listOf(testMessage("Great insult")))
        val msg = dao.getAllMessages().first()
        dao.voteUp(msg.id)
        dao.voteUp(msg.id)
        val updated = dao.getAllMessages().first()
        assertEquals(2, updated.votesUp)
    }
}
```

- [ ] **Step 7: Add test dependencies to `shared/build.gradle.kts`**

Add to `dependencies`:

```kotlin
androidTestImplementation("androidx.test:core:1.5.0")
androidTestImplementation("androidx.test:runner:1.5.2")
androidTestImplementation("androidx.test.ext:junit:1.1.5")
androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
```

- [ ] **Step 8: Run tests**

Run: In Android Studio, right-click `MessageDaoTest` → Run (must run on emulator — these are instrumented tests).
Expected: All 5 tests PASS.

- [ ] **Step 9: Build full project**

Run: Build → Make Project
Expected: BUILD SUCCESSFUL

- [ ] **Step 10: Commit**

```bash
git add -A
git commit -m "feat: add shared data models, Room database, and DAO with tests"
```

---

## Task 3: Pre-Written Insult Library

**Files:**
- Create: `mobile/src/main/java/com/meatsack/mobile/data/SeedData.kt`

- [ ] **Step 1: Create `SeedData.kt` with ~100 messages**

```kotlin
package com.meatsack.mobile.data

import com.meatsack.shared.constants.EscalationLevel
import com.meatsack.shared.constants.MessageSource
import com.meatsack.shared.constants.MessageTone
import com.meatsack.shared.constants.TriggerType
import com.meatsack.shared.model.Message

object SeedData {

    fun getPreWrittenMessages(): List<Message> = buildList {
        // ── Level 1: AGGRESSIVE ── Inactivity ──
        addAll(inactivityLevel1Full)
        addAll(inactivityLevel1WorkSafe)
        // ── Level 2: SAVAGE ── Inactivity ──
        addAll(inactivityLevel2Full)
        addAll(inactivityLevel2WorkSafe)
        // ── Level 3: NUCLEAR ── Inactivity ──
        addAll(inactivityLevel3Full)
        addAll(inactivityLevel3WorkSafe)
        // ── Level 4: EXISTENTIAL ── Inactivity ──
        addAll(inactivityLevel4Full)
        addAll(inactivityLevel4WorkSafe)
    }

    private fun msg(
        text: String,
        level: EscalationLevel,
        trigger: TriggerType = TriggerType.INACTIVITY,
        tone: MessageTone = MessageTone.FULL_SEND,
    ) = Message(
        text = text,
        level = level,
        triggerType = trigger,
        tone = tone,
        source = MessageSource.PRE_WRITTEN,
    )

    // ── LEVEL 1: AGGRESSIVE ─────────────────────────────────────

    private val inactivityLevel1Full = listOf(
        msg("Are you glued to that chair? GET UP, you lazy sack of shit.", EscalationLevel.AGGRESSIVE),
        msg("30 minutes of nothing. You're pathetic. Move.", EscalationLevel.AGGRESSIVE),
        msg("Hey, you sedentary piece of gristle. Your legs still work. USE THEM.", EscalationLevel.AGGRESSIVE),
        msg("GET UP, you domesticated sloth.", EscalationLevel.AGGRESSIVE),
        msg("You've been still so long your muscles are filing a missing persons report.", EscalationLevel.AGGRESSIVE),
        msg("Move it, you couch-welded disappointment.", EscalationLevel.AGGRESSIVE),
        msg("Your chair is not your coffin. Not yet. GET UP.", EscalationLevel.AGGRESSIVE),
        msg("Still sitting? You oxygen-wasting chair ornament. MOVE.", EscalationLevel.AGGRESSIVE),
        msg("GET UP, you overfed house cat.", EscalationLevel.AGGRESSIVE),
        msg("30 minutes. You've done nothing. You're a waste of a heartbeat right now.", EscalationLevel.AGGRESSIVE),
        msg("Your ancestors didn't survive plagues for you to sit there like a lump. MOVE.", EscalationLevel.AGGRESSIVE),
        msg("Get off your ass, you cortisol-soaked quitter.", EscalationLevel.AGGRESSIVE),
    )

    private val inactivityLevel1WorkSafe = listOf(
        msg("Are you glued to that chair? GET UP.", EscalationLevel.AGGRESSIVE, tone = MessageTone.WORK_SAFE),
        msg("30 minutes of nothing. Pathetic. Move.", EscalationLevel.AGGRESSIVE, tone = MessageTone.WORK_SAFE),
        msg("Your muscles are filing a missing persons report. MOVE.", EscalationLevel.AGGRESSIVE, tone = MessageTone.WORK_SAFE),
        msg("Move it. Now. You're better than this. Barely.", EscalationLevel.AGGRESSIVE, tone = MessageTone.WORK_SAFE),
    )

    // ── LEVEL 2: SAVAGE ─────────────────────────────────────────

    private val inactivityLevel2Full = listOf(
        msg("One hour. Your muscles are literally eating themselves. You're choosing to rot.", EscalationLevel.SAVAGE),
        msg("You're choosing to rot right now. You know that, right?", EscalationLevel.SAVAGE),
        msg("Still sitting, you sarcopenic motherfucker? Your body is decomposing in real time.", EscalationLevel.SAVAGE),
        msg("An hour of nothing. You osteopenic jello mold. STAND UP.", EscalationLevel.SAVAGE),
        msg("Your joints are fusing together while you sit there, you arthritic waste.", EscalationLevel.SAVAGE),
        msg("One hour. A hibernating waste of a pulse. That's what you are.", EscalationLevel.SAVAGE),
        msg("You gravity-surrendering meatsack. One hour of decay. GET UP.", EscalationLevel.SAVAGE),
        msg("Still here? You Netflix-marinated excuse machine. Your body hates you.", EscalationLevel.SAVAGE),
        msg("Your skeleton is becoming decorative. One hour, you osteoporotic coward. MOVE.", EscalationLevel.SAVAGE),
        msg("An hour. You elevator-taking, stair-fearing fraud. STAND UP AND WALK.", EscalationLevel.SAVAGE),
    )

    private val inactivityLevel2WorkSafe = listOf(
        msg("One hour. Your muscles are literally eating themselves. Move.", EscalationLevel.SAVAGE, tone = MessageTone.WORK_SAFE),
        msg("You're choosing to rot right now. One hour of nothing.", EscalationLevel.SAVAGE, tone = MessageTone.WORK_SAFE),
        msg("Your joints are fusing. One hour. GET UP.", EscalationLevel.SAVAGE, tone = MessageTone.WORK_SAFE),
    )

    // ── LEVEL 3: NUCLEAR ────────────────────────────────────────

    private val inactivityLevel3Full = listOf(
        msg("Your ancestors survived wars and you can't walk to the kitchen, you arthritic jello mold.", EscalationLevel.NUCLEAR),
        msg("You weak, excuse-making piece of shit. STAND UP.", EscalationLevel.NUCLEAR),
        msg("90 minutes of nothing. You sarcopenic pansy ass motherfucker. MOVE.", EscalationLevel.NUCLEAR),
        msg("You're a weakness-worshipping fraud. Your body is giving up on you because YOU gave up first.", EscalationLevel.NUCLEAR),
        msg("You soft-bellied comfort addict. 90 minutes. Your muscles are screaming and you can't hear them over your excuses.", EscalationLevel.NUCLEAR),
        msg("GET UP, you osteopenic waste of a skeleton. 90 goddamn minutes.", EscalationLevel.NUCLEAR),
        msg("Your bones are turning to chalk while you sit there, you pathetic snooze-button-hitting disgrace.", EscalationLevel.NUCLEAR),
        msg("You potential-wasting coward. 90 minutes of choosing to be weak.", EscalationLevel.NUCLEAR),
    )

    private val inactivityLevel3WorkSafe = listOf(
        msg("Your ancestors survived wars and you can't walk to the kitchen.", EscalationLevel.NUCLEAR, tone = MessageTone.WORK_SAFE),
        msg("90 minutes. You're a weakness-worshipping fraud. STAND UP.", EscalationLevel.NUCLEAR, tone = MessageTone.WORK_SAFE),
    )

    // ── LEVEL 4: EXISTENTIAL ────────────────────────────────────

    private val inactivityLevel4Full = listOf(
        msg("2 hours of nothing. Two hours closer to death, spent getting weaker.", EscalationLevel.EXISTENTIAL),
        msg("What are you even doing with your life?", EscalationLevel.EXISTENTIAL),
        msg("2 hours. You beached fucking walrus of a human. Every minute is a choice and you keep choosing weakness.", EscalationLevel.EXISTENTIAL),
        msg("Two hours. Your body is a temple and you've turned it into a landfill. GET UP.", EscalationLevel.EXISTENTIAL),
        msg("You've been sitting so long your blood has a parking brake on it. TWO HOURS. MOVE.", EscalationLevel.EXISTENTIAL),
        msg("2 hours of bone-density-losing, muscle-wasting, excuse-making NOTHING. Is this who you are?", EscalationLevel.EXISTENTIAL),
        msg("Two hours. You're not resting. You're practicing being dead.", EscalationLevel.EXISTENTIAL),
        msg("Is this it? Is this your life? Sitting here while your body rots? GET UP.", EscalationLevel.EXISTENTIAL),
    )

    private val inactivityLevel4WorkSafe = listOf(
        msg("2 hours of nothing. Two hours closer to death, spent getting weaker.", EscalationLevel.EXISTENTIAL, tone = MessageTone.WORK_SAFE),
        msg("What are you even doing with your life? Two hours. MOVE.", EscalationLevel.EXISTENTIAL, tone = MessageTone.WORK_SAFE),
    )
}
```

- [ ] **Step 2: Build to verify no compile errors**

Run: Build → Make Project
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "feat: add pre-written insult library with ~50 seed messages across all levels"
```

---

## Task 4: Watch — Message Repository & Selection

**Files:**
- Create: `wear/src/main/java/com/meatsack/wear/messages/MessageRepository.kt`
- Test: `wear/src/androidTest/java/com/meatsack/wear/messages/MessageRepositoryTest.kt`

- [ ] **Step 1: Create `MessageRepository.kt`**

```kotlin
package com.meatsack.wear.messages

import com.meatsack.shared.constants.EscalationLevel
import com.meatsack.shared.constants.MessageTone
import com.meatsack.shared.constants.TriggerType
import com.meatsack.shared.db.MessageDao
import com.meatsack.shared.model.Message

class MessageRepository(private val dao: MessageDao) {

    suspend fun selectMessage(
        level: EscalationLevel,
        triggerType: TriggerType,
        tone: MessageTone
    ): Message? {
        val oneDayAgo = System.currentTimeMillis() - 24 * 60 * 60 * 1000
        val eligible = dao.getEligibleMessages(level, triggerType, tone, oneDayAgo)
        if (eligible.isEmpty()) return null

        // Separate unvoted (AI-generated, not yet rated) from voted
        val unvoted = eligible.filter { it.votesUp == 0 && it.votesDown == 0 }
        val voted = eligible.filter { it.votesUp > 0 || it.votesDown > 0 }

        // 30% chance to show an unvoted message if any exist
        val pick = if (unvoted.isNotEmpty() && Math.random() < 0.3) {
            unvoted.random()
        } else if (voted.isNotEmpty()) {
            // Weighted random by net vote score (minimum weight 1)
            weightedRandom(voted)
        } else {
            eligible.random()
        }

        dao.markShown(pick.id, System.currentTimeMillis())
        return pick
    }

    suspend fun voteUp(messageId: Long) {
        dao.voteUp(messageId)
    }

    suspend fun voteDown(messageId: Long) {
        dao.voteDown(messageId)
    }

    private fun weightedRandom(messages: List<Message>): Message {
        val weights = messages.map { maxOf(1, it.votesUp - it.votesDown) }
        val totalWeight = weights.sum()
        var random = (Math.random() * totalWeight).toInt()
        for (i in messages.indices) {
            random -= weights[i]
            if (random <= 0) return messages[i]
        }
        return messages.last()
    }
}
```

- [ ] **Step 2: Write test for MessageRepository**

Create `wear/src/androidTest/java/com/meatsack/wear/messages/MessageRepositoryTest.kt`:

```kotlin
package com.meatsack.wear.messages

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.meatsack.shared.constants.EscalationLevel
import com.meatsack.shared.constants.MessageSource
import com.meatsack.shared.constants.MessageTone
import com.meatsack.shared.constants.TriggerType
import com.meatsack.shared.db.AppDatabase
import com.meatsack.shared.model.Message
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MessageRepositoryTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: MessageRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        repo = MessageRepository(db.messageDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun testMsg(text: String, level: EscalationLevel = EscalationLevel.AGGRESSIVE) = Message(
        text = text, level = level,
        triggerType = TriggerType.INACTIVITY, tone = MessageTone.FULL_SEND,
        source = MessageSource.PRE_WRITTEN,
    )

    @Test
    fun selectsMessageFromCorrectLevel() = runBlocking {
        db.messageDao().insertAll(listOf(
            testMsg("Level 1 msg", EscalationLevel.AGGRESSIVE),
            testMsg("Level 2 msg", EscalationLevel.SAVAGE),
        ))
        val msg = repo.selectMessage(EscalationLevel.AGGRESSIVE, TriggerType.INACTIVITY, MessageTone.FULL_SEND)
        assertNotNull(msg)
        assertEquals("Level 1 msg", msg!!.text)
    }

    @Test
    fun returnsNullWhenNoMessages() = runBlocking {
        val msg = repo.selectMessage(EscalationLevel.AGGRESSIVE, TriggerType.INACTIVITY, MessageTone.FULL_SEND)
        assertNull(msg)
    }

    @Test
    fun voteUpIncrementsScore() = runBlocking {
        db.messageDao().insertAll(listOf(testMsg("Great insult")))
        val msg = db.messageDao().getAllMessages().first()
        repo.voteUp(msg.id)
        repo.voteUp(msg.id)
        val updated = db.messageDao().getAllMessages().first()
        assertEquals(2, updated.votesUp)
    }
}
```

- [ ] **Step 3: Add test dependencies to `wear/build.gradle.kts`**

Add to `dependencies`:

```kotlin
androidTestImplementation("androidx.test:core:1.5.0")
androidTestImplementation("androidx.test:runner:1.5.2")
androidTestImplementation("androidx.test.ext:junit:1.1.5")
androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
```

- [ ] **Step 4: Run tests on Wear OS emulator**

Run: Right-click `MessageRepositoryTest` → Run
Expected: All 3 tests PASS

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: add message selection algorithm with weighted random and vote tracking"
```

---

## Task 5: Watch — Step Tracking & Inactivity Detection

**Files:**
- Create: `wear/src/main/java/com/meatsack/wear/health/StepTracker.kt`

- [ ] **Step 1: Add Health Services dependency to `wear/build.gradle.kts`**

Add to `dependencies`:

```kotlin
implementation("androidx.health:health-services-client:1.0.0-rc02")
```

- [ ] **Step 2: Add permissions to `wear/src/main/AndroidManifest.xml`**

Add inside `<manifest>`, before `<application>`:

```xml
<uses-permission android:name="android.permission.BODY_SENSORS" />
<uses-permission android:name="android.permission.ACTIVITY_RECOGNITION" />
```

- [ ] **Step 3: Create `StepTracker.kt`**

```kotlin
package com.meatsack.wear.health

import android.content.Context
import android.util.Log
import androidx.health.services.client.HealthServices
import androidx.health.services.client.PassiveListenerCallback
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.PassiveListenerConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class StepTracker(context: Context) {
    private val client = HealthServices.getClient(context).passiveMonitoringClient

    private val _totalStepsToday = MutableStateFlow(0)
    val totalStepsToday: StateFlow<Int> = _totalStepsToday

    private var lastMovementTimestamp: Long = System.currentTimeMillis()
    private var stepsInCurrentWindow: Int = 0
    private var windowStartTimestamp: Long = System.currentTimeMillis()

    companion object {
        private const val TAG = "StepTracker"
        private const val MOVEMENT_WINDOW_MS = 5 * 60 * 1000L // 5 minutes
        private const val MOVEMENT_THRESHOLD_STEPS = 50
    }

    private val callback = object : PassiveListenerCallback {
        override fun onNewDataPointsReceived(dataPoints: DataPointContainer) {
            val steps = dataPoints.getData(DataType.STEPS_DAILY).lastOrNull()
            if (steps != null) {
                val stepCount = steps.value
                _totalStepsToday.value = stepCount.toInt()
                trackMovement(stepCount.toInt())
                Log.d(TAG, "Steps today: $stepCount")
            }
        }
    }

    fun startTracking() {
        val config = PassiveListenerConfig.builder()
            .setDataTypes(setOf(DataType.STEPS_DAILY))
            .build()
        client.setPassiveListenerCallback(config, callback)
        Log.d(TAG, "Step tracking started")
    }

    fun stopTracking() {
        client.clearPassiveListenerCallbackAsync()
        Log.d(TAG, "Step tracking stopped")
    }

    fun getMinutesSinceLastMovement(): Int {
        val elapsed = System.currentTimeMillis() - lastMovementTimestamp
        return (elapsed / 60_000).toInt()
    }

    fun hasSignificantMovement(): Boolean {
        return stepsInCurrentWindow >= MOVEMENT_THRESHOLD_STEPS
    }

    private fun trackMovement(currentTotal: Int) {
        val now = System.currentTimeMillis()

        // Reset window if it's been more than 5 minutes
        if (now - windowStartTimestamp > MOVEMENT_WINDOW_MS) {
            stepsInCurrentWindow = 0
            windowStartTimestamp = now
        }

        stepsInCurrentWindow++

        if (stepsInCurrentWindow >= MOVEMENT_THRESHOLD_STEPS) {
            lastMovementTimestamp = now
            stepsInCurrentWindow = 0
            windowStartTimestamp = now
        }
    }
}
```

- [ ] **Step 4: Build to verify**

Run: Build → Make Project
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: add step tracking with inactivity detection via Health Services"
```

---

## Task 6: Watch — Escalation Manager

**Files:**
- Create: `wear/src/main/java/com/meatsack/wear/escalation/EscalationManager.kt`
- Test: `wear/src/test/java/com/meatsack/wear/escalation/EscalationManagerTest.kt`

- [ ] **Step 1: Create `EscalationManager.kt`**

```kotlin
package com.meatsack.wear.escalation

import com.meatsack.shared.constants.EscalationLevel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class EscalationManager {
    private val _currentLevel = MutableStateFlow(EscalationLevel.AGGRESSIVE)
    val currentLevel: StateFlow<EscalationLevel> = _currentLevel

    private var inactivityStartTime: Long = 0
    private var isActive: Boolean = false
    private var isSnoozed: Boolean = false
    private var snoozeUntil: Long = 0

    fun onInactivityDetected(minutesIdle: Int) {
        if (isSnoozed && System.currentTimeMillis() < snoozeUntil) return

        if (!isActive) {
            isActive = true
            inactivityStartTime = System.currentTimeMillis() - (minutesIdle * 60_000L)
        }

        isSnoozed = false
        _currentLevel.value = calculateLevel(minutesIdle)
    }

    fun onMovementDetected() {
        isActive = false
        inactivityStartTime = 0
        _currentLevel.value = EscalationLevel.AGGRESSIVE
    }

    fun snooze(durationMinutes: Int) {
        isSnoozed = true
        snoozeUntil = System.currentTimeMillis() + (durationMinutes * 60_000L)
    }

    fun shouldTrigger(minutesIdle: Int): Boolean {
        if (isSnoozed && System.currentTimeMillis() < snoozeUntil) return false

        val threshold = EscalationLevel.INACTIVITY_THRESHOLD_MINUTES_DEFAULT
        val interval = EscalationLevel.ESCALATION_INTERVAL_MINUTES

        if (minutesIdle < threshold) return false

        // Trigger at threshold, then every interval after
        val minutesPastThreshold = minutesIdle - threshold
        return minutesPastThreshold % interval == 0
    }

    fun isCurrentlySnoozed(): Boolean {
        return isSnoozed && System.currentTimeMillis() < snoozeUntil
    }

    private fun calculateLevel(minutesIdle: Int): EscalationLevel {
        val threshold = EscalationLevel.INACTIVITY_THRESHOLD_MINUTES_DEFAULT
        val interval = EscalationLevel.ESCALATION_INTERVAL_MINUTES
        val minutesPastThreshold = maxOf(0, minutesIdle - threshold)
        val escalations = minutesPastThreshold / interval

        return when {
            escalations >= 3 -> EscalationLevel.EXISTENTIAL
            escalations >= 2 -> EscalationLevel.NUCLEAR
            escalations >= 1 -> EscalationLevel.SAVAGE
            else -> EscalationLevel.AGGRESSIVE
        }
    }
}
```

- [ ] **Step 2: Write unit tests**

Create `wear/src/test/java/com/meatsack/wear/escalation/EscalationManagerTest.kt`:

```kotlin
package com.meatsack.wear.escalation

import com.meatsack.shared.constants.EscalationLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class EscalationManagerTest {
    private lateinit var manager: EscalationManager

    @Before
    fun setUp() {
        manager = EscalationManager()
    }

    @Test
    fun `starts at aggressive level`() {
        assertEquals(EscalationLevel.AGGRESSIVE, manager.currentLevel.value)
    }

    @Test
    fun `does not trigger before threshold`() {
        assertFalse(manager.shouldTrigger(20))
        assertFalse(manager.shouldTrigger(29))
    }

    @Test
    fun `triggers at threshold`() {
        assertTrue(manager.shouldTrigger(30))
    }

    @Test
    fun `triggers at escalation intervals`() {
        assertTrue(manager.shouldTrigger(30))  // Level 1
        assertTrue(manager.shouldTrigger(60))  // Level 2
        assertTrue(manager.shouldTrigger(90))  // Level 3
        assertTrue(manager.shouldTrigger(120)) // Level 4
    }

    @Test
    fun `does not trigger between intervals`() {
        assertFalse(manager.shouldTrigger(45))
        assertFalse(manager.shouldTrigger(75))
    }

    @Test
    fun `escalates levels correctly`() {
        manager.onInactivityDetected(30)
        assertEquals(EscalationLevel.AGGRESSIVE, manager.currentLevel.value)

        manager.onInactivityDetected(60)
        assertEquals(EscalationLevel.SAVAGE, manager.currentLevel.value)

        manager.onInactivityDetected(90)
        assertEquals(EscalationLevel.NUCLEAR, manager.currentLevel.value)

        manager.onInactivityDetected(120)
        assertEquals(EscalationLevel.EXISTENTIAL, manager.currentLevel.value)
    }

    @Test
    fun `movement resets to aggressive`() {
        manager.onInactivityDetected(90)
        assertEquals(EscalationLevel.NUCLEAR, manager.currentLevel.value)

        manager.onMovementDetected()
        assertEquals(EscalationLevel.AGGRESSIVE, manager.currentLevel.value)
    }

    @Test
    fun `stays at existential for extended inactivity`() {
        manager.onInactivityDetected(150)
        assertEquals(EscalationLevel.EXISTENTIAL, manager.currentLevel.value)

        manager.onInactivityDetected(300)
        assertEquals(EscalationLevel.EXISTENTIAL, manager.currentLevel.value)
    }
}
```

- [ ] **Step 3: Add JUnit dependency to `wear/build.gradle.kts`**

Add to `dependencies`:

```kotlin
testImplementation("junit:junit:4.13.2")
```

- [ ] **Step 4: Run tests**

Run: `./gradlew :wear:testDebugUnitTest --tests "com.meatsack.wear.escalation.EscalationManagerTest"`
Expected: All 8 tests PASS

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: add escalation manager with level calculation, snooze, and movement reset"
```

---

## Task 7: Watch — Insult Screen UI

**Files:**
- Create: `wear/src/main/java/com/meatsack/wear/presentation/theme/Theme.kt`
- Create: `wear/src/main/java/com/meatsack/wear/presentation/InsultScreen.kt`

- [ ] **Step 1: Create `Theme.kt`**

```kotlin
package com.meatsack.wear.presentation.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Colors
import androidx.wear.compose.material.MaterialTheme
import androidx.compose.runtime.Composable

private val MeatsackColors = Colors(
    primary = Color(0xFFFF3B30),      // Angry red
    onPrimary = Color.White,
    secondary = Color(0xFFFF9500),    // Warning orange
    onSecondary = Color.Black,
    background = Color.Black,
    onBackground = Color.White,
    surface = Color(0xFF1C1C1E),
    onSurface = Color.White,
    error = Color(0xFFFF453A),
    onError = Color.White,
)

@Composable
fun MeatsackTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colors = MeatsackColors,
        content = content
    )
}

object MeatsackTypography {
    val insultText = TextStyle(
        fontSize = 16.sp,
        fontWeight = FontWeight.Bold,
        color = Color.White,
        lineHeight = 20.sp,
    )
    val statsText = TextStyle(
        fontSize = 11.sp,
        fontWeight = FontWeight.Normal,
        color = Color(0xFF8E8E93),
        lineHeight = 14.sp,
    )
    val brandText = TextStyle(
        fontSize = 8.sp,
        fontWeight = FontWeight.Light,
        color = Color(0xFF3A3A3C),
    )
}
```

- [ ] **Step 2: Create `InsultScreen.kt`**

```kotlin
package com.meatsack.wear.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Text
import androidx.wear.tooling.preview.devices.WearDevices
import com.meatsack.wear.presentation.theme.MeatsackTheme
import com.meatsack.wear.presentation.theme.MeatsackTypography

@Composable
fun InsultScreen(
    insultText: String,
    statsText: String,
    onThumbsUp: () -> Unit,
    onThumbsDown: () -> Unit,
) {
    MeatsackTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Branding watermark
                Text(
                    text = "meatsackMotivator",
                    style = MeatsackTypography.brandText,
                )

                // Insult message
                Text(
                    text = insultText,
                    style = MeatsackTypography.insultText,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )

                // Stats context line
                Text(
                    text = statsText,
                    style = MeatsackTypography.statsText,
                    textAlign = TextAlign.Center,
                )

                // Vote buttons
                Row(
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Thumbs down
                    Button(
                        onClick = onThumbsDown,
                        modifier = Modifier.size(40.dp),
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(
                            backgroundColor = Color(0xFF2C2C2E)
                        ),
                    ) {
                        Text("👎", style = MeatsackTypography.insultText.copy(fontSize = androidx.compose.ui.unit.TextUnit.Unspecified))
                    }

                    // Thumbs up
                    Button(
                        onClick = onThumbsUp,
                        modifier = Modifier.size(40.dp),
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(
                            backgroundColor = Color(0xFF2C2C2E)
                        ),
                    ) {
                        Text("👍", style = MeatsackTypography.insultText.copy(fontSize = androidx.compose.ui.unit.TextUnit.Unspecified))
                    }
                }
            }
        }
    }
}

@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true)
@Composable
fun InsultScreenPreview() {
    InsultScreen(
        insultText = "GET UP, you osteopenic jello mold.",
        statsText = "438 steps. It's 2pm. Pathetic.",
        onThumbsUp = {},
        onThumbsDown = {},
    )
}
```

- [ ] **Step 3: Build and check preview**

Run: Build → Make Project. Then open `InsultScreen.kt` in Android Studio and click the "Preview" tab in the right panel to see the watch face rendering.
Expected: BUILD SUCCESSFUL, preview shows the insult screen on a round watch face.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "feat: add watch insult screen with vote buttons and meatsack theme"
```

---

## Task 8: Watch — Notification & Screen Takeover Service

**Files:**
- Create: `wear/src/main/java/com/meatsack/wear/notification/InsultNotificationService.kt`
- Modify: `wear/src/main/AndroidManifest.xml`

- [ ] **Step 1: Create `InsultNotificationService.kt`**

```kotlin
package com.meatsack.wear.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import com.meatsack.shared.model.Message
import com.meatsack.wear.presentation.InsultActivity

class InsultNotificationService(private val context: Context) {

    companion object {
        const val CHANNEL_ID = "meatsack_insults"
        const val NOTIFICATION_ID = 1
        const val EXTRA_MESSAGE_ID = "message_id"
        const val EXTRA_MESSAGE_TEXT = "message_text"
        const val EXTRA_STATS_TEXT = "stats_text"
    }

    init {
        createNotificationChannel()
    }

    fun deliverInsult(message: Message, statsText: String) {
        vibrate()
        showFullScreenNotification(message, statsText)
    }

    private fun vibrate() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            manager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        val effect = VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE)
        vibrator.vibrate(effect)
    }

    private fun showFullScreenNotification(message: Message, statsText: String) {
        val intent = Intent(context, InsultActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_MESSAGE_ID, message.id)
            putExtra(EXTRA_MESSAGE_TEXT, message.text)
            putExtra(EXTRA_STATS_TEXT, statsText)
        }

        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("meatsackMotivator")
            .setContentText(message.text)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(pendingIntent, true)
            .setAutoCancel(true)
            .build()

        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Insult Notifications",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Aggressive motivational messages"
            enableVibration(false) // We handle vibration ourselves
        }
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }
}
```

- [ ] **Step 2: Create `InsultActivity.kt`**

Create `wear/src/main/java/com/meatsack/wear/presentation/InsultActivity.kt`:

```kotlin
package com.meatsack.wear.presentation

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.meatsack.shared.db.AppDatabase
import com.meatsack.wear.messages.MessageRepository
import com.meatsack.wear.notification.InsultNotificationService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class InsultActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val messageId = intent.getLongExtra(InsultNotificationService.EXTRA_MESSAGE_ID, -1)
        val messageText = intent.getStringExtra(InsultNotificationService.EXTRA_MESSAGE_TEXT) ?: "MOVE."
        val statsText = intent.getStringExtra(InsultNotificationService.EXTRA_STATS_TEXT) ?: ""

        val db = AppDatabase.getDatabase(applicationContext)
        val repo = MessageRepository(db.messageDao())

        setContent {
            InsultScreen(
                insultText = messageText,
                statsText = statsText,
                onThumbsUp = {
                    CoroutineScope(Dispatchers.IO).launch {
                        if (messageId > 0) repo.voteUp(messageId)
                    }
                    finish()
                },
                onThumbsDown = {
                    CoroutineScope(Dispatchers.IO).launch {
                        if (messageId > 0) repo.voteDown(messageId)
                    }
                    finish()
                },
            )
        }
    }
}
```

- [ ] **Step 3: Register InsultActivity in `wear/src/main/AndroidManifest.xml`**

Add inside `<application>`:

```xml
<activity
    android:name=".presentation.InsultActivity"
    android:exported="false"
    android:showOnLockScreen="true"
    android:turnScreenOn="true"
    android:taskAffinity=""
    android:excludeFromRecents="true" />
```

Also add permission at the manifest level:

```xml
<uses-permission android:name="android.permission.VIBRATE" />
<uses-permission android:name="android.permission.USE_FULL_SCREEN_INTENT" />
```

- [ ] **Step 4: Build to verify**

Run: Build → Make Project
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: add notification service with haptic buzz and full-screen takeover"
```

---

## Task 9: Watch — Main Service (Ties Everything Together)

**Files:**
- Create: `wear/src/main/java/com/meatsack/wear/MeatsackWearService.kt`
- Create: `wear/src/main/java/com/meatsack/wear/MeatsackWearApp.kt`
- Modify: `wear/src/main/AndroidManifest.xml`

- [ ] **Step 1: Create `MeatsackWearService.kt`**

This is the foreground service that runs continuously, polling step data and firing insults.

```kotlin
package com.meatsack.wear

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.meatsack.shared.constants.EscalationLevel
import com.meatsack.shared.constants.MessageTone
import com.meatsack.shared.constants.TriggerType
import com.meatsack.shared.db.AppDatabase
import com.meatsack.wear.escalation.EscalationManager
import com.meatsack.wear.health.StepTracker
import com.meatsack.wear.messages.MessageRepository
import com.meatsack.wear.notification.InsultNotificationService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MeatsackWearService : Service() {

    private lateinit var stepTracker: StepTracker
    private lateinit var escalationManager: EscalationManager
    private lateinit var messageRepo: MessageRepository
    private lateinit var notificationService: InsultNotificationService

    private var pollingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default)

    companion object {
        private const val TAG = "MeatsackWearService"
        private const val FOREGROUND_CHANNEL_ID = "meatsack_service"
        private const val FOREGROUND_NOTIFICATION_ID = 2
        private const val POLL_INTERVAL_MS = 60_000L // Check every minute
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val db = AppDatabase.getDatabase(applicationContext)
        stepTracker = StepTracker(applicationContext)
        escalationManager = EscalationManager()
        messageRepo = MessageRepository(db.messageDao())
        notificationService = InsultNotificationService(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(FOREGROUND_NOTIFICATION_ID, createForegroundNotification())
        stepTracker.startTracking()
        startPolling()
        Log.d(TAG, "meatsackMotivator service started. Watching you.")
        return START_STICKY
    }

    override fun onDestroy() {
        pollingJob?.cancel()
        stepTracker.stopTracking()
        super.onDestroy()
    }

    private fun startPolling() {
        pollingJob = scope.launch {
            while (true) {
                delay(POLL_INTERVAL_MS)
                checkInactivity()
            }
        }
    }

    private suspend fun checkInactivity() {
        val minutesIdle = stepTracker.getMinutesSinceLastMovement()

        if (stepTracker.hasSignificantMovement()) {
            escalationManager.onMovementDetected()
            return
        }

        if (!escalationManager.shouldTrigger(minutesIdle)) return

        escalationManager.onInactivityDetected(minutesIdle)
        val level = escalationManager.currentLevel.value
        val tone = MessageTone.FULL_SEND // v1: always full send

        val message = messageRepo.selectMessage(level, TriggerType.INACTIVITY, tone) ?: return

        val steps = stepTracker.totalStepsToday.value
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val ampm = if (hour < 12) "am" else "pm"
        val displayHour = if (hour == 0) 12 else if (hour > 12) hour - 12 else hour
        val statsText = "$steps steps. It's $displayHour$ampm. Pathetic."

        Log.d(TAG, "Firing insult: Level ${level.value}, idle ${minutesIdle}min")
        notificationService.deliverInsult(message, statsText)
    }

    private fun createForegroundNotification(): Notification {
        val channel = NotificationChannel(
            FOREGROUND_CHANNEL_ID,
            "meatsackMotivator Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps meatsackMotivator running"
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)

        return NotificationCompat.Builder(this, FOREGROUND_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("meatsackMotivator")
            .setContentText("Watching you, you lazy meatsack.")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }
}
```

- [ ] **Step 2: Create `MeatsackWearApp.kt`**

```kotlin
package com.meatsack.wear

import android.app.Application
import android.content.Intent

class MeatsackWearApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val intent = Intent(this, MeatsackWearService::class.java)
        startForegroundService(intent)
    }
}
```

- [ ] **Step 3: Register in `wear/src/main/AndroidManifest.xml`**

Add to `<manifest>`:
```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_HEALTH" />
```

Set application class and register service inside `<application>`:
```xml
<application
    android:name=".MeatsackWearApp"
    ... >
    
    <service
        android:name=".MeatsackWearService"
        android:exported="false"
        android:foregroundServiceType="health" />
```

- [ ] **Step 4: Build to verify**

Run: Build → Make Project
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: add main wear service that polls inactivity and fires insults"
```

---

## Task 10: Phone — Settings & Data Store

**Files:**
- Create: `mobile/src/main/java/com/meatsack/mobile/data/SettingsRepository.kt`
- Create: `mobile/src/main/java/com/meatsack/mobile/ui/settings/SettingsScreen.kt`
- Create: `mobile/src/main/java/com/meatsack/mobile/ui/settings/SettingsViewModel.kt`

- [ ] **Step 1: Add DataStore dependency to `mobile/build.gradle.kts`**

Add to `dependencies`:
```kotlin
implementation("androidx.datastore:datastore-preferences:1.0.0")
implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
```

- [ ] **Step 2: Create `SettingsRepository.kt`**

```kotlin
package com.meatsack.mobile.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("meatsack_settings")

class SettingsRepository(private val context: Context) {

    companion object {
        val DAILY_STEP_GOAL = intPreferencesKey("daily_step_goal")
        val INACTIVITY_THRESHOLD = intPreferencesKey("inactivity_threshold_min")
        val ACTIVE_HOURS_START = intPreferencesKey("active_hours_start")
        val ACTIVE_HOURS_END = intPreferencesKey("active_hours_end")
        val QUIET_HOURS_START = intPreferencesKey("quiet_hours_start")
        val QUIET_HOURS_END = intPreferencesKey("quiet_hours_end")
        val CONTEXT_AWARE_ENABLED = booleanPreferencesKey("context_aware_enabled")
    }

    val dailyStepGoal: Flow<Int> = context.dataStore.data.map { it[DAILY_STEP_GOAL] ?: 10_000 }
    val inactivityThreshold: Flow<Int> = context.dataStore.data.map { it[INACTIVITY_THRESHOLD] ?: 30 }
    val activeHoursStart: Flow<Int> = context.dataStore.data.map { it[ACTIVE_HOURS_START] ?: 7 }
    val activeHoursEnd: Flow<Int> = context.dataStore.data.map { it[ACTIVE_HOURS_END] ?: 22 }
    val quietHoursStart: Flow<Int> = context.dataStore.data.map { it[QUIET_HOURS_START] ?: 22 }
    val quietHoursEnd: Flow<Int> = context.dataStore.data.map { it[QUIET_HOURS_END] ?: 7 }
    val contextAwareEnabled: Flow<Boolean> = context.dataStore.data.map { it[CONTEXT_AWARE_ENABLED] ?: false }

    suspend fun setDailyStepGoal(goal: Int) {
        context.dataStore.edit { it[DAILY_STEP_GOAL] = goal }
    }

    suspend fun setInactivityThreshold(minutes: Int) {
        context.dataStore.edit { it[INACTIVITY_THRESHOLD] = minutes }
    }

    suspend fun setActiveHours(start: Int, end: Int) {
        context.dataStore.edit {
            it[ACTIVE_HOURS_START] = start
            it[ACTIVE_HOURS_END] = end
        }
    }

    suspend fun setQuietHours(start: Int, end: Int) {
        context.dataStore.edit {
            it[QUIET_HOURS_START] = start
            it[QUIET_HOURS_END] = end
        }
    }

    suspend fun setContextAwareEnabled(enabled: Boolean) {
        context.dataStore.edit { it[CONTEXT_AWARE_ENABLED] = enabled }
    }
}
```

- [ ] **Step 3: Create `SettingsViewModel.kt`**

```kotlin
package com.meatsack.mobile.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.meatsack.mobile.data.SettingsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = SettingsRepository(application)

    val dailyStepGoal = repo.dailyStepGoal.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), 10_000)
    val inactivityThreshold = repo.inactivityThreshold.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), 30)
    val activeHoursStart = repo.activeHoursStart.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), 7)
    val activeHoursEnd = repo.activeHoursEnd.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), 22)
    val quietHoursStart = repo.quietHoursStart.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), 22)
    val quietHoursEnd = repo.quietHoursEnd.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), 7)
    val contextAwareEnabled = repo.contextAwareEnabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), false)

    fun updateStepGoal(goal: Int) = viewModelScope.launch { repo.setDailyStepGoal(goal) }
    fun updateInactivityThreshold(min: Int) = viewModelScope.launch { repo.setInactivityThreshold(min) }
    fun updateActiveHours(start: Int, end: Int) = viewModelScope.launch { repo.setActiveHours(start, end) }
    fun updateQuietHours(start: Int, end: Int) = viewModelScope.launch { repo.setQuietHours(start, end) }
    fun toggleContextAware(enabled: Boolean) = viewModelScope.launch { repo.setContextAwareEnabled(enabled) }
}
```

- [ ] **Step 4: Create `SettingsScreen.kt`**

```kotlin
package com.meatsack.mobile.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun SettingsScreen(viewModel: SettingsViewModel = viewModel()) {
    val stepGoal by viewModel.dailyStepGoal.collectAsState()
    val inactivityThreshold by viewModel.inactivityThreshold.collectAsState()
    val activeStart by viewModel.activeHoursStart.collectAsState()
    val activeEnd by viewModel.activeHoursEnd.collectAsState()
    val quietStart by viewModel.quietHoursStart.collectAsState()
    val quietEnd by viewModel.quietHoursEnd.collectAsState()
    val contextAware by viewModel.contextAwareEnabled.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))

        // Daily Step Goal
        Text("Daily Step Goal: $stepGoal", style = MaterialTheme.typography.titleMedium)
        Slider(
            value = stepGoal.toFloat(),
            onValueChange = { viewModel.updateStepGoal(it.toInt()) },
            valueRange = 2000f..30000f,
            steps = 27,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))

        // Inactivity Threshold
        Text("Inactivity Threshold: $inactivityThreshold min", style = MaterialTheme.typography.titleMedium)
        Slider(
            value = inactivityThreshold.toFloat(),
            onValueChange = { viewModel.updateInactivityThreshold(it.toInt()) },
            valueRange = 10f..120f,
            steps = 10,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))

        // Active Hours
        Text("Active Hours: ${activeStart}:00 - ${activeEnd}:00", style = MaterialTheme.typography.titleMedium)
        Text("Start", style = MaterialTheme.typography.bodySmall)
        Slider(
            value = activeStart.toFloat(),
            onValueChange = { viewModel.updateActiveHours(it.toInt(), activeEnd) },
            valueRange = 0f..23f,
            steps = 22,
            modifier = Modifier.fillMaxWidth(),
        )
        Text("End", style = MaterialTheme.typography.bodySmall)
        Slider(
            value = activeEnd.toFloat(),
            onValueChange = { viewModel.updateActiveHours(activeStart, it.toInt()) },
            valueRange = 0f..23f,
            steps = 22,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))

        // Quiet Hours
        Text("Quiet Hours: ${quietStart}:00 - ${quietEnd}:00", style = MaterialTheme.typography.titleMedium)
        Text("Start", style = MaterialTheme.typography.bodySmall)
        Slider(
            value = quietStart.toFloat(),
            onValueChange = { viewModel.updateQuietHours(it.toInt(), quietEnd) },
            valueRange = 0f..23f,
            steps = 22,
            modifier = Modifier.fillMaxWidth(),
        )
        Text("End", style = MaterialTheme.typography.bodySmall)
        Slider(
            value = quietEnd.toFloat(),
            onValueChange = { viewModel.updateQuietHours(quietStart, it.toInt()) },
            valueRange = 0f..23f,
            steps = 22,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))

        // Context-aware toggle
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                "Context-aware language",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = contextAware,
                onCheckedChange = { viewModel.toggleContextAware(it) },
            )
        }
        Text(
            "When on, uses cleaner language during work hours. When off, full send all day.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
```

- [ ] **Step 5: Build to verify**

Run: Build → Make Project
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat: add phone settings screen with DataStore persistence"
```

---

## Task 11: Phone — Insult Library Browser

**Files:**
- Create: `mobile/src/main/java/com/meatsack/mobile/ui/library/LibraryScreen.kt`
- Create: `mobile/src/main/java/com/meatsack/mobile/ui/library/LibraryViewModel.kt`

- [ ] **Step 1: Create `LibraryViewModel.kt`**

```kotlin
package com.meatsack.mobile.ui.library

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.meatsack.shared.db.AppDatabase
import com.meatsack.shared.model.Message
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class LibraryViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = AppDatabase.getDatabase(application).messageDao()

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages

    init {
        loadMessages()
    }

    fun loadMessages() {
        viewModelScope.launch {
            _messages.value = dao.getAllMessages()
        }
    }
}
```

- [ ] **Step 2: Create `LibraryScreen.kt`**

```kotlin
package com.meatsack.mobile.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meatsack.shared.model.Message

@Composable
fun LibraryScreen(viewModel: LibraryViewModel = viewModel()) {
    val messages by viewModel.messages.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text("Insult Library", style = MaterialTheme.typography.headlineMedium)
        Text("${messages.size} messages", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(16.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(messages) { message ->
                MessageCard(message)
            }
        }
    }
}

@Composable
private fun MessageCard(message: Message) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = message.text,
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "L${message.level.value} | ${message.triggerType.name} | ${message.source.name}",
                    style = MaterialTheme.typography.labelSmall,
                )
                Text(
                    "👍 ${message.votesUp}  👎 ${message.votesDown}",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}
```

- [ ] **Step 3: Build to verify**

Run: Build → Make Project
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "feat: add phone insult library browser with vote counts"
```

---

## Task 12: Phone — Main Activity & Navigation

**Files:**
- Create: `mobile/src/main/java/com/meatsack/mobile/ui/navigation/NavGraph.kt`
- Create: `mobile/src/main/java/com/meatsack/mobile/MainActivity.kt`
- Create: `mobile/src/main/java/com/meatsack/mobile/MeatsackMobileApp.kt`
- Modify: `mobile/src/main/AndroidManifest.xml`

- [ ] **Step 1: Add navigation dependencies to `mobile/build.gradle.kts`**

Add to `dependencies`:
```kotlin
implementation("androidx.navigation:navigation-compose:2.7.7")
implementation("androidx.compose.material:material-icons-extended:1.6.3")
```

- [ ] **Step 2: Create `NavGraph.kt`**

```kotlin
package com.meatsack.mobile.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.meatsack.mobile.ui.library.LibraryScreen
import com.meatsack.mobile.ui.settings.SettingsScreen

enum class Screen(val route: String, val label: String, val icon: ImageVector) {
    Library("library", "Library", Icons.Default.List),
    Settings("settings", "Settings", Icons.Default.Settings),
}

@Composable
fun MeatsackNavGraph() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    Scaffold(
        bottomBar = {
            NavigationBar {
                Screen.entries.forEach { screen ->
                    NavigationBarItem(
                        selected = currentRoute == screen.route,
                        onClick = {
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.startDestinationId) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(screen.icon, contentDescription = screen.label) },
                        label = { Text(screen.label) },
                    )
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Library.route,
            modifier = Modifier.padding(padding),
        ) {
            composable(Screen.Library.route) { LibraryScreen() }
            composable(Screen.Settings.route) { SettingsScreen() }
        }
    }
}
```

- [ ] **Step 3: Create `MainActivity.kt`**

```kotlin
package com.meatsack.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import com.meatsack.mobile.ui.navigation.MeatsackNavGraph

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                MeatsackNavGraph()
            }
        }
    }
}
```

- [ ] **Step 4: Create `MeatsackMobileApp.kt`**

```kotlin
package com.meatsack.mobile

import android.app.Application
import com.meatsack.mobile.data.SeedData
import com.meatsack.shared.db.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MeatsackMobileApp : Application() {
    override fun onCreate() {
        super.onCreate()
        seedDatabaseIfEmpty()
    }

    private fun seedDatabaseIfEmpty() {
        CoroutineScope(Dispatchers.IO).launch {
            val db = AppDatabase.getDatabase(this@MeatsackMobileApp)
            if (db.messageDao().getMessageCount() == 0) {
                db.messageDao().insertAll(SeedData.getPreWrittenMessages())
            }
        }
    }
}
```

- [ ] **Step 5: Update `mobile/src/main/AndroidManifest.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <application
        android:name=".MeatsackMobileApp"
        android:allowBackup="true"
        android:label="meatsackMotivator"
        android:supportsRtl="true"
        android:theme="@style/Theme.Material3.DayNight">

        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>

</manifest>
```

- [ ] **Step 6: Build and run phone app on emulator**

Run: Select "mobile" run configuration → Run on phone emulator.
Expected: App launches with bottom navigation showing Library (empty initially, then populated with seed data) and Settings screens.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat: add phone main activity with navigation, seed data on first launch"
```

---

## Task 13: Data Layer Sync (Phone → Watch)

**Files:**
- Create: `mobile/src/main/java/com/meatsack/mobile/sync/PhoneSyncSender.kt`
- Create: `wear/src/main/java/com/meatsack/wear/sync/WatchSyncReceiver.kt`
- Modify: `wear/src/main/AndroidManifest.xml`

- [ ] **Step 1: Create `PhoneSyncSender.kt`**

```kotlin
package com.meatsack.mobile.sync

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.meatsack.shared.db.AppDatabase
import kotlinx.coroutines.tasks.await

class PhoneSyncSender(private val context: Context) {

    companion object {
        private const val TAG = "PhoneSyncSender"
        private const val PATH_MESSAGES = "/messages"
        private const val KEY_MESSAGE_DATA = "message_data"
        private const val CACHE_SIZE = 50
    }

    suspend fun syncMessagesToWatch() {
        val db = AppDatabase.getDatabase(context)
        val messages = db.messageDao().getAllMessages()
            .filter { it.isActive && it.votesDown < 3 }
            .take(CACHE_SIZE)

        if (messages.isEmpty()) {
            Log.w(TAG, "No messages to sync")
            return
        }

        // Serialize messages as a simple delimited string
        // Format: id|text|level|triggerType|tone|source|votesUp|votesDown\n
        val serialized = messages.joinToString("\n") { msg ->
            "${msg.id}|${msg.text}|${msg.level.value}|${msg.triggerType.name}|${msg.tone.name}|${msg.source.name}|${msg.votesUp}|${msg.votesDown}"
        }

        val request = PutDataMapRequest.create(PATH_MESSAGES).apply {
            dataMap.putString(KEY_MESSAGE_DATA, serialized)
            dataMap.putLong("timestamp", System.currentTimeMillis())
        }.asPutDataRequest().setUrgent()

        try {
            Wearable.getDataClient(context).putDataItem(request).await()
            Log.d(TAG, "Synced ${messages.size} messages to watch")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sync messages", e)
        }
    }
}
```

- [ ] **Step 2: Create `WatchSyncReceiver.kt`**

```kotlin
package com.meatsack.wear.sync

import android.util.Log
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService
import com.meatsack.shared.constants.EscalationLevel
import com.meatsack.shared.constants.MessageSource
import com.meatsack.shared.constants.MessageTone
import com.meatsack.shared.constants.TriggerType
import com.meatsack.shared.db.AppDatabase
import com.meatsack.shared.model.Message
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class WatchSyncReceiver : WearableListenerService() {

    companion object {
        private const val TAG = "WatchSyncReceiver"
        private const val PATH_MESSAGES = "/messages"
        private const val KEY_MESSAGE_DATA = "message_data"
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        dataEvents.forEach { event ->
            val path = event.dataItem.uri.path
            if (path == PATH_MESSAGES) {
                val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
                val serialized = dataMap.getString(KEY_MESSAGE_DATA) ?: return@forEach

                val messages = deserializeMessages(serialized)
                CoroutineScope(Dispatchers.IO).launch {
                    val db = AppDatabase.getDatabase(applicationContext)
                    db.messageDao().insertAll(messages)
                    Log.d(TAG, "Received and stored ${messages.size} messages from phone")
                }
            }
        }
    }

    private fun deserializeMessages(data: String): List<Message> {
        return data.split("\n").mapNotNull { line ->
            val parts = line.split("|")
            if (parts.size < 8) return@mapNotNull null
            try {
                Message(
                    id = parts[0].toLong(),
                    text = parts[1],
                    level = EscalationLevel.fromValue(parts[2].toInt()),
                    triggerType = TriggerType.valueOf(parts[3]),
                    tone = MessageTone.valueOf(parts[4]),
                    source = MessageSource.valueOf(parts[5]),
                    votesUp = parts[6].toInt(),
                    votesDown = parts[7].toInt(),
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse message: $line", e)
                null
            }
        }
    }
}
```

- [ ] **Step 3: Register WatchSyncReceiver in `wear/src/main/AndroidManifest.xml`**

Add inside `<application>`:

```xml
<service
    android:name=".sync.WatchSyncReceiver"
    android:exported="true">
    <intent-filter>
        <action android:name="com.google.android.gms.wearable.DATA_CHANGED" />
        <data
            android:scheme="wear"
            android:host="*"
            android:pathPrefix="/messages" />
    </intent-filter>
</service>
```

- [ ] **Step 4: Add a "Sync to Watch" button in the phone app**

Add to `LibraryScreen.kt`, after the title section:

```kotlin
import androidx.compose.material3.Button
import com.meatsack.mobile.sync.PhoneSyncSender
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

// Inside LibraryScreen composable, after the "messages" count text:
val context = LocalContext.current
val scope = rememberCoroutineScope()

Button(
    onClick = {
        scope.launch {
            PhoneSyncSender(context).syncMessagesToWatch()
        }
    },
    modifier = Modifier.fillMaxWidth(),
) {
    Text("Sync to Watch")
}
Spacer(Modifier.height(8.dp))
```

- [ ] **Step 5: Build both modules**

Run: Build → Make Project
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat: add Data Layer sync — phone pushes message cache to watch"
```

---

## Task 14: End-to-End Test on Emulators

**Files:** No new files — this is integration testing.

- [ ] **Step 1: Run phone app**

Select "mobile" configuration → Run on phone emulator.
Expected: App launches, Library shows seed messages, Settings has all sliders.

- [ ] **Step 2: Sync messages to watch**

In the phone app Library screen, tap "Sync to Watch".
Expected: Logcat shows "Synced X messages to watch" from PhoneSyncSender.

- [ ] **Step 3: Run watch app**

Select "wear" configuration → Run on Wear OS emulator.
Expected: Watch app starts. Logcat shows "meatsackMotivator service started" and "Step tracking started".

- [ ] **Step 4: Wait for inactivity trigger**

Wait 30+ minutes on the emulator (or temporarily change `INACTIVITY_THRESHOLD_MINUTES_DEFAULT` to 1 for testing). 
Expected: Watch vibrates, InsultActivity launches with a full-screen insult and vote buttons.

- [ ] **Step 5: Test voting**

Tap thumbs up or thumbs down on the insult screen.
Expected: Screen dismisses. In logcat, the vote is recorded.

- [ ] **Step 6: Restore defaults and commit**

If you changed any constants for testing, revert them. Then:

```bash
git add -A
git commit -m "test: verify end-to-end flow — phone sync, watch trigger, insult delivery, voting"
```

---

## Task 15: CLAUDE.md & Project Documentation

**Files:**
- Create: `CLAUDE.md`

- [ ] **Step 1: Create `CLAUDE.md`**

```markdown
# CLAUDE.md

## Project Overview

**meatsackMotivator** — Samsung Galaxy Watch (Wear OS) app that delivers aggressive, David Goggins-style motivational insults based on health/activity data. Phone companion app manages settings and the insult library.

## Commands

```bash
# Build
./gradlew build

# Run tests
./gradlew :shared:connectedAndroidTest    # Room DAO tests (needs emulator)
./gradlew :wear:testDebugUnitTest          # Escalation manager unit tests
./gradlew :wear:connectedAndroidTest       # Message repository tests (needs emulator)

# Install on devices
./gradlew :wear:installDebug               # Install watch app
./gradlew :mobile:installDebug             # Install phone app
```

## Architecture

Three Gradle modules:
- **shared** — Room database, Message entity, DAO, enum constants. Used by both wear and mobile.
- **wear** — Watch app. StepTracker (Health Services), EscalationManager (level/timer), MessageRepository (selection algorithm), InsultNotificationService (haptic + full-screen), MeatsackWearService (foreground polling service).
- **mobile** — Phone companion. SettingsScreen (DataStore), LibraryScreen (message browser), PhoneSyncSender (Data Layer push to watch), SeedData (pre-written insults).

## Data Flow

Phone seeds database → syncs message cache to watch via Data Layer → Watch polls step count → detects inactivity → escalation manager picks level → message repository selects insult → notification service buzzes and shows full-screen takeover → user votes → vote stored locally.

## Git Workflow

- Do NOT commit directly to main. Create a feature branch for each fix/feature.
- Branch naming: `fix/description`, `feature/description`
```

- [ ] **Step 2: Commit**

```bash
git add CLAUDE.md
git commit -m "docs: add CLAUDE.md with project overview, commands, and architecture"
```

---

## Summary

| Task | What it builds | Tests |
|---|---|---|
| 0 | Dev environment setup | — |
| 1 | Project scaffolding (3 Gradle modules) | Build succeeds |
| 2 | Shared data models, Room DB, DAO | 5 instrumented tests |
| 3 | Pre-written insult library (~50 messages) | Build succeeds |
| 4 | Watch message repository + selection algorithm | 3 instrumented tests |
| 5 | Watch step tracking + inactivity detection | Build succeeds |
| 6 | Watch escalation manager | 8 unit tests |
| 7 | Watch insult screen UI | Compose preview |
| 8 | Watch notification + screen takeover | Build succeeds |
| 9 | Watch main service (ties it all together) | Build succeeds |
| 10 | Phone settings + DataStore | Build succeeds |
| 11 | Phone insult library browser | Build succeeds |
| 12 | Phone main activity + navigation + seed data | Run on emulator |
| 13 | Data Layer sync (phone → watch) | Build succeeds |
| 14 | End-to-end integration test | Manual on emulators |
| 15 | CLAUDE.md documentation | — |

**Total: 16 tests (automated) + manual E2E verification**
