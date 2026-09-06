package com.meatsack.motivator.mobile.ui.library

import com.meatsack.shared.model.Message

/**
 * Pins the Library's display order to the order first observed, so a vote
 * (which changes the DAO's net-score ORDER BY) doesn't move the card out from
 * under the user's finger. Counts still update live; only position is stable.
 *
 * - The order is captured from the first NON-EMPTY list (an empty first
 *   emission — fresh DB — must not freeze an empty order).
 * - Ids not in the captured order (e.g. new AI-generated rows) are appended
 *   after all known rows, in their incoming order, and are then remembered.
 * - Ids that disappear (pruned) simply drop out.
 *
 * Lives for the ViewModel's lifetime: the order re-freezes when the screen is
 * next created (app relaunch), which is when a user expects a re-sort.
 */
class FrozenOrder {
    private val rank = LinkedHashMap<Long, Int>()

    fun apply(messages: List<Message>): List<Message> {
        if (messages.isEmpty()) return messages
        for (m in messages) if (m.id !in rank) rank[m.id] = rank.size
        return messages.sortedBy { rank.getValue(it.id) }
    }
}
