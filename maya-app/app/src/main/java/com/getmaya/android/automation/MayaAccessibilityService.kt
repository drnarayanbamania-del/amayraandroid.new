package com.getmaya.android.automation

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/** Automation service for WhatsApp UI control, screen reading, taps, gestures. */
class MayaAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // TODO: route WhatsApp/chat window events to the assistant
    }

    override fun onInterrupt() {}
}
