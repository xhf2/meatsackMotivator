package com.meatsack.shared.diagnostics

import java.io.File

/**
 * A persistent, capped, newline-delimited line buffer backed by a single file. Pure JVM
 * (no Android types) so it unit-tests on the plain JVM; production passes a file under
 * `context.filesDir`. Critically the file survives process/service death — exactly the
 * condition we need to catch (a watch service the OS keeps killing takes its *in-memory*
 * state with it, but not this file).
 *
 * On-device diagnostics tool, first built for the triggering investigation
 * (docs/debug/triggering-investigation.md). Retained as a standing dev-debugging aid — do not strip.
 *
 * Thread-safety: all file operations synchronize on a JVM-wide monitor keyed by the file's
 * absolute path (an interned String), so independent instances pointing at the same file —
 * e.g. a WearableListenerService writing while a ViewModel reads — still serialize correctly.
 */
class DiagnosticsLineStore(
    private val file: File,
    private val maxRows: Int = DEFAULT_MAX_ROWS,
) {
    // Interned path → one canonical monitor per file across all instances in this process.
    private val lock: Any = file.absolutePath.intern()

    /** Append one line, then trim to the most recent [maxRows]. */
    fun append(line: String) = synchronized(lock) {
        writeUnlocked(readUnlocked() + line)
    }

    /**
     * Merge an incoming ordered window into the stored history (see [merge]) and trim.
     * Used by the phone receiver to fold each pushed window into the full record.
     */
    fun appendMerging(incoming: List<String>) = synchronized(lock) {
        writeUnlocked(merge(readUnlocked(), incoming))
    }

    fun read(): List<String> = synchronized(lock) { readUnlocked() }

    fun clear() = synchronized(lock) {
        runCatching { if (file.exists()) file.delete() }
    }

    private fun readUnlocked(): List<String> =
        runCatching {
            if (!file.exists()) emptyList() else file.readText().split("\n").filter { it.isNotEmpty() }
        }.getOrDefault(emptyList())

    private fun writeUnlocked(lines: List<String>) {
        val capped = if (lines.size > maxRows) lines.subList(lines.size - maxRows, lines.size) else lines
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(capped.joinToString("\n"))
        }
    }

    companion object {
        const val DEFAULT_MAX_ROWS = 5000

        /**
         * Fold an [incoming] window of newer lines into [existing], assuming both are ordered
         * and the windows overlap (the watch sends a sliding window each poll). Finds the
         * longest suffix of [existing] that equals a prefix of [incoming] and appends only the
         * non-overlapping remainder — so repeated overlapping pushes reconstruct the full,
         * de-duplicated history. With no overlap it conservatively concatenates.
         */
        fun merge(existing: List<String>, incoming: List<String>): List<String> {
            if (existing.isEmpty()) return incoming
            if (incoming.isEmpty()) return existing
            val maxK = minOf(existing.size, incoming.size)
            for (k in maxK downTo 1) {
                if (existing.subList(existing.size - k, existing.size) == incoming.subList(0, k)) {
                    return existing + incoming.subList(k, incoming.size)
                }
            }
            return existing + incoming
        }
    }
}
