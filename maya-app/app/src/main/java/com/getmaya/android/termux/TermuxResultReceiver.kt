package com.getmaya.android.termux

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Receives results from Termux:RUN_COMMAND (com.getmaya.android.TERMUX_RESULT).
 * Requires Termux's allow-external-apps = true, as documented in the original.
 */
class TermuxResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // TODO: read stdout/exit code extras, return to the assistant
    }
}
