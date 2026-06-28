package com.meatsack.shared.data

import com.meatsack.shared.constants.EscalationLevel
import com.meatsack.shared.constants.MessageLimits
import com.meatsack.shared.constants.MessageSource
import com.meatsack.shared.constants.TriggerType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class InsultLoaderFileTest {

    // Reads the asset straight from disk rather than via AssetManager: the
    // :shared:testDebugUnitTest working dir is the module dir (shared/), so this
    // relative path resolves without an emulator. Both paths feed the same
    // InsultLoader.parse, so the only thing this can't catch is the file failing
    // to bundle into the APK.
    private fun bundledInsults() =
        InsultLoader.parse(File("src/main/assets/${InsultLoader.ASSET_NAME}").readText())

    @Test
    fun bundledFile_parsesAndIsNotEmpty() {
        // No fixed count — insults.json is hand-edited, so any valid non-empty set
        // passes. The guard is only against the file parsing to zero records
        // (empty/corrupt). Bucket coverage below still enforces the structure.
        assertTrue(bundledInsults().isNotEmpty())
    }

    @Test
    fun bundledFile_allRecordsArePreWritten() {
        assertTrue(bundledInsults().all { it.source == MessageSource.PRE_WRITTEN })
    }

    @Test
    fun bundledFile_everyTextSerializable() {
        // Sync serializes every active message via MessageSerializer, which rejects
        // text over MAX_MESSAGE_TEXT_LENGTH or containing the '|'/newline field/line
        // separators (a require() that crashes the caller). A bundled insult that
        // violates these would seed fine but blow up "Sync to Watch" — guard it here
        // so a bad edit fails CI instead of the app.
        bundledInsults().forEach { m ->
            assertTrue(
                "insult exceeds ${MessageLimits.MAX_MESSAGE_TEXT_LENGTH} chars " +
                    "(len=${m.text.length}): ${m.text}",
                m.text.length <= MessageLimits.MAX_MESSAGE_TEXT_LENGTH,
            )
            assertFalse("insult contains '|': ${m.text}", m.text.contains("|"))
            assertFalse("insult contains a newline: ${m.text}", m.text.contains("\n"))
        }
    }

    @Test
    fun bundledFile_coversEveryTriggerLevelBucket() {
        val present = bundledInsults().map { it.triggerType to it.level }.toSet()
        // Lower-bound guard: every required bucket must be covered, but the set is
        // not locked to exactly these — adding an insult in a new trigger/level
        // combo is allowed and must not force a test edit (the point of dropping
        // the fixed-count assertion). It only fails if a required bucket vanishes.
        val required = setOf(
            TriggerType.INACTIVITY to EscalationLevel.AGGRESSIVE,
            TriggerType.INACTIVITY to EscalationLevel.SAVAGE,
            TriggerType.INACTIVITY to EscalationLevel.NUCLEAR,
            TriggerType.INACTIVITY to EscalationLevel.EXISTENTIAL,
            TriggerType.BEHIND_PACE to EscalationLevel.AGGRESSIVE,
            TriggerType.BEHIND_PACE to EscalationLevel.SAVAGE,
            TriggerType.BEHIND_PACE to EscalationLevel.NUCLEAR,
            TriggerType.BEHIND_PACE to EscalationLevel.EXISTENTIAL,
            TriggerType.END_OF_DAY to EscalationLevel.SAVAGE,
            TriggerType.END_OF_DAY to EscalationLevel.NUCLEAR,
            TriggerType.END_OF_DAY to EscalationLevel.EXISTENTIAL,
        )
        assertTrue(
            "insults.json is missing buckets: ${required - present}",
            present.containsAll(required),
        )
    }
}
