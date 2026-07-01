package com.meatsack.shared.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsSerializerTest {

    @Test fun roundTrip_preservesLines() {
        val lines = listOf("06-30 07:00:01 POLL idle=540", "06-30 07:00:01 FIRE level=4")
        assertEquals(lines, DiagnosticsSerializer.deserialize(DiagnosticsSerializer.serialize(lines)))
    }

    @Test fun deserialize_emptyString_returnsEmptyList() {
        assertEquals(emptyList<String>(), DiagnosticsSerializer.deserialize(""))
    }

    @Test fun serialize_emptyList_returnsEmptyString() {
        assertEquals("", DiagnosticsSerializer.serialize(emptyList()))
    }

    @Test fun deserialize_dropsBlankLines() {
        // A trailing separator (or stray blank) must not surface as an empty entry.
        assertEquals(listOf("a", "b"), DiagnosticsSerializer.deserialize("a\nb\n"))
    }

    @Test fun maxDiagRows_worstCasePayload_underDataItemLimit() {
        // Backs the sizing claim in SyncChannel.MAX_DIAG_ROWS: a full window of long lines
        // stays well under the ~100 KB Data Layer DataItem limit.
        val line = "X".repeat(180)
        val lines = List(SyncChannel.MAX_DIAG_ROWS) { line }
        val serialized = DiagnosticsSerializer.serialize(lines)
        assertTrue("payload was ${serialized.length} chars", serialized.length < 100_000)
    }
}
