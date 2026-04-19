package com.meatsack.motivator

import android.app.Application
import android.content.Intent

class MeatsackWearApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val intent = Intent(this, MeatsackWearService::class.java)
        startForegroundService(intent)
    }
}
