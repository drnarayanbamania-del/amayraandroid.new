package com.amayra.maya.accessibility

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import com.amayra.maya.tools.RiskLevel
import com.amayra.maya.tools.Tool
import com.amayra.maya.tools.ToolContext
import com.amayra.maya.tools.ToolResult
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.resume

// ---------- Macro tools ----------

/** play_macro: run a named builtin macro. */
class PlayMacroTool : Tool {
    override val name = "play_macro"
    override val description =
        "Run a saved macro by name (e.g. 'chatgpt image', 'gemini image'). Macros automate other apps " +
            "through Maya's accessibility service. Params substitute into {param} placeholders."
    override val risk = RiskLevel.CONFIRM
    override fun parameters() = JsonObject(mapOf(
        "type" to JsonPrimitive("object"),
        "properties" to JsonObject(mapOf(
            "name" to JsonObject(mapOf("type" to JsonPrimitive("string"), "description" to JsonPrimitive("Macro name, e.g. 'chatgpt image'"))),
            "params" to JsonObject(mapOf("type" to JsonPrimitive("object"), "description" to JsonPrimitive("Optional parameter map, e.g. {\"image prompt\": \"a cat on a skateboard\"}")))
        )),
        "required" to JsonArray(listOf(JsonPrimitive("name")))
    ))

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val name = (args["name"] as? JsonPrimitive)?.contentOrNull
            ?: return ToolResult.Failure("Missing 'name'.")
        val params = mutableMapOf<String, String>()
        (args["params"] as? JsonObject)?.forEach { (k, v) ->
            params[k] = (v as? JsonPrimitive)?.contentOrNull ?: v.toString()
        }
        val engine = MacroEngine(ctx.appContext)
        return when (val r = engine.runByName(name, params)) {
            is MacroEngine.MacroRunResult.Success -> ToolResult.Success(r.summary)
            is MacroEngine.MacroRunResult.Failure -> ToolResult.Failure(r.error)
        }
    }
}

/** list_macros: introspection so the LLM knows what's available. */
class ListMacrosTool : Tool {
    override val name = "list_macros"
    override val description = "List available macros with their intent descriptions."
    override val risk = RiskLevel.SAFE
    override fun parameters() = JsonObject(emptyMap())
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val macros = MacroEngine(ctx.appContext).loadBuiltinMacros()
        if (macros.isEmpty()) return ToolResult.Success("No macros available.")
        return ToolResult.Success(macros.joinToString("\n") { "• ${it.name}: ${it.intent}" })
    }
}

/** lock_screen via accessibility. */
class LockScreenTool : Tool {
    override val name = "lock_screen"
    override val description =
        "Lock the phone screen (like pressing the power button). Needs the accessibility service. Use for 'phone lock kar do' / 'lock the screen'."
    override val risk = RiskLevel.DEFAULT
    override fun parameters() = JsonObject(emptyMap())
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val svc = MayaAccessibilityService.instance
            ?: return ToolResult.Failure("Accessibility service off — enable it in Settings → Accessibility → Maya.")
        val done = suspendCancellableCoroutine { cont ->
            svc.lockScreen { ok -> if (cont.isActive) cont.resume(ok) {} }
        }
        return if (done) ToolResult.Success("Screen locked.")
        else ToolResult.Failure("Android refused the lock action. Toggle Maya's accessibility service off/on, then retry.")
    }
}

/** take_screenshot via accessibility (API 30+), summarized by the vision model when attached. */
class TakeScreenshotTool : Tool {
    override val name = "take_screenshot"
    override val description =
        "Take a screenshot of the current screen (needs the accessibility service). Returns screen text the assistant can read."
    override val risk = RiskLevel.DEFAULT
    override fun parameters() = JsonObject(emptyMap())
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val svc = MayaAccessibilityService.instance
            ?: return ToolResult.Failure("Screenshot nahi liya ja saka - accessibility service band ho sakti hai (Settings me on karke phir bolo).")
        val bmp: Bitmap? = suspendCancellableCoroutine { cont ->
            svc.takeScreenshot { b -> if (cont.isActive) cont.resume(b) {} }
        }
        if (bmp == null) {
            return ToolResult.Failure("Screenshot failed (needs Android 11+ and the accessibility service).")
        }
        val text = svc.screenTextDump()
        return ToolResult.Success("Screenshot taken (not saved). Screen text: ${text.take(1200)}")
    }
}

/** screen_info: read the current accessibility tree. */
class ScreenInfoTool : Tool {
    override val name = "screen_info"
    override val description =
        "Read the current screen's visible text tree (accessibility). Useful to verify a macro reached the right screen."
    override val risk = RiskLevel.SAFE
    override fun parameters() = JsonObject(emptyMap())
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val svc = MayaAccessibilityService.instance
            ?: return ToolResult.Failure("Page ko phone screen par dekha nahi ja saka - accessibility service band hai.")
        return ToolResult.Success(svc.screenTextDump().ifBlank { "(no readable text on screen)" })
    }
}
