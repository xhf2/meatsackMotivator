# Externalize insults into an editable build-time JSON file

**Date:** 2026-06-25
**Status:** Approved (design)
**Scope:** Option 1 — developer/build-time externalization. End-user runtime import/export (Option 2) is explicitly out of scope but the format chosen here is forward-compatible with it.

## Problem

The 69 pre-written insults live as hardcoded Kotlin in
`shared/src/main/java/com/meatsack/shared/data/SeedData.kt`, split across 22
`listOf(...)` properties keyed by (trigger × level × tone). Editing wording,
adding an insult, or checking which escalation level a line fires at means
reading Kotlin and recompiling the `shared` module.

Goal: move the insult content into a single hand-editable data file in the repo
so the wording — and the trigger/level/tone of each line — can be edited and
inspected without touching Kotlin, while keeping the same first-launch seeding
behavior.

## Non-goals

- No on-device editing, import, or export (that is Option 2 — a future feature).
- No change to the runtime data model: after first-launch seeding, the Room DB
  remains the source of truth for live messages (votes, edits, AI additions,
  `isActive`). This file only supplies the **factory-default seed**.
- No change to escalation/selection logic, sync, or the phone Library UI.

## Format

A single asset bundled into both APKs:

`shared/src/main/assets/insults.json`

A flat JSON array. **Every record spells out all four fields explicitly** — no
defaults, so any field can be scanned/grepped/diffed without remembering an
implied value, and the level/trigger of each line is always visible:

```json
[
  {
    "text": "GET UP, you domesticated sloth.",
    "trigger": "INACTIVITY",
    "level": "AGGRESSIVE",
    "tone": "FULL_SEND"
  },
  {
    "text": "Are you glued to that chair? GET UP.",
    "trigger": "INACTIVITY",
    "level": "AGGRESSIVE",
    "tone": "WORK_SAFE"
  }
]
```

Field values are the **enum constant names** (decoded by name):

- `trigger` — `TriggerType`: `INACTIVITY` | `BEHIND_PACE` | `END_OF_DAY`
- `level` — `EscalationLevel`: `AGGRESSIVE` | `SAVAGE` | `NUCLEAR` | `EXISTENTIAL`
- `tone` — `MessageTone`: `FULL_SEND` | `WORK_SAFE`

`source` is **not** in the file. Every record loaded from this asset is assigned
`MessageSource.PRE_WRITTEN` in code. Runtime-only fields (`id`, `votesUp`,
`votesDown`, `lastShownTimestamp`, `isActive`) are not in the file and take their
`Message` defaults.

### Why flat-array (forward-compat with Option 2)

The flat record array is a DB-export shape: an object is a `Message` minus its
runtime-only fields. A future Option-2 import/export can reuse this exact schema
(extended with the runtime fields it needs to round-trip). A grouped
`trigger→level→tone→[strings]` structure was rejected because it cannot
round-trip per-message state and would be a dead end for Option 2.

## Components

### `InsultDto` (new, `shared`)

`@Serializable data class` mirroring one JSON record:

```kotlin
@Serializable
data class InsultDto(
    val text: String,
    val trigger: TriggerType,
    val level: EscalationLevel,
    val tone: MessageTone,
)
```

All four fields required (no Kotlin default values) — a record missing any field
is a parse error. The three enums get `@Serializable` so they decode by name.

### `InsultLoader` (new, `shared`)

```kotlin
object InsultLoader {
    fun load(context: Context): List<Message>
}
```

- Reads `insults.json` from assets:
  `context.assets.open("insults.json").bufferedReader().use { it.readText() }`.
- Decodes with `Json.decodeFromString<List<InsultDto>>(...)`.
- Maps each DTO → `Message(text, level, triggerType, tone, source = PRE_WRITTEN)`.
- Lets any exception (missing asset, malformed JSON, unknown enum name)
  **propagate** — it is a build-author error, not a recoverable runtime
  condition.

### `SeedData` (modified, `shared`)

The 22 hardcoded lists and the `msg()` helper are deleted. The public entry
point keeps its name but gains a `Context`:

```kotlin
object SeedData {
    fun getPreWrittenMessages(context: Context): List<Message> =
        InsultLoader.load(context)
}
```

(`SeedData` could be collapsed into `InsultLoader` entirely, but keeping the
`getPreWrittenMessages` name minimizes caller churn and preserves the existing
mental model.)

### Callers (modified)

- `MeatsackMobileApp.seedDatabaseIfEmpty()` →
  `SeedData.getPreWrittenMessages(this)`
- `MeatsackWearApp.seedDatabaseIfEmpty()` →
  `SeedData.getPreWrittenMessages(this)`

Both are `Application`s, so `this` is a valid `Context`. The existing
`try { ... } catch (t: Throwable) { Log.e(...) }` around seeding stays as the
runtime backstop: if a bad file ever ships, the seeder logs and leaves the
Library empty rather than crashing.

## Build changes

Add to the `shared` module (`shared/build.gradle.kts`):

- Apply the Kotlin serialization plugin (`kotlin("plugin.serialization")` /
  the version-catalog alias, matching the project's plugin style).
- `implementation` dependency on `org.jetbrains.kotlinx:kotlinx-serialization-json`.

Add the plugin + library coordinates to `gradle/libs.versions.toml` if the
project uses a version catalog (it does — deps are referenced as `libs.*`).

## Data migration

Port all current seed messages from `SeedData.kt` into `insults.json`
**verbatim** — same text, level, trigger, and tone, preserving FULL_SEND /
WORK_SAFE pairs. No content is added, dropped, or reworded in this change.

## Error handling

| Situation | Behavior |
|---|---|
| Malformed JSON / missing field / unknown enum name | Exception propagates from `InsultLoader`; caught by the seeder's existing `try/catch`, logged, Library left empty. Primarily prevented at build time by the test below. |
| Asset missing entirely | Same path (IOException → logged). |
| File valid but empty array | Seeds zero messages (degenerate but not an error). |

## Testing

A JVM unit test in `shared` (`InsultLoaderTest` or similar). Because the test
runs on the JVM, it reads the JSON from the module source path
(`src/main/assets/insults.json`) rather than via an Android `Context`; the
parsing logic under test is the same `Json.decodeFromString` call. It asserts:

1. The bundled JSON **parses** without error (this is what fails CI on a typo).
2. Row count is **> 0** and equals the number of records in the file.
3. Every `(trigger, level)` bucket present in the prior hardcoded seed is still
   represented (coverage guard against accidentally deleting a whole bucket).
4. Every decoded record maps to a `Message` with `source == PRE_WRITTEN`.

Existing tests that depend on the seed (e.g. `MessageRepositoryTest`,
`PhoneSyncSender`'s `MAX_VOTE_ROWS` assumptions) should continue to pass since
the message set is ported verbatim.

## Risks / notes

- **`MAX_VOTE_ROWS` / sync cap.** `PhoneSyncSender` documents the bundled seed
  (a few dozen rows; ~55 in the current `insults.json`) and caps the push at
  `CACHE_SIZE = 200`, so the seed count stays well under the cap regardless of
  hand-edits. The bundled-file test guards bucket coverage rather than an exact
  count, so intentional content edits don't require a test bump.
- **Asset path on JVM test.** The unit test reads the file directly from disk,
  not through `AssetManager`; this is a deliberate, minor divergence so the test
  needs no emulator. The runtime path (`AssetManager`) is exercised by the
  existing seeders on device.
- **Adding the serialization plugin** is a one-time `shared`-module setup the
  CLAUDE.md "known limitations" already anticipated ("switch to JSON
  (kotlinx.serialization)").
