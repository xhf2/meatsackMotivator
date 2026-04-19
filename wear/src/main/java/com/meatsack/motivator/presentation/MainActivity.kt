package com.meatsack.motivator.presentation

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.meatsack.motivator.MeatsackWearService

class MainActivity : ComponentActivity() {

    private val requiredPermissions: Array<String>
        get() = buildList {
            add(Manifest.permission.ACTIVITY_RECOGNITION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // API 33+: notifications silently drop without this grant.
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        // Require ACTIVITY_RECOGNITION for FGS type=health. POST_NOTIFICATIONS is
        // nice-to-have — without it we start anyway but warn.
        val activityOk = results[Manifest.permission.ACTIVITY_RECOGNITION] == true
        if (activityOk) {
            startServiceAndFinish()
        } else {
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            startServiceAndFinish()
        } else {
            requestPermissionsLauncher.launch(missing.toTypedArray())
        }
    }

    private fun startServiceAndFinish() {
        startForegroundService(Intent(this, MeatsackWearService::class.java))
        finish()
    }
}
