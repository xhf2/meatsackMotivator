package com.meatsack.shared.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class VoteSyncSerializerTest {

    @Test fun roundTrip_preservesAllRows() {
        val votes = listOf(
            VoteSnapshot(42, 5, 2),
            VoteSnapshot(7, 0, 1),
            VoteSnapshot(100, 3, 0),
        )
        val serialized = VoteSyncSerializer.serialize(votes)
        assertEquals(votes, VoteSyncSerializer.deserialize(serialized))
    }

    @Test fun deserialize_emptyString_returnsEmptyList() {
        assertEquals(emptyList<VoteSnapshot>(), VoteSyncSerializer.deserialize(""))
    }

    @Test fun deserialize_dropsMalformedLines_keepsValid() {
        // line 2: too few fields (dropped on the field-count guard);
        // line 3: 3 fields but a non-numeric id (dropped on parse failure)
        val data = "42|5|2\nbroken|line\nabc|1|1\n7|0|3"
        assertEquals(
            listOf(VoteSnapshot(42, 5, 2), VoteSnapshot(7, 0, 3)),
            VoteSyncSerializer.deserialize(data),
        )
    }
}
