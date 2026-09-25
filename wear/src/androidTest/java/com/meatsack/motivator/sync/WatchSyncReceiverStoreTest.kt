package com.meatsack.motivator.sync

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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression guard for the cooldown-wipe bug: applying a phone `/messages`
 * payload (which always carries lastShownTimestamp = 0) must not reset the
 * watch's own delivery timestamps.
 */
@RunWith(AndroidJUnit4::class)
class WatchSyncReceiverStoreTest {
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun phoneRow(id: Long, up: Int = 0) = Message(
        id = id,
        text = "m$id",
        level = EscalationLevel.AGGRESSIVE,
        triggerType = TriggerType.INACTIVITY,
        tone = MessageTone.FULL_SEND,
        source = MessageSource.PRE_WRITTEN,
        votesUp = up,
        lastShownTimestamp = 0,
    )

    @Test
    fun store_keepsWatchShownTimestamp_andAppliesPhoneVotes() = runBlocking {
        val dao = db.messageDao()
        dao.insertAll(listOf(phoneRow(1)))
        dao.markShown(1, timestamp = 999L)

        WatchSyncReceiver.store(listOf(phoneRow(1, up = 2), phoneRow(2)), dao)

        val rows = dao.getAllMessages().associateBy { it.id }
        assertEquals(999L, rows.getValue(1).lastShownTimestamp)
        assertEquals(2, rows.getValue(1).votesUp)
        assertEquals(0L, rows.getValue(2).lastShownTimestamp)
    }

    @Test
    fun store_leavesRowsAbsentFromPayloadUntouched() = runBlocking {
        val dao = db.messageDao()
        dao.insertAll(listOf(phoneRow(1), phoneRow(3)))
        dao.markShown(3, timestamp = 555L)

        // Payload omits id 3 (e.g. pruned on the phone). The watch never deletes rows.
        WatchSyncReceiver.store(listOf(phoneRow(1)), dao)

        val rows = dao.getAllMessages().associateBy { it.id }
        assertEquals(setOf(1L, 3L), rows.keys)
        assertEquals(555L, rows.getValue(3).lastShownTimestamp)
    }
}
