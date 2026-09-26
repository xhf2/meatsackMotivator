package com.meatsack.motivator.mobile.sync

import com.meatsack.shared.constants.GenerationLimits.RETIRE_DOWNVOTES
import com.meatsack.shared.model.Message

/**
 * Pure selection of which rows go into a `/messages` push. Kept out of [PhoneSyncSender]
 * so the rule is JVM-testable without Room or the Wearable client.
 *
 * Every row is a candidate — including `votesDown >= RETIRE_DOWNVOTES` (retired) and
 * `isActive = 0` (archived). The watch's `getEligibleMessages()` hides both, so sending
 * them can't make them fire — but *omitting* them means a phone-side retire/archive never
 * reaches the watch, whose stale copy would keep firing. That is why the payload is
 * ordered hidden-first: when the library exceeds [CACHE_SIZE], the rows that fall off are
 * fireable ones the watch merely lacks, never the ones it must stop firing.
 */
object SyncPayload {
    /**
     * Maximum messages pushed in a single DataItem. Sized to comfortably hold the bundled
     * seed plus headroom for AI-generated growth, while staying well under Wear's ~100 KB
     * DataItem limit (200 × ≤100 chars × 2 bytes ≈ 40 KB).
     *
     * Was 50 in v1 when the seed had only 49 INACTIVITY rows; that ceiling silently
     * truncated v2 seed rows on phones with no voted messages, meaning watch-side workers
     * couldn't find BEHIND_PACE/END_OF_DAY messages and always fell back to the INACTIVITY pool.
     */
    const val CACHE_SIZE = 200

    /** [messages] is what gets sent; [dropped] is how many rows did not fit. */
    data class Selection(val messages: List<Message>, val dropped: Int)

    /** True when the watch hides this row by query, i.e. it must reach the watch to *stop* firing. */
    fun isHiddenOnWatch(m: Message): Boolean = !m.isActive || m.votesDown >= RETIRE_DOWNVOTES

    /** Hidden rows first, then fireable rows, each group in the input (net-score) order; capped at [CACHE_SIZE]. */
    fun select(all: List<Message>): Selection {
        val (hidden, fireable) = all.partition(::isHiddenOnWatch)
        val ordered = hidden + fireable
        return Selection(ordered.take(CACHE_SIZE), (ordered.size - CACHE_SIZE).coerceAtLeast(0))
    }
}
