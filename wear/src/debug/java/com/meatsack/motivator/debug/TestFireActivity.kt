package com.meatsack.motivator.debug

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import com.meatsack.motivator.MeatsackWearApp
import com.meatsack.motivator.notification.InsultNotificationService
import com.meatsack.shared.constants.EscalationLevel
import com.meatsack.shared.constants.MessageSource
import com.meatsack.shared.constants.MessageTone
import com.meatsack.shared.constants.TriggerType
import com.meatsack.shared.db.AppDatabase
import com.meatsack.shared.model.Message
import kotlinx.coroutines.launch

/**
 * Debug-only. Fires a REAL insult notification (the production delivery path) so the
 * notification + 👎/👍 voting + wrist-raise surfacing can be verified on-device:
 *
 *   adb shell am start -n com.meatsack.motivator/.debug.TestFireActivity
 *
 * Picks the top message from the DB so the vote lands on a real row; falls back to a
 * throwaway message (id = -1, vote is a no-op) if the DB is empty.
 */
class TestFireActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val stats = intent.getStringExtra("stats") ?: "42 steps. TEST fire."
        val fallbackText = intent.getStringExtra("text")
            ?: "GET UP, you cloud-native pile of laundry."

        val app = application as MeatsackWearApp
        val notifier = InsultNotificationService(applicationContext)

        app.applicationScope.launch {
            val message = AppDatabase.getDatabase(applicationContext)
                .messageDao()
                .getAllMessages()
                .firstOrNull()
                ?: Message(
                    text = fallbackText,
                    level = EscalationLevel.AGGRESSIVE,
                    triggerType = TriggerType.INACTIVITY,
                    tone = MessageTone.FULL_SEND,
                    source = MessageSource.PRE_WRITTEN,
                )
            Log.d("TestFireActivity", "Firing test insult notification: ${message.text}")
            notifier.deliverInsult(message, stats)
        }

        finish()
    }
}
