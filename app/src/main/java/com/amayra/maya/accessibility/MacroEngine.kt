package com.amayra.maya.accessibility

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.amayra.maya.core.MayaLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.util.concurrent.atomic.AtomicBoolean

// ---------- Macro data model (mirrors assets/macros/builtin_macros.json) ----------

@Serializable
data class MacroStep(
    val tool: String,
    val args: Map<String, JsonElement> = emptyMap(),
    // step-level metadata used by the reference format
    @SerialName("_why") val why: String? = null,
    val mode: String? = null,
    val timeout_seconds: Int? = null
)

@Serializable
data class Macro(
    val name: String,
    val intent: String = "",
    val params: List<String> = emptyList(),
    val steps: List<MacroStep> = emptyList()
)

@Serializable
data class MacroFile(
    val version: Int = 1,
    val macros: List<Macro> = emptyList()
)

/**
 * Runs macros: sequences of screen-automation steps driven by the accessibility
 * service. Builtin macros ship in assets/macros/builtin_macros.json (seeded
 * version bumps re-seed edits onto installs).
 */
class MacroEngine(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val running = AtomicBoolean(false)
    @Volatile var progressText: String = ""
        private set
    val isRunning: Boolean get() = running.get()

    fun loadBuiltinMacros(): List<Macro> = try {
        val raw = context.assets.open("macros/builtin_macros.json").use { it.readBytes().decodeToString() }
        json.decodeFromString<MacroFile>(raw).macros
    } catch (t: Throwable) {
        MayaLog.w("MACRO", "No builtin macros: ${t.message}")
        emptyList()
    }

    suspend fun runByName(name: String, params: Map<String, String> = emptyMap()): MacroRunResult {
        val all = loadBuiltinMacros()
        val macro = all.firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?: return MacroRunResult.Failure(
                "No macro named \"$name\". Available: ${all.joinToString(", ") { it.name }}"
            )
        return runMacro(macro, params)
    }

    suspend fun runMacro(macro: Macro, params: Map<String, String> = emptyMap()): MacroRunResult {
        if (!MayaAccessibilityService.isLive) {
            return MacroRunResult.Failure(
                "Maya's accessibility service is off. Enable it in Settings → Accessibility → Maya, then ask again."
            )
        }
        if (!running.compareAndSet(false, true)) {
            return MacroRunResult.Failure("Another macro is already running.")
        }
        try {
            MayaLog.i("MACRO", "Run '${macro.name}' params=$params")
            for ((i, step) in macro.steps.withIndex()) {
                if (com.amayra.maya.accessibility.MacroEngine.cancelFlag.get()) {
                    return MacroRunResult.Failure("Cancelled.")
                }
                // "_optional": true inside args (reference semantics): a step that is
                // only sometimes applicable is skipped, not failed.
                val optional = (step.args["_optional"])?.let { el ->
                    (el as? kotlinx.serialization.json.JsonPrimitive)?.content == "true"
                } == true
                progressText = "Step ${i + 1}/${macro.steps.size}: ${step.tool}"
                val ok = executeStep(step, params, optional)
                if (!ok && !optional) {
                    return MacroRunResult.Failure(
                        "Step ${i + 1} (${step.tool}) failed. ${step.why.orEmpty()}".trim()
                    )
                }
            }
            progressText = "Done: ${macro.name}"
            return MacroRunResult.Success("Macro \"${macro.name}\" completed all ${macro.steps.size} steps.")
        } finally {
            running.set(false)
        }
    }

    private suspend fun executeStep(step: MacroStep, params: Map<String, String>, optional: Boolean): Boolean =
        withContext(Dispatchers.Default) {
            fun raw(k: String): String =
                (step.args[k] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()

            fun substituted(v: String): String {
                var out = v
                for ((k, value) in params) out = out.replace("{$k}", value)
                return out
            }

            when (step.tool) {
                "open_app_by_name" -> {
                    val name = substituted(raw("name"))
                    val pm = context.packageManager
                    val pkg = pm.queryIntentActivities(
                        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
                    )
                        .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
                        .firstOrNull { it.first.equals(name, ignoreCase = true) || it.first.contains(name, true) }
                        ?.second
                    if (pkg == null) {
                        MayaLog.w("MACRO", "open_app_by_name miss: $name")
                        false
                    } else {
                        try {
                            context.startActivity(
                                pm.getLaunchIntentForPackage(pkg)!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                            true
                        } catch (t: Throwable) {
                            MayaLog.w("MACRO", "launch failed: ${t.message}")
                            false
                        }
                    }
                }

                "wait" -> {
                    delay(raw("seconds").toFloatOrNull()?.times(1000)?.toLong() ?: 1000L)
                    true
                }

                "wait_for_screen_text" -> {
                    val text = substituted(raw("text"))
                    val timeoutMs = (step.timeout_seconds ?: 60) * 1000L
                    val wantDisappear = step.mode.equals("disappear", ignoreCase = true)
                    val start = System.currentTimeMillis()
                    while (System.currentTimeMillis() - start < timeoutMs) {
                        val present = screenContains(text)
                        if (wantDisappear && !present) return@withContext true
                        if (!wantDisappear && present) return@withContext true
                        delay(700)
                    }
                    false
                }

                "tap_text" -> {
                    val text = substituted(raw("text"))
                    val bounds = RectArrayHolder.rect
                    val found = tapCenterOfText(text)
                    if (!found) MayaLog.w("MACRO", "tap_text miss: $text")
                    found
                }

                "tap_coords" -> {
                    val x = raw("x").toFloatOrNull()
                    val y = raw("y").toFloatOrNull()
                    if (x == null || y == null) false
                    else {
                        val ok = tapAt(x, y)
                        delay(300)
                        ok
                    }
                }

                "type_text" -> {
                    val text = substituted(raw("text"))
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("maya", text))
                    val ok = pasteIntoFocusedEditable()
                    if (!ok) MayaLog.w("MACRO", "type_text: no focused editable found")
                    ok
                }

                "go_back" -> {
                    MayaAccessibilityService.withService({ false }) { svc -> svc.goBack { }; true }
                }

                "lock_screen" -> {
                    val done = java.util.concurrent.atomic.AtomicBoolean(false)
                    MayaAccessibilityService.withService({ false }) { svc ->
                        svc.lockScreen { ok -> done.set(ok) }
                        true
                    }
                    var waited = 0
                    while (!done.get() && waited < 1500) { delay(100); waited += 100 }
                    done.get()
                }

                "screenshot" -> {
                    val holder = ScreenshotHolder()
                    MayaAccessibilityService.withService({ false }) { svc ->
                        svc.takeScreenshot { bmp -> holder.bitmap = bmp }
                        true
                    }
                    var waited = 0
                    while (holder.bitmap == null && waited < 2000) { delay(100); waited += 100 }
                    holder.bitmap != null
                }

                else -> {
                    MayaLog.w("MACRO", "Unknown macro step tool: ${step.tool}")
                    optional
                }
            }
        }

    // ---- accessibility plumbing (kept off the hot path of optional steps) ----

    private class RectArrayHolder { companion object { val rect = Rect() } }
    private class ScreenshotHolder { @Volatile var bitmap: android.graphics.Bitmap? = null }

    private fun screenContains(text: String): Boolean =
        MayaAccessibilityService.withService({ false }) { svc -> svc.findByText(text).isNotEmpty() }

    private suspend fun tapCenterOfText(text: String): Boolean {
        val svc = MayaAccessibilityService.instance ?: return false
        val node = svc.findByText(text).firstOrNull { it.isClickable || it.isEnabled }
            ?: svc.findByText(text).firstOrNull()
            ?: return false
        val r = Rect()
        node.getBoundsInScreen(r)
        if (r.isEmpty) return false
        val ok = tapAt(r.exactCenterX(), r.exactCenterY())
        delay(350)
        return ok
    }

    private suspend fun tapAt(x: Float, y: Float): Boolean {
        val svc = MayaAccessibilityService.instance ?: return false
        val latch = kotlinx.coroutines.CompletableDeferred<Boolean>()
        svc.tap(x, y) { ok -> latch.complete(ok) }
        return try { latch.await() } catch (_: Throwable) { false }
    }

    private fun pasteIntoFocusedEditable(): Boolean {
        val svc = MayaAccessibilityService.instance ?: return false
        val target = svc.roots().asSequence()
            .mapNotNull { root ->
                root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }
            }
            .firstOrNull()
            ?: svc.roots().asSequence()
                .mapNotNull { root -> findEditable(root, 0) }
                .firstOrNull()
            ?: return false
        return target.performAction(AccessibilityNodeInfo.ACTION_PASTE)
    }

    private fun findEditable(node: AccessibilityNodeInfo, depth: Int): AccessibilityNodeInfo? {
        if (depth > 30) return null
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                findEditable(child, depth + 1)?.let { return it }
            }
        }
        return null
    }

    sealed class MacroRunResult {
        data class Success(val summary: String) : MacroRunResult()
        data class Failure(val error: String) : MacroRunResult()
    }

    companion object {
        val cancelFlag = AtomicBoolean(false)
    }
}
