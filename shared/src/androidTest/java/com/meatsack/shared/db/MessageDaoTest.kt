package com.meatsack.shared.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.meatsack.shared.constants.EscalationLevel
import com.meatsack.shared.constants.MessageSource
import com.meatsack.shared.constants.MessageTone
import com.meatsack.shared.constants.TriggerType
import com.meatsack.shared.model.Message
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MessageDaoTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: MessageDao

    private fun testMessage(
        text: String = "Test insult",
        level: EscalationLevel = EscalationLevel.AGGRESSIVE,
        triggerType: TriggerType = TriggerType.INACTIVITY,
        tone: MessageTone = MessageTone.FULL_SEND,
    ) = Message(
        text = text,
        level = level,
        triggerType = triggerType,
        tone = tone,
        source = MessageSource.PRE_WRITTEN,
    )

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        dao = db.messageDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun insertAndRetrieveMessages() = runBlocking {
        val messages = listOf(
            testMessage("GET UP, you lazy sack of shit."),
            testMessage("Are you glued to that chair?"),
        )
        dao.insertAll(messages)
        assertEquals(2, dao.getMessageCount())
    }

    @Test
    fun getEligibleMessagesFiltersCorrectly() = runBlocking {
        dao.insertAll(
            listOf(
                testMessage("Level 1 inactivity", level = EscalationLevel.AGGRESSIVE),
                testMessage("Level 2 inactivity", level = EscalationLevel.SAVAGE),
                testMessage("Level 1 behind pace", triggerType = TriggerType.BEHIND_PACE),
            ),
        )
        val results = dao.getEligibleMessages(
            EscalationLevel.AGGRESSIVE,
            TriggerType.INACTIVITY,
            MessageTone.FULL_SEND,
            cutoffTimestamp = 0,
        )
        assertEquals(1, results.size)
        assertEquals("Level 1 inactivity", results[0].text)
    }

    @Test
    fun votesDownHidesMessage() = runBlocking {
        dao.insertAll(listOf(testMessage("Bad insult")))
        val msg = dao.getAllMessages().first()
        dao.voteDown(msg.id)
        dao.voteDown(msg.id)
        dao.voteDown(msg.id)
        val results = dao.getEligibleMessages(
            EscalationLevel.AGGRESSIVE,
            TriggerType.INACTIVITY,
            MessageTone.FULL_SEND,
            cutoffTimestamp = 0,
        )
        assertTrue(results.isEmpty())
    }

    @Test
    fun recentlyShownMessagesExcluded() = runBlocking {
        dao.insertAll(listOf(testMessage("Recent insult")))
        val msg = dao.getAllMessages().first()
        val now = System.currentTimeMillis()
        dao.markShown(msg.id, now)
        val results = dao.getEligibleMessages(
            EscalationLevel.AGGRESSIVE,
            TriggerType.INACTIVITY,
            MessageTone.FULL_SEND,
            cutoffTimestamp = now - 1000,
        )
        assertTrue(results.isEmpty())
    }

    @Test
    fun voteUpIncrementsCount() = runBlocking {
        dao.insertAll(listOf(testMessage("Great insult")))
        val msg = dao.getAllMessages().first()
        dao.voteUp(msg.id)
        dao.voteUp(msg.id)
        val updated = dao.getAllMessages().first()
        assertEquals(2, updated.votesUp)
    }
}
