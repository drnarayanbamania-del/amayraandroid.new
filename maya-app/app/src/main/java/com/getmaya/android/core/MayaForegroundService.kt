package com.getmaya.android.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder

/** Persistent foreground microphone service (foregroundServiceType=microphone). */
class MayaForegroundService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Maya", NotificationManager.IMPORTANCE_LOW)
        )
        startForeground(1, buildNotification())
        // TODO: own wake-word loop (assets/wakeword/*.tflite) feeds GeminiLiveClient
        return START_STICKY
    }

    private fun buildNotification(): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Maya")
            .setContentText("Listening for \"Hey Maya\"")
            .setSmallIcon(com.getmaya.android.R.drawable.ic_launcher_foreground)
            .build()

    companion object { const val CHANNEL_ID = "maya_core" }
}
