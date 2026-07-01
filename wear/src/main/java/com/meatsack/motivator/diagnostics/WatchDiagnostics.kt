package com.meatsack.motivator.diagnostics

import android.content.Context
import com.meatsack.shared.diagnostics.DiagnosticsLineStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Watch-side diagnostics recorder for the triggering investigation
 * (docs/debug/triggering-investigation.md). Stamps each event with wall-clock time and
 * appends it to a persistent on-disk buffer that survives the service/process kills we're
 * trying to catch (hypothesis H2). The full record lives here; [WatchDiagnosticsSender]
 * pushes a recent window to the phone each poll.
 *
 * Temporary — delete with the rest of the diagnostics pipe once the bug is fixed.
 */
class WatchDiagnostics(private val store: DiagnosticsLineStore) {

    // MM-dd so a multi-day record (e.g. overnight → morning) stays unambiguous.
    private val format = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    /** Record one event. Newlines are stripped and the text capped so a line stays one wire row. */
    fun log(text: String) {
        store.append("${format.format(Date())} ${text.replace('\n', ' ').take(180)}")
    }

    fun recent(): List<String> = store.read()

    fun clear() = store.clear()

    companion object {
        const val FILE_NAME = "watch_diagnostics.log"

        fun create(context: Context): WatchDiagnostics =
            WatchDiagnostics(DiagnosticsLineStore(File(context.filesDir, FILE_NAME)))
    }
}
