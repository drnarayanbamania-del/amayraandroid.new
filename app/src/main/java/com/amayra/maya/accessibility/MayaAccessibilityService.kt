package com.amayra.maya.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.amayra.maya.core.MayaLog
import com.amayra.maya.core.StateBus
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference

/**
 * Maya's accessibility service: the hands of the macro engine.
 *
 * Capabilities (all require this service to be enabled by the user):
 *  - tap by text / coordinates / id, type text, go back, lock screen
 *  - screenshot (API 30+) via takeScreenshot
 *  - read the screen tree (findFocus / rootInActiveWindow)
 */
class MayaAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        StateBus.setAccessibilityLive(true)
        MayaLog.i("A11Y", "Maya accessibility service connected")
    }

    override fun onDestroy() {
        instance = null
        StateBus.setAccessibilityLive(false)
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    // ---- screen tree -------------------------------------------------------

    fun roots(): List<AccessibilityNodeInfo> {
        val list = mutableListOf<AccessibilityNodeInfo>()
        rootInActiveWindow?.let { list.add(it) }
        try {
            // windows API (API 21+): iterate all windows for better coverage
            val wm = windows
            wm?.forEach { w -> w.root?.let { if (list.none { e -> e == it }) list.add(it) } }
        } catch (_: Throwable) {}
        return list
    }

    fun findByText(text: String): List<AccessibilityNodeInfo> {
        val out = mutableListOf<AccessibilityNodeInfo>()
        for (root in roots()) {
            val nodes = root.findAccessibilityNodeInfosByText(text)
            out.addAll(nodes)
        }
        return out
    }

    fun findByViewId(id: String): AccessibilityNodeInfo? {
        for (root in roots()) {
            val found = root.findAccessibilityNodeInfosByViewId(id)
            if (found.isNotEmpty()) return found[0]
        }
        return null
    }

    fun screenTextDump(maxChars: Int = 4000): String {
        val sb = StringBuilder()
        for (root in roots()) {
            dump(root, sb, 0)
            if (sb.length > maxChars) break
        }
        return sb.take(maxChars).toString()
    }

    private fun dump(node: AccessibilityNodeInfo, sb: StringBuilder, depth: Int) {
        if (sb.length > 3500 || depth > 25) return
        val txt = node.text?.toString()?.trim().orEmpty()
        val cd = node.contentDescription?.toString()?.trim().orEmpty()
        val cls = node.className?.toString()?.substringAfterLast('.') ?: ""
        if (txt.isNotEmpty() || cd.isNotEmpty()) {
            sb.append("  ".repeat(depth)).append("[$cls] ")
            if (txt.isNotEmpty()) sb.append(txt)
            if (cd.isNotEmpty()) sb.append(" (cd: $cd)")
            sb.append('\n')
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { dump(it, sb, depth + 1) }
        }
    }

    // ---- gestures ----------------------------------------------------------

    fun tap(x: Float, y: Float, callback: (Boolean) -> Unit) {
        if (Build.VERSION.SDK_INT < 24) { callback(false); return }
        val path = android.graphics.Path().apply { moveTo(x, y); lineTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 60)
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) { callback(true) }
            override fun onCancelled(g: GestureDescription?) { callback(false) }
        }, null)
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long, callback: (Boolean) -> Unit) {
        if (Build.VERSION.SDK_INT < 24) { callback(false); return }
        val path = android.graphics.Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        val stroke = GestureDescription.StrokeDescription(path, 0, ms)
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) { callback(true) }
            override fun onCancelled(g: GestureDescription?) { callback(false) }
        }, null)
    }

    fun lockScreen(callback: (Boolean) -> Unit) {
        // Accessibility GLOBAL_ACTION_LOCK_SCREEN (API 28+)
        if (Build.VERSION.SDK_INT >= 28) {
            val ok = performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
            callback(ok)
        } else {
            callback(false)
        }
    }

    fun goBack(callback: (Boolean) -> Unit) {
        callback(performGlobalAction(GLOBAL_ACTION_BACK))
    }

    // ---- screenshot --------------------------------------------------------

    fun takeScreenshot(onResult: (Bitmap?) -> Unit) {
        if (Build.VERSION.SDK_INT < 30) { onResult(null); return }
        try {
            takeScreenshot(
                display.displayId,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        val bmp = Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                        screenshot.hardwareBuffer.close()
                        onResult(bmp)
                    }
                    override fun onFailure(errorCode: Int) { onResult(null) }
                }
            )
        } catch (t: Throwable) {
            MayaLog.w("A11Y", "screenshot failed: ${t.message}")
            onResult(null)
        }
    }

    companion object {
        @Volatile var instance: MayaAccessibilityService? = null
            private set

        val isLive: Boolean get() = instance != null

        /** Convenience for tools: run [block] with the service or fail. */
        inline fun <T> withService(onUnavailable: () -> T, block: (MayaAccessibilityService) -> T): T {
            val svc = instance ?: return onUnavailable()
            return block(svc)
        }
    }
}
