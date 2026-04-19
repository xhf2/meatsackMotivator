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
import com.meatsack.motivator.presentation.InsultActivity
import com.meatsack.shared.model.Message

class InsultNotificationService(private val context: Context) {

    companion object {
        const val CHANNEL_ID = "meatsack_insults"
        const val NOTIFICATION_ID = 1
        const val EXTRA_MESSAGE_ID = "message_id"
        const val EXTRA_MESSAGE_TEXT = "message_text"
        const val EXTRA_STATS_TEXT = "stats_text"
    }

    init {
        createNotificationChannel()
    }

    fun deliverInsult(message: Message, statsText: String) {
        vibrate()
        showFullScreenNotification(message, statsText)
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

    private fun showFullScreenNotification(message: Message, statsText: String) {
        val intent = Intent(context, InsultActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_MESSAGE_ID, message.id)
            putExtra(EXTRA_MESSAGE_TEXT, message.text)
            putExtra(EXTRA_STATS_TEXT, statsText)
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("meatsackMotivator")
            .setContentText(message.text)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(pendingIntent, true)
            .setAutoCancel(true)
            .build()

        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
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
