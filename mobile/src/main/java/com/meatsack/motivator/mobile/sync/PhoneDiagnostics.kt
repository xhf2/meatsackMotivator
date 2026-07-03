package com.meatsack.motivator.mobile.sync

import android.content.Context
import com.meatsack.shared.diagnostics.DiagnosticsLineStore
import java.io.File

/**
 * Phone-side home for the watch's triggering diagnostics
 * (docs/debug/triggering-investigation.md). The receiver folds each pushed window into this
 * file and the Debug screen reads it back. A larger cap than the wire window so the phone
 * accumulates several days of the watch's history even though each push is bounded.
 *
 * Retained on-device dev/diagnostics tool (see docs/debug/triggering-investigation.md).
 */
object PhoneDiagnostics {
    const val FILE_NAME = "phone_diagnostics.log"
    const val MAX_ROWS = 10_000

    fun store(context: Context): DiagnosticsLineStore =
        DiagnosticsLineStore(File(context.filesDir, FILE_NAME), MAX_ROWS)
}
