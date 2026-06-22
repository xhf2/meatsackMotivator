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
}
