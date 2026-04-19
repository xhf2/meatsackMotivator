package com.meatsack.motivator.presentation

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.meatsack.motivator.messages.MessageRepository
import com.meatsack.motivator.notification.InsultNotificationService
import com.meatsack.shared.db.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class InsultActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val messageId = intent.getLongExtra(InsultNotificationService.EXTRA_MESSAGE_ID, -1L)
        val messageText = intent.getStringExtra(InsultNotificationService.EXTRA_MESSAGE_TEXT) ?: "MOVE."
        val statsText = intent.getStringExtra(InsultNotificationService.EXTRA_STATS_TEXT) ?: ""

        val db = AppDatabase.getDatabase(applicationContext)
        val repo = MessageRepository(db.messageDao())

        setContent {
            InsultScreen(
                insultText = messageText,
                statsText = statsText,
                onThumbsUp = {
                    CoroutineScope(Dispatchers.IO).launch {
                        if (messageId > 0) repo.voteUp(messageId)
                    }
                    finish()
                },
                onThumbsDown = {
                    CoroutineScope(Dispatchers.IO).launch {
                        if (messageId > 0) repo.voteDown(messageId)
                    }
                    finish()
                },
            )
        }
    }
}
