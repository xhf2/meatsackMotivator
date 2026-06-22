package com.meatsack.shared.sync

/** One message's vote counts, keyed by message id. */
data class VoteSnapshot(val id: Long, val votesUp: Int, val votesDown: Int)

/**
 * Symmetric (de)serializer for the `/votes` Wear Data Layer channel (watch → phone).
 * One snapshot per `\n`-separated line, 3 `|`-separated fields: id, votesUp, votesDown.
 *
 * Malformed lines are dropped (logged at WARN) rather than throwing, so a corrupt
 * payload never takes down the receiver. Mirrors MessageSerializer.
 */
object VoteSyncSerializer {
    private const val FIELD_COUNT = 3
    private const val FIELD_SEPARATOR = "|"
    private const val LINE_SEPARATOR = "\n"

    fun serialize(votes: List<VoteSnapshot>): String =
        votes.joinToString(LINE_SEPARATOR) { v ->
            listOf(v.id, v.votesUp, v.votesDown).joinToString(FIELD_SEPARATOR)
        }

    fun deserialize(data: String): List<VoteSnapshot> {
        if (data.isEmpty()) return emptyList()
        return data.split(LINE_SEPARATOR).mapNotNull { parseLine(it) }
    }

    private fun parseLine(line: String): VoteSnapshot? {
        val parts = line.split(FIELD_SEPARATOR)
        if (parts.size != FIELD_COUNT) {
            android.util.Log.w(
                "VoteSyncSerializer",
                "Dropped line with ${parts.size} fields (expected $FIELD_COUNT): ${line.take(80)}",
            )
            return null
        }
        return runCatching {
            VoteSnapshot(
                id = parts[0].toLong(),
                votesUp = parts[1].toInt(),
                votesDown = parts[2].toInt(),
            )
        }.onFailure { error ->
            android.util.Log.w("VoteSyncSerializer", "Dropped malformed line: ${line.take(80)}", error)
        }.getOrNull()
    }
}
