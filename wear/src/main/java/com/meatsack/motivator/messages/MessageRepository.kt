package com.meatsack.motivator.messages

import com.meatsack.shared.constants.EscalationLevel
import com.meatsack.shared.constants.MessageTone
import com.meatsack.shared.constants.TriggerType
import com.meatsack.shared.db.MessageDao
import com.meatsack.shared.model.Message
import kotlin.random.Random

class MessageRepository(
    private val dao: MessageDao,
    private val random: Random = Random.Default,
) {

    suspend fun selectMessage(
        level: EscalationLevel,
        triggerType: TriggerType,
        tone: MessageTone
    ): Message? {
        val oneDayAgo = System.currentTimeMillis() - 24 * 60 * 60 * 1000
        val eligible = dao.getEligibleMessages(level, triggerType, tone, oneDayAgo)
        if (eligible.isEmpty()) return null

        // Separate unvoted (AI-generated, not yet rated) from voted
        val unvoted = eligible.filter { it.votesUp == 0 && it.votesDown == 0 }
        val voted = eligible.filter { it.votesUp > 0 || it.votesDown > 0 }

        // 30% chance to show an unvoted message if any exist
        val pick = when {
            unvoted.isNotEmpty() && random.nextDouble() < 0.3 -> unvoted.random()
            voted.isNotEmpty() -> weightedRandom(voted)
            // eligible is non-empty (we returned null above) and
            // unvoted + voted partitions eligible exhaustively, so if
            // voted is empty here then unvoted must be non-empty.
            else -> unvoted.random()
        }

        dao.markShown(pick.id, System.currentTimeMillis())
        return pick
    }

    suspend fun voteUp(messageId: Long) {
        dao.voteUp(messageId)
    }

    suspend fun voteDown(messageId: Long) {
        dao.voteDown(messageId)
    }

    private fun weightedRandom(messages: List<Message>): Message {
        val weights = messages.map { maxOf(1, it.votesUp - it.votesDown) }
        val totalWeight = weights.sum()
        var random = this.random.nextInt(totalWeight)
        for (i in messages.indices) {
            random -= weights[i]
            if (random < 0) return messages[i]
        }
        return messages.last()
    }
}
