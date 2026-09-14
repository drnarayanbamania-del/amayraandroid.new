package com.getmaya.android.automation

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** Reads WhatsApp/other notifications so Maya can announce or auto-reply. */
class MayaNotificationListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        // TODO: extract sender/text (on-device only), feed assistant events like [WHATSAPP CHAT]
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {}
}
