package com.meatsack.motivator.notification

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import com.meatsack.motivator.R
import com.meatsack.motivator.presentation.InsultActivity
import com.meatsack.shared.model.Message

class InsultNotificationService(private val context: Context) {

    companion object {
        private const val TAG = "InsultNotification"
        const val CHANNEL_ID = "meatsack_insults"
        const val EXTRA_MESSAGE_ID = "message_id"
        const val EXTRA_MESSAGE_TEXT = "message_text"
        const val EXTRA_STATS_TEXT = "stats_text"

        // The card "sender" name shown as the notification title.
        private const val SENDER = "meatsackMotivator"

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
        // POST_NOTIFICATIONS is requested at runtime in MainActivity; if the user
        // denied it, posting would be a silent OS-level drop. Make that explicit.
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "POST_NOTIFICATIONS denied; skipping insult notification for id=${message.id}")
            return
        }

        // Assumes message.id fits in an Int (Room autoincrement ids stay well under 2^31);
        // see VoteReceiver.requestCode for the same bounded-id assumption.
        val notifId = message.id.toInt()

        // Tapping the card leads to InsultActivity (where voting happens) via this
        // contentIntent. The card carries NO inline vote actions and no rich expandable
        // content on purpose — a minimal, action-less card is our lever to nudge the tap
        // toward the activity. NOTE: this is not guaranteed — Samsung One UI Watch was
        // observed to open the Wear shade detail view on first tap regardless, launching
        // the activity from its "Open app" affordance. Suppressing that detail step would
        // require a full-screen intent, which we deliberately avoid for Play compliance.
        // Voting lives only in InsultActivity. statsText is not shown on the card but is
        // forwarded so the full-screen view can display it.
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

        // Large icon = the app launcher icon, so the brand icon shows ON the collapsed
        // card (a MessagingStyle avatar only renders in the expanded view, which we no
        // longer use). Rendered to a bitmap so the adaptive launcher icon masks cleanly.
        val largeIcon = ContextCompat.getDrawable(context, R.mipmap.ic_launcher)?.toBitmap()

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.brand_red))
            .setLargeIcon(largeIcon)
            .setContentTitle(SENDER)
            .setContentText(message.text)
            // Full insult (capped at 100 chars upstream) shows un-truncated when the card
            // is opened into the Wear detail view.
            .setStyle(NotificationCompat.BigTextStyle().bigText(message.text))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setTimeoutAfter(NOTIFICATION_TIMEOUT_MS)
            .build()

        NotificationManagerCompat.from(context).notify(INSULT_TAG, notifId, notification)
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
