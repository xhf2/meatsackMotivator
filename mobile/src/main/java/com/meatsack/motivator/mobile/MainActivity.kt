package com.meatsack.motivator.mobile

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.lifecycleScope
import com.meatsack.motivator.mobile.data.SettingsRepository
import com.meatsack.motivator.mobile.sync.SettingsSyncResult
import com.meatsack.motivator.mobile.ui.navigation.MeatsackNavGraph
import com.meatsack.motivator.mobile.ui.theme.MeatsackTheme
import com.meatsack.motivator.mobile.ui.theme.ThemeChoice
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val settingsRepo = remember { SettingsRepository(applicationContext) }
            val themeChoice by settingsRepo.themeChoice.collectAsState(initial = ThemeChoice.VITALS)
            MeatsackTheme(choice = themeChoice) {
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
