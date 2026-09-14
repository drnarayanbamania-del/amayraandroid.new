package com.amayra.maya.events

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.amayra.maya.AppGraph
import com.amayra.maya.core.MayaLog

/**
 * Reads incoming notifications (WhatsApp etc.) into the EventBus so the
 * cognitive core can run auto-reply. Requires user opt-in via
 * Settings → Notification access. Maya never reads anything before that.
 */
class MayaNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        MayaLog.i("AUTO", "Notification listener connected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val n = sbn ?: return
        val pkg = n.packageName ?: return
        // Only WhatsApp packages matter today; keep everything else out of the pipeline.
        if (!pkg.startsWith("com.whatsapp")) return
        val extras = n.notification?.extras ?: return
        val title = extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString() ?: return
        val text = extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString() ?: return
        if (title.isBlank() || text.isBlank()) return
        MayaLog.i("AUTO", "WA notification from ${title.take(24)}")
        AppGraph.eventBus.publish(
            MayaaEvent(
                type = EventType.NOTIFICATION,
                source = EventSource.NOTIFICATION_LISTENER,
                packageName = pkg,
                title = title,
                text = text
            )
        )
    }
}
