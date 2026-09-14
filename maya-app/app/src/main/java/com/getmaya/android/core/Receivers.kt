package com.getmaya.android.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager

/** Restarts the foreground service after reboot. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            context.startForegroundService(Intent(context, MayaForegroundService::class.java))
        }
    }
}

/** Fires scheduled reminders (used with SCHEDULE_EXACT_ALARM). */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // TODO: show reminder notification with title/text from extras
    }
}

/** Captures mic audio bursts requested by other components. */
class MicCaptureReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // TODO: forward to the active voice session
    }
}

/** Ends "driving mode" automation. */
class DrivingOffReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // TODO: stop driving-mode automation
    }
}

/** Announces incoming calls by name (contact lookup happens on-device). */
class PhoneStateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
        val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
        // TODO: pass to the assistant for on-device announcement
        android.util.Log.d("Maya", "Phone state=$state number=$number")
    }
}
