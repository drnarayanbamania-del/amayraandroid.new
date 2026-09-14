package com.amayra.maya.accessibility

import com.amayra.maya.core.MayaLog
import com.amayra.maya.tools.RiskLevel
import com.amayra.maya.tools.Tool
import com.amayra.maya.tools.ToolContext
import com.amayra.maya.tools.ToolResult
import com.amayra.maya.tools.argSchema
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.resume

/**
 * Agent screen-control tools (the "Android computer-use" layer).
 *
 * These give the agent a closed action loop over the real UI:
 *   inspect_screen → click_element / type_text / scroll / press_back →
 *   wait for UI settle → verify via inspect_screen.
 *
 * Every action returns a structured, honest result; after each mutation we
 * re-dump the screen so the MODEL can verify the expected state instead of
 * the tool blindly claiming success. Element targeting tries, in order:
 * visible text → content-description → resource-id → screen-quarter
 * coordinates (bounds) as a last resort.
 */

/** Structured, compact description of one interactive node. */
private data class UiElement(
    val text: String,
    val description: String,
    val viewId: String,
    val cls: String,
    val clickable: Boolean,
    val editable: Boolean,
    val checked: Boolean?,
    val scrollable: Boolean,
    val cx: Int,
    val cy: Int
) {
    fun line(index: Int): String = buildString {
        append("#$index ")
        if (text.isNotBlank()) append("\"$text\" ")
        if (description.isNotBlank()) append("(desc=\"$description\") ")
        if (viewId.isNotBlank()) append("(id=$viewId) ")
        append("<${cls.substringAfterLast('.').removeSuffix("Impl")}>")
        if (clickable) append(" [clickable]")
        if (editable) append(" [editable]")
        if (checked != null) append(" [${if (checked) "ON" else "OFF"}]")
        if (scrollable) append(" [scrollable]")
        append(" @($cx,$cy)")
    }
}

/** Collect interactive elements from all window roots. */
private fun collectElements(svc: MayaAccessibilityService): List<UiElement> {
    val out = mutableListOf<UiElement>()
    fun walk(node: android.view.accessibility.AccessibilityNodeInfo?) {
        if (node == null) return
        try {
            if (node.isClickable || node.isEditable || node.isScrollable || node.isCheckable) {
                val b = android.graphics.Rect()
                node.getBoundsInScreen(b)
                out += UiElement(
                    text = node.text?.toString()?.trim().orEmpty(),
                    description = node.contentDescription?.toString()?.trim().orEmpty(),
                    viewId = node.viewIdResourceName?.substringAfterLast('/') ?: "",
                    cls = node.className?.toString().orEmpty(),
                    clickable = node.isClickable,
                    editable = node.isEditable,
                    checked = if (node.isCheckable) node.isChecked else null,
                    scrollable = node.isScrollable,
                    cx = b.centerX(),
                    cy = b.centerY()
                )
            }
            for (i in 0 until node.childCount) walk(node.getChild(i))
        } catch (_: Throwable) {}
    }
    svc.roots().forEach { walk(it) }
    return out
}

private fun findTarget(
    svc: MayaAccessibilityService,
    text: String?,
    description: String?,
    resourceId: String?
): android.view.accessibility.AccessibilityNodeInfo? {
    if (!resourceId.isNullOrBlank()) {
        svc.findByViewId(resourceId)?.let { return it }
        // also try with package prefix stripped both ways
        for (root in svc.roots()) {
            val pkg = root.packageName?.toString().orEmpty()
            if (pkg.isNotBlank()) {
                svc.findByViewId("$pkg:id/$resourceId")?.let { return it }
            }
        }
    }
    if (!description.isNullOrBlank()) {
        for (root in svc.roots()) {
            root.findAccessibilityNodeInfosByText(description).firstOrNull {
                it.contentDescription?.toString()?.contains(description, ignoreCase = true) == true
            }?.let { return it }
        }
    }
    if (!text.isNullOrBlank()) {
        for (root in svc.roots()) {
            root.findAccessibilityNodeInfosByText(text).firstOrNull {
                it.text?.toString()?.contains(text, ignoreCase = true) == true ||
                    it.contentDescription?.toString()?.contains(text, ignoreCase = true) == true
            }?.let { return it }
        }
    }
    return null
}

/** Best clickable/scrollable ancestor-or-self for performing an action. */
private fun actionable(node: android.view.accessibility.AccessibilityNodeInfo): android.view.accessibility.AccessibilityNodeInfo {
    var cur: android.view.accessibility.AccessibilityNodeInfo? = node
    var last = node
    while (cur != null) {
        if (cur.isClickable || cur.isScrollable) return cur
        last = cur
        cur = try { cur.parent } catch (_: Throwable) { null }
    }
    return last
}

/** Gesture fallback coordinates for the node's center. */
private suspend fun gestureTap(svc: MayaAccessibilityService, x: Float, y: Float): Boolean =
    suspendCancellableCoroutine { cont ->
        svc.tap(x, y) { ok -> if (cont.isActive) cont.resume(ok) }
    }

/** Post-action settle: let the UI transition finish before verification. */
private suspend fun settle(ms: Long = 900L) = withContext(Dispatchers.IO) {
    try { Thread.sleep(ms) } catch (_: InterruptedException) {}
}

// ---------------------------------------------------------------------------
// Tools
// ---------------------------------------------------------------------------

/** Structured, compact screen inventory — the agent's "eyes". */
class InspectScreenTool : Tool {
    override val name = "inspect_screen"
    override val description = "Inspect the current screen: list interactive elements (text, id, type, state, position) so you can decide the next UI action."
    override val risk = RiskLevel.SAFE
    override fun parameters() = JsonObject(emptyMap())
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val svc = MayaAccessibilityService.instance
            ?: return ToolResult.Failure("Screen nahi padh sakta — Maya accessibility service off hai (Settings → Accessibility → Maya).")
        val els = collectElements(svc)
        if (els.isEmpty()) return ToolResult.Failure("Koi interactive element nahi mila (screen shayad locked hai ya service ka root nahi mila).")
        val pkg = svc.roots().firstOrNull()?.packageName?.toString().orEmpty()
        val body = buildString {
            append("package=$pkg elements=${els.size}\n")
            els.take(30).forEachIndexed { i, e -> appendLine(e.line(i)) }
            if (els.size > 30) appendLine("… (+${els.size - 30} more)")
        }
        return ToolResult.Success(body)
    }
}

/** Click the element matching text/description/id; falls back to a gesture tap at its bounds. */
class ClickElementTool : Tool {
    override val name = "click_element"
    override val description = "Tap a UI element found on the current screen. Verify the result afterwards with inspect_screen."
    override val risk = RiskLevel.SAFE
    override fun parameters() = argSchema(
        mapOf(
            "text" to "Visible text of the element (first choice)",
            "description" to "content-description of the element",
            "resource_id" to "Resource id of the element (last structured choice)"
        ),
        emptyList()
    )
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val svc = MayaAccessibilityService.instance
            ?: return ToolResult.Failure("Click nahi ho sakta — accessibility service off hai.")
        val text = args["text"]?.jsonPrimitive?.contentOrNull
        val desc = args["description"]?.jsonPrimitive?.contentOrNull
        val resId = args["resource_id"]?.jsonPrimitive?.contentOrNull
        if (text.isNullOrBlank() && desc.isNullOrBlank() && resId.isNullOrBlank()) {
            return ToolResult.Failure("Ek identifier do: text, description, ya resource_id.")
        }
        val node = findTarget(svc, text, desc, resId)
            ?: return ToolResult.Failure("Element nahi mila (text=$text desc=$desc id=$resId). inspect_screen se dobara dekho — shayad scroll karna padega.")
        val target = actionable(node)
        val performed = try { target.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) } catch (t: Throwable) { false }
        if (!performed) {
            // Gesture fallback at the node's screen center.
            val b = android.graphics.Rect()
            target.getBoundsInScreen(b)
            val ok = gestureTap(svc, b.exactCenterX(), b.exactCenterY())
            if (!ok) return ToolResult.Failure("Click perform nahi hua (node action + gesture dono fail).")
        }
        settle()
        val after = collectElements(svc)
        return ToolResult.Success(
            "Tapped ${text ?: desc ?: resId}. Post-action screen has ${after.size} interactive elements — verify with inspect_screen."
        )
    }
}

/** Type text into the focused (or located) editable node. */
class TypeTextTool : Tool {
    override val name = "type_text"
    override val description = "Type text into an editable field on the current screen (uses the focused field unless 'text_target' names one)."
    override val risk = RiskLevel.SAFE
    override fun parameters() = argSchema(
        mapOf(
            "value" to "Text to type",
            "text_target" to "(optional) visible text of the field to focus first",
            "submit" to "(optional) true = press Enter/IME action after typing"
        ),
        listOf("value")
    )
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val svc = MayaAccessibilityService.instance
            ?: return ToolResult.Failure("Type nahi kar sakta — accessibility service off hai.")
        val value = args["value"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (value.isBlank()) return ToolResult.Failure("Missing 'value'.")
        val targetText = args["text_target"]?.jsonPrimitive?.contentOrNull
        val submit = args["submit"]?.jsonPrimitive?.booleanOrNull ?: false

        val bundle = android.os.Bundle()
        var typed = false
        if (targetText != null) {
            findTarget(svc, targetText, null, null)?.let { n ->
                if (n.isFocusable) n.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_FOCUS)
                typed = n.performAction(
                    android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT,
                    bundle.apply { putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }
                )
            }
        }
        if (!typed) {
            // TYPE into the currently focused editable node.
            val focused = svc.roots().firstNotNullOfOrNull { root ->
                root.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT)
            }
            if (focused != null && focused.isEditable) {
                typed = focused.performAction(
                    android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT,
                    bundle.apply { putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }
                )
        if (!typed) {
            // SET_TEXT unsupported on this node → honest failure; the agent
            // should click_element the field first, then retry.
            return ToolResult.Failure("Field mila par type nahi kar paya (SET_TEXT rejected). Pehle click_element se field focus karo, phir retry.")
        }
            }
        }
        if (!typed) return ToolResult.Failure("Koi editable field nahi mila/mujhe type nahi karne diya. Field pehle tap karo (click_element).")
        var submitNote = ""
        if (submit) {
            // IME enter isn't reliably reachable from accessibility — click the
            // send/search/go button instead (more robust across apps).
            val sendBtn = collectElements(svc).firstOrNull { e ->
                e.clickable && (e.text + e.description).contains(Regex("send|search|go|post|arrow", RegexOption.IGNORE_CASE))
            }
            if (sendBtn != null) {
                findTarget(svc, sendBtn.text.ifBlank { null }, sendBtn.description.ifBlank { null }, sendBtn.viewId.ifBlank { null })
                    ?.let { actionable(it).performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) }
                submitNote = " + send-button tapped"
            } else submitNote = " (Enter nahi mila — send button khud click karna padega)"
        }
        settle(400)
        return ToolResult.Success("Typed \"${value.take(40)}\"$submitNote. Verify with inspect_screen.")
    }
}

/** Scroll the screen (gesture swipe) in a direction. */
class ScrollScreenTool : Tool {
    override val name = "scroll_screen"
    override val description = "Scroll the current screen up/down (or a scrollable element containing the named text)."
    override val risk = RiskLevel.SAFE
    override fun parameters() = argSchema(
        mapOf(
            "direction" to "up | down (default down)",
            "on_text" to "(optional) scroll within the scrollable list containing this text"
        ),
        emptyList()
    )
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val svc = MayaAccessibilityService.instance
            ?: return ToolResult.Failure("Scroll nahi kar sakta — accessibility service off hai.")
        val dir = (args["direction"]?.jsonPrimitive?.contentOrNull ?: "down").lowercase()
        val onText = args["on_text"]?.jsonPrimitive?.contentOrNull
        val w = ctx.appContext.resources.displayMetrics.widthPixels
        val h = ctx.appContext.resources.displayMetrics.heightPixels
        // A scrollable node targeted? Use ACTION_SCROLL_FORWARD/BACKWARD on it.
        if (onText != null) {
            findTarget(svc, onText, null, null)?.let { n ->
                var cur: android.view.accessibility.AccessibilityNodeInfo? = n
                while (cur != null && !cur.isScrollable) cur = try { cur.parent } catch (_: Throwable) { null }
                if (cur != null) {
                    val fwd = dir != "up"
                    val ok = cur.performAction(
                        if (fwd) android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                        else android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                    )
                    if (ok) { settle(); return ToolResult.Success("Scrolled $dir inside \"$onText\". Verify with inspect_screen.") }
                }
            }
        }
        // Gesture swipe across the middle of the screen.
        val down = dir != "up"
        val y1 = if (down) h * 0.72f else h * 0.32f
        val y2 = if (down) h * 0.32f else h * 0.72f
        val ok = suspendCancellableCoroutine { cont ->
            svc.swipe(w * 0.5f, y1, w * 0.5f, y2, 260) { done -> if (cont.isActive) cont.resume(done) }
        }
        if (!ok) return ToolResult.Failure("Scroll gesture fail hua.")
        settle()
        return ToolResult.Success("Scrolled $dir. Verify with inspect_screen.")
    }
}

/** System back (accessibility GLOBAL_ACTION_BACK). */
class PressBackTool : Tool {
    override val name = "press_back"
    override val description = "Press the Android back button (global action)."
    override val risk = RiskLevel.SAFE
    override fun parameters() = JsonObject(emptyMap())
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val svc = MayaAccessibilityService.instance
            ?: return ToolResult.Failure("Back press nahi ho sakta — accessibility service off hai.")
        val done = suspendCancellableCoroutine { cont ->
            svc.goBack { ok -> if (cont.isActive) cont.resume(ok) }
        }
        if (!done) return ToolResult.Failure("Back press fail hua.")
        settle()
        return ToolResult.Success("Went back. Verify with inspect_screen.")
    }
}

/** Element locator helper the agent can use before clicking (semantic search). */
class FindElementTool : Tool {
    override val name = "find_element"
    override val description = "Search the current screen for an element by text/description/id and return its index line (for click_element)."
    override val risk = RiskLevel.SAFE
    override fun parameters() = argSchema(
        mapOf("text" to "Text to search for (case-insensitive substring)"),
        listOf("text")
    )
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val svc = MayaAccessibilityService.instance
            ?: return ToolResult.Failure("Screen search nahi kar sakta — accessibility service off hai.")
        val q = args["text"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (q.isBlank()) return ToolResult.Failure("Missing 'text'.")
        val hits = collectElements(svc).withIndex().filter { (_, e) ->
            e.text.contains(q, true) || e.description.contains(q, true) || e.viewId.contains(q, true)
        }
        if (hits.isEmpty()) return ToolResult.Failure("\"$q\" ke liye koi element nahi mila — scroll_screen karke dobara try karo.")
        val body = hits.joinToString("\n") { (i, e) -> e.line(i) }
        return ToolResult.Success("Matches for \"$q\":\n$body")
    }
}
