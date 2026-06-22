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
}
