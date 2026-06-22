package com.meatsack.motivator.presentation

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.meatsack.motivator.MeatsackWearApp
import com.meatsack.motivator.messages.MessageRepository
import com.meatsack.motivator.notification.InsultNotificationService
import com.meatsack.motivator.sync.WatchVoteSender
import com.meatsack.shared.db.AppDatabase
import kotlinx.coroutines.launch

class InsultActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val messageId = intent.getLongExtra(InsultNotificationService.EXTRA_MESSAGE_ID, -1L)
        val messageText = intent.getStringExtra(InsultNotificationService.EXTRA_MESSAGE_TEXT) ?: "MOVE."
        val statsText = intent.getStringExtra(InsultNotificationService.EXTRA_STATS_TEXT) ?: ""

        // App-scope outlives this activity's finish() so vote writes complete.
        val appScope = (application as MeatsackWearApp).applicationScope
        val db = AppDatabase.getDatabase(applicationContext)
        val repo = MessageRepository(db.messageDao())
        val voteSender = WatchVoteSender(applicationContext)

        setContent {
            InsultScreen(
                insultText = messageText,
                statsText = statsText,
                onThumbsUp = {
                    recordVote(appScope, voteSender, messageId) { repo.voteUp(it) }
                    finish()
                },
                onThumbsDown = {
                    recordVote(appScope, voteSender, messageId) { repo.voteDown(it) }
                    finish()
                },
            )
        }
    }

    private inline fun recordVote(
        scope: kotlinx.coroutines.CoroutineScope,
        voteSender: WatchVoteSender,
        messageId: Long,
        crossinline write: suspend (Long) -> Unit,
    ) {
        if (messageId <= 0L) return
        scope.launch {
            try {
                write(messageId)
                // Best-effort back-sync; the vote is already persisted locally and the
                // next vote re-sends the full snapshot, so a failure here is non-fatal.
                voteSender.syncVotesToPhone()
            } catch (t: Throwable) {
                Log.e("InsultActivity", "Vote write failed for id=$messageId", t)
            }
        }
    }
}
