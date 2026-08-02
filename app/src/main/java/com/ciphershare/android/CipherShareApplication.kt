package com.ciphershare.android

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class CipherShareApplication : Application() {

    companion object {
        const val CHANNEL_STATUS = "ciphershare_status"
        const val CHANNEL_EVENTS = "ciphershare_events"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = getSystemService(NotificationManager::class.java)

        val statusChannel = NotificationChannel(
            CHANNEL_STATUS,
            "Running status",
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = "Shows that CipherShare is running and discoverable"
        }

        val eventsChannel = NotificationChannel(
            CHANNEL_EVENTS,
            "Transfers & devices",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Incoming transfer requests, completed transfers, and new devices found"
        }

        manager.createNotificationChannel(statusChannel)
        manager.createNotificationChannel(eventsChannel)
    }
}
