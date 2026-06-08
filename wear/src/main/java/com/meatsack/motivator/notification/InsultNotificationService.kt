package com.meatsack.motivator.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.meatsack.motivator.R
import com.meatsack.motivator.presentation.InsultActivity
import com.meatsack.shared.model.Message

class InsultNotificationService(private val context: Context) {

    companion object {
        const val CHANNEL_ID = "meatsack_insults"
        const val EXTRA_MESSAGE_ID = "message_id"
        const val EXTRA_MESSAGE_TEXT = "message_text"
        const val EXTRA_STATS_TEXT = "stats_text"

        // Each insult is its own notification (id = message.id) and auto-expires so
        // the stream can't grow unbounded.
        private const val NOTIFICATION_TIMEOUT_MS = 30L * 60L * 1000L

        const val INSULT_TAG = "insult"
    }

    init {
        createNotificationChannel()
    }

    fun deliverInsult(message: Message, statsText: String) {
        vibrate()
        showInsultNotification(message, statsText)
    }

    private fun vibrate() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            manager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        val effect = VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE)
        vibrator.vibrate(effect)
    }

    private fun showInsultNotification(message: Message, statsText: String) {
        val notifId = message.id.toInt()

        val contentIntent = PendingIntent.getActivity(
            context,
            notifId,
            Intent(context, InsultActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_MESSAGE_ID, message.id)
                putExtra(EXTRA_MESSAGE_TEXT, message.text)
                putExtra(EXTRA_STATS_TEXT, statsText)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(message.text)
            .setContentText(statsText)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(message.text)
                    .setSummaryText(statsText),
            )
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setTimeoutAfter(NOTIFICATION_TIMEOUT_MS)
            .addAction(R.drawable.ic_thumb_down, "👎", votePendingIntent(message.id, notifId, isUp = false))
            .addAction(R.drawable.ic_thumb_up, "👍", votePendingIntent(message.id, notifId, isUp = true))
            .build()

        NotificationManagerCompat.from(context).notify(INSULT_TAG, notifId, notification)
    }

    private fun votePendingIntent(messageId: Long, notifId: Int, isUp: Boolean): PendingIntent {
        val intent = Intent(context, VoteReceiver::class.java).apply {
            action = VoteReceiver.ACTION_VOTE
            putExtra(EXTRA_MESSAGE_ID, messageId)
            putExtra(VoteReceiver.EXTRA_VOTE_UP, isUp)
            putExtra(VoteReceiver.EXTRA_NOTIFICATION_ID, notifId)
        }
        return PendingIntent.getBroadcast(
            context,
            VoteReceiver.requestCode(messageId, isUp),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Insult Notifications",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Aggressive motivational messages"
            enableVibration(false)
        }
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }
}
