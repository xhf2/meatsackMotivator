package com.meatsack.motivator.messages

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.meatsack.shared.constants.EscalationLevel
import com.meatsack.shared.constants.MessageSource
import com.meatsack.shared.constants.MessageTone
import com.meatsack.shared.constants.TriggerType
import com.meatsack.shared.db.AppDatabase
import com.meatsack.shared.model.Message
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.random.Random

@RunWith(AndroidJUnit4::class)
class MessageRepositoryTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: MessageRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        repo = MessageRepository(db.messageDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun testMsg(text: String, level: EscalationLevel = EscalationLevel.AGGRESSIVE) = Message(
        text = text, level = level,
        triggerType = TriggerType.INACTIVITY, tone = MessageTone.FULL_SEND,
        source = MessageSource.PRE_WRITTEN,
    )

    @Test
    fun selectsMessageFromCorrectLevel() = runBlocking {
        db.messageDao().insertAll(listOf(
            testMsg("Level 1 msg", EscalationLevel.AGGRESSIVE),
            testMsg("Level 2 msg", EscalationLevel.SAVAGE),
        ))
        val msg = repo.selectMessage(EscalationLevel.AGGRESSIVE, TriggerType.INACTIVITY, MessageTone.FULL_SEND)
        assertNotNull(msg)
        assertEquals("Level 1 msg", msg!!.text)
    }

    @Test
    fun returnsNullWhenNoMessages() = runBlocking {
        val msg = repo.selectMessage(EscalationLevel.AGGRESSIVE, TriggerType.INACTIVITY, MessageTone.FULL_SEND)
        assertNull(msg)
    }

    @Test
    fun voteUpIncrementsScore() = runBlocking {
        db.messageDao().insertAll(listOf(testMsg("Great insult")))
        val msg = db.messageDao().getAllMessages().first()
        repo.voteUp(msg.id)
        repo.voteUp(msg.id)
        val updated = db.messageDao().getAllMessages().first()
        assertEquals(2, updated.votesUp)
    }

    @Test
    fun weightedRandomHonorsDistribution() = runBlocking {
        // Insert three voted messages with distinct net scores to
        // create weights = [3, 2, 1]. A deterministic Random with a
        // known seed lets us verify the algorithm's picks without
        // flaky probabilistic asserts.
        db.messageDao().insertAll(listOf(
            testMsg("weight-3"),
            testMsg("weight-2"),
            testMsg("weight-1"),
        ))
        val all = db.messageDao().getAllMessages()
        // getAllMessages orders by (votesUp - votesDown) DESC, but since
        // none are voted yet, the ORDER BY falls through and we rely on
        // insertion order. Assert the invariant we need:
        val w3 = all.first { it.text == "weight-3" }
        val w2 = all.first { it.text == "weight-2" }
        val w1 = all.first { it.text == "weight-1" }
        // Set each message's votesUp so net-score differs: 3, 2, 1.
        repeat(3) { db.messageDao().voteUp(w3.id) }
        repeat(2) { db.messageDao().voteUp(w2.id) }
        repeat(1) { db.messageDao().voteUp(w1.id) }
        // With a seeded Random, call selectMessage many times and tally
        // the distribution. We want to confirm ALL THREE buckets are
        // reachable (in particular that the last element is no longer
        // starved by the bias bug that prompted this fix).
        val seededRepo = MessageRepository(db.messageDao(), Random(seed = 42L))
        val counts = mutableMapOf("weight-3" to 0, "weight-2" to 0, "weight-1" to 0)
        repeat(1000) {
            // Reset lastShownTimestamp each iteration so the 24h cutoff
            // doesn't exclude previously-shown rows on re-selection.
            listOf(w3, w2, w1).forEach { db.messageDao().markShown(it.id, 0L) }
            val picked = seededRepo.selectMessage(
                EscalationLevel.AGGRESSIVE, TriggerType.INACTIVITY, MessageTone.FULL_SEND
            )
            counts[picked!!.text] = counts.getValue(picked.text) + 1
        }
        // Every bucket should be hit with a 3/2/1 seed distribution over
        // 1000 iterations. Loose bounds avoid spurious failures from
        // PRNG variance; the key regression guard is that all three > 0.
        assertTrue("weight-3 must win often", counts.getValue("weight-3") > 300)
        assertTrue("weight-2 must win often", counts.getValue("weight-2") > 150)
        assertTrue("weight-1 must be reachable (regression guard)", counts.getValue("weight-1") > 50)
    }

    @Test
    fun voteDownIncrementsCount() = runBlocking {
        db.messageDao().insertAll(listOf(testMsg("Bad insult")))
        val msg = db.messageDao().getAllMessages().first()
        repo.voteDown(msg.id)
        repo.voteDown(msg.id)
        val updated = db.messageDao().getAllMessages().first()
        assertEquals(2, updated.votesDown)
    }
}
