package com.meatsack.motivator.debug

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.activity.ComponentActivity
import com.meatsack.motivator.notification.InsultNotificationService
import com.meatsack.motivator.presentation.InsultActivity

class TestFireActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val text = intent.getStringExtra("text")
            ?: "GET UP, you cloud-native pile of laundry."
        val stats = intent.getStringExtra("stats") ?: "42 steps. TEST fire."

        Log.d("TestFireActivity", "Forwarding to InsultActivity: $text")

        vibrate()

        startActivity(
            Intent(this, InsultActivity::class.java).apply {
                putExtra(InsultNotificationService.EXTRA_MESSAGE_ID, -1L)
                putExtra(InsultNotificationService.EXTRA_MESSAGE_TEXT, text)
                putExtra(InsultNotificationService.EXTRA_STATS_TEXT, stats)
            },
        )
        finish()
    }

    private fun vibrate() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager
            manager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as Vibrator
        }
        vibrator.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))
    }
}
