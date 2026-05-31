package com.meatsack.motivator.mobile

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.lifecycle.lifecycleScope
import com.meatsack.motivator.mobile.sync.SettingsSyncResult
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
                when (val result = app.settingsSyncer.syncNow()) {
                    SettingsSyncResult.Success -> Unit
                    is SettingsSyncResult.Failed ->
                        Log.e(TAG, "onResume syncNow failed", result.error)
                }
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Log.e(TAG, "onResume syncNow unexpected throw", t)
            }
        }
    }
}
