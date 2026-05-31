package com.meatsack.motivator.mobile

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.lifecycle.lifecycleScope
import com.meatsack.motivator.mobile.ui.navigation.MeatsackNavGraph
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                MeatsackNavGraph()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val app = applicationContext as MeatsackMobileApp
        lifecycleScope.launch {
            try {
                app.settingsSyncer.syncNow()
            } catch (t: Throwable) {
                Log.w(TAG, "onResume syncNow failed", t)
            }
        }
    }
}
