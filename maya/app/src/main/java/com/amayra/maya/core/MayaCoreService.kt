package com.amayra.maya.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.amayra.maya.MainActivity
import com.amayra.maya.MayaApplication

/**
 * Foreground assistant runtime. Keeps Maya available for wake words, SOS and
 * automation while respecting Android background limits (specialUse FGS).
 */
class MayaCoreService : Service() {

    /** Partial wakelock: ColorOS Hans-freezer does not freeze wakelock holders,
     *  so standby (and later wake-word) stays alive with the screen off. */
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        acquireWakelock()
        MayaLog.i("CORE", "MayaCoreService created")
    }

    private fun acquireWakelock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "maya:standby").apply {
            setReferenceCounted(false)
            acquire(6 * 60 * 60 * 1000L) // 6h safety cap; service restart re-acquires
        }
        MayaLog.i("CORE", "Standby wakelock held (Hans-freeze guard)")
    }

    private fun releaseWakelock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    override fun onDestroy() {
        releaseWakelock()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SLEEP -> {
                MayaApplication.get(this).core.sleep()
                releaseWakelock()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
        }
        startForeground(NOTIF_ID, buildNotification())
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val sleep = PendingIntent.getService(
            this, 1,
            Intent(this, MayaCoreService::class.java).setAction(ACTION_SLEEP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL_ASSISTANT)
            .setContentTitle("Maya is on standby")
            .setContentText("Tap to open · long-press to configure")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Sleep", sleep).build())
            .setOngoing(true)
            .build()
    }

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ASSISTANT, getString(com.amayra.maya.R.string.notif_channel_assistant), NotificationManager.IMPORTANCE_LOW).apply {
                description = getString(com.amayra.maya.R.string.notif_channel_assistant_desc)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SOS, getString(com.amayra.maya.R.string.notif_channel_sos), NotificationManager.IMPORTANCE_HIGH).apply {
                description = getString(com.amayra.maya.R.string.notif_channel_sos_desc)
            }
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CHANNEL_ASSISTANT = "maya_assistant"
        const val CHANNEL_SOS = "maya_sos"
        const val NOTIF_ID = 42
        const val ACTION_SLEEP = "com.amayra.maya.SLEEP"

        fun start(ctx: Context) {
            val i = Intent(ctx, MayaCoreService::class.java)
            ContextCompat_startForegroundService(ctx, i)
        }

        private fun ContextCompat_startForegroundService(ctx: Context, i: Intent) {
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }
    }
}

/** Restarts the assistant service after reboot when standby is enabled. */
class MayaBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val standby = MayaApplication.get(context).settings.prefsCache?.standbyEnabled ?: false
        if (standby) {
            MayaCoreService.start(context)
            MayaLog.i("CORE", "Boot: standby service restarted")
        }
    }
}
