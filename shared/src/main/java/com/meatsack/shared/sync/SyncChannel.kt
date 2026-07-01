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
    const val PATH_VOTES = "/votes"
    const val KEY_VOTE_DATA = "vote_data"

    /**
     * Max vote rows in one /votes payload. The watch caps its send to this so the
     * phone never has to reject a legitimate back-sync; well under the ~100 KB
     * DataItem limit (2000 rows x ~13 chars) and far above any realistic voted-
     * message count.
     */
    const val MAX_VOTE_ROWS = 2000

    // --- Temporary triggering-diagnostics pipe (watch → phone). See
    // docs/debug/triggering-investigation.md. Remove once the triggering bug is fixed.
    const val PATH_DIAGNOSTICS = "/diagnostics"
    const val KEY_DIAG_DATA = "diag_data"

    /**
     * Max diagnostic lines in one /diagnostics payload. The watch holds the full
     * record on disk and only pushes the most recent [MAX_DIAG_ROWS] lines each poll;
     * the phone overlap-merges successive windows into its full history. Sized so a
     * worst-case payload (400 rows x ~180 chars ≈ 72 KB) stays under the ~100 KB
     * DataItem limit while still overlapping consecutive 60 s polls by hundreds of
     * lines (polls add only 1–3 lines), keeping the merge unambiguous.
     */
    const val MAX_DIAG_ROWS = 400
}
