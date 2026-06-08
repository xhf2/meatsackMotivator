package com.meatsack.motivator.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.meatsack.motivator.MeatsackWearApp
import com.meatsack.motivator.messages.MessageRepository
import com.meatsack.shared.db.AppDatabase
import kotlinx.coroutines.launch

/**
 * Records a 👍/👎 vote tapped directly on an insult notification, then dismisses
 * that notification. Runs on the app scope so the DB write outlives this receiver.
 */
class VoteReceiver : BroadcastReceiver() {

    /** Pure result of interpreting a vote intent — unit-testable without Android. */
    sealed class VoteAction {
        data class Up(val messageId: Long) : VoteAction()
        data class Down(val messageId: Long) : VoteAction()
        object Ignore : VoteAction()

        companion object {
            fun from(messageId: Long, isUp: Boolean): VoteAction = when {
                messageId <= 0L -> Ignore
                isUp -> Up(messageId)
                else -> Down(messageId)
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val messageId = intent.getLongExtra(InsultNotificationService.EXTRA_MESSAGE_ID, -1L)
        val isUp = intent.getBooleanExtra(EXTRA_VOTE_UP, true)
        val notifId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)

        // Acknowledge the tap immediately by dismissing the notification.
        if (notifId > 0) NotificationManagerCompat.from(context).cancel(notifId)

        val action = VoteAction.from(messageId, isUp)
        if (action is VoteAction.Ignore) return

        val app = context.applicationContext as MeatsackWearApp
        val pending = goAsync()
        app.applicationScope.launch {
            try {
                val repo = MessageRepository(AppDatabase.getDatabase(context).messageDao())
                when (action) {
                    is VoteAction.Up -> repo.voteUp(action.messageId)
                    is VoteAction.Down -> repo.voteDown(action.messageId)
                    VoteAction.Ignore -> Unit
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Vote write failed for id=$messageId", t)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "VoteReceiver"
        const val ACTION_VOTE = "com.meatsack.motivator.ACTION_VOTE"
        const val EXTRA_VOTE_UP = "vote_up"
        const val EXTRA_NOTIFICATION_ID = "notification_id"

        /**
         * Unique request code per (message, direction). FLAG_IMMUTABLE PendingIntents
         * with identical request codes would otherwise alias and reuse stale extras.
         */
        fun requestCode(messageId: Long, isUp: Boolean): Int =
            (messageId.toInt() shl 1) or (if (isUp) 1 else 0)
    }
}
