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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

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
}
