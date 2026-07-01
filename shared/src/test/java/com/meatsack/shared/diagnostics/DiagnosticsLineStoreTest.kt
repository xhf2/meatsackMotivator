package com.meatsack.shared.diagnostics

import com.meatsack.shared.diagnostics.DiagnosticsLineStore.Companion.merge
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class DiagnosticsLineStoreTest {

    private fun tempFile(): File =
        File.createTempFile("diag", ".log").also {
            it.deleteOnExit()
            it.delete()
        }

    @Test fun append_thenRead_roundTrips() {
        val store = DiagnosticsLineStore(tempFile())
        store.append("one")
        store.append("two")
        assertEquals(listOf("one", "two"), store.read())
    }

    @Test fun read_missingFile_returnsEmpty() {
        assertEquals(emptyList<String>(), DiagnosticsLineStore(tempFile()).read())
    }

    @Test fun append_capsToMaxRows_keepingNewest() {
        val store = DiagnosticsLineStore(tempFile(), maxRows = 3)
        (1..5).forEach { store.append("line$it") }
        assertEquals(listOf("line3", "line4", "line5"), store.read())
    }

    @Test fun clear_removesAllLines() {
        val store = DiagnosticsLineStore(tempFile())
        store.append("x")
        store.clear()
        assertEquals(emptyList<String>(), store.read())
    }

    @Test fun appendMerging_overlappingWindows_reconstructFullHistory() {
        val store = DiagnosticsLineStore(tempFile())
        store.appendMerging(listOf("a", "b", "c"))
        store.appendMerging(listOf("b", "c", "d")) // slides forward by one
        store.appendMerging(listOf("c", "d", "e"))
        assertEquals(listOf("a", "b", "c", "d", "e"), store.read())
    }

    @Test fun merge_emptyExisting_returnsIncoming() {
        assertEquals(listOf("a", "b"), merge(emptyList(), listOf("a", "b")))
    }

    @Test fun merge_noOverlap_concatenates() {
        // Defensive path: a gap (e.g. across a long outage) appends rather than dropping data.
        assertEquals(listOf("a", "b", "x", "y"), merge(listOf("a", "b"), listOf("x", "y")))
    }

    @Test fun merge_fullDuplicateWindow_isIdempotent() {
        assertEquals(listOf("a", "b", "c"), merge(listOf("a", "b", "c"), listOf("a", "b", "c")))
    }
}
