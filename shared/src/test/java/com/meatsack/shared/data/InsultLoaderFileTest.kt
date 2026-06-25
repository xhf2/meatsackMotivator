package com.meatsack.shared.data

import com.meatsack.shared.constants.EscalationLevel
import com.meatsack.shared.constants.MessageSource
import com.meatsack.shared.constants.TriggerType
import org.junit.Assert.assertEquals
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
    fun bundledFile_parsesToExpectedCount() {
        assertEquals(69, bundledInsults().size)
    }

    @Test
    fun bundledFile_allRecordsArePreWritten() {
        assertTrue(bundledInsults().all { it.source == MessageSource.PRE_WRITTEN })
    }

    @Test
    fun bundledFile_coversEveryTriggerLevelBucket() {
        val present = bundledInsults().map { it.triggerType to it.level }.toSet()
        val expected = setOf(
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
        assertEquals(expected, present)
    }
}
