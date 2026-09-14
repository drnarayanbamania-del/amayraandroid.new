package com.amayra.maya.integration

import com.amayra.maya.settings.SettingsRepository
import com.amayra.maya.tools.RiskLevel
import com.amayra.maya.tools.Tool
import com.amayra.maya.tools.ToolContext
import com.amayra.maya.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull

/**
 * Tools that drive the PC Amayra assistant over the :18789 JSON-lines bridge.
 * The PC side runs its own agent brain with its own tools (apps, mouse,
 * keyboard, windows, files, screenshots), so commands are plain natural
 * language ("open Chrome and search for GPUs") — the phone sends the intent,
 * the PC plans and executes, and the structured reply flows back here.
 */
private suspend fun pcEndpoint(settings: SettingsRepository): Pair<String, Int>? {
    val prefs = settings.prefsCache ?: return null
    val host = prefs.pcHost.trim()
    if (host.isBlank()) return null
    return host to (prefs.pcRelayPort)
}

/** Natural-language command executed on the PC by the PC assistant's brain. */
class PcCommandTool(private val settings: SettingsRepository) : Tool {
    override val name = "pc_command"
    override val description =
        "Run a task on the user's Windows PC via the Amayra PC assistant. The PC agent " +
            "plans and executes with its own tools (open apps, type, click, windows, files, " +
            "screenshots, shell). Give a clear natural-language instruction, e.g. " +
            "\"open Notepad and type hello\" or \"what windows are open\". Returns the PC's report."
    override val risk = RiskLevel.CONFIRM  // remote execution — always confirm
    override fun parameters() = JsonObject(mapOf(
        "type" to JsonPrimitive("object"),
        "properties" to JsonObject(mapOf(
            "command" to JsonObject(mapOf(
                "type" to JsonPrimitive("string"),
                "description" to JsonPrimitive("Natural-language task for the PC assistant")
            ))
        )),
        "required" to JsonArray(listOf(JsonPrimitive("command")))
    ))

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val cmd = args["command"]?.let { (it as? JsonPrimitive)?.contentOrNull }
            ?: return ToolResult.Failure("Missing command.")
        val endpoint = pcEndpoint(settings)
            ?: return ToolResult.Failure("PC address not set. Add the PC's IP in Settings → PC bridge first.")
        val reply = PcCommandClient.command(endpoint.first, endpoint.second, cmd)
        return if (reply.ok) {
            ToolResult.Success("PC: ${reply.summary.ifBlank { "done." }}".take(2000))
        } else {
            ToolResult.Failure("PC command failed: ${reply.error ?: reply.summary.take(300)}")
        }
    }
}

/**
 * pc_screenshot: capture the PC's screen and show it in the phone chat.
 * The JPEG is saved to app files; its path rides on the tool result as
 * "PC_SCREENSHOT:<path>" so the chat can render the image inline.
 */
class PcScreenshotTool(private val settings: SettingsRepository) : Tool {
    override val name = "pc_screenshot"
    override val description =
        "Take a screenshot of the user's PC screen and show it in the chat. Use when the user " +
            "asks what's on the PC screen, or to verify the result of a pc_command task."
    override val risk = RiskLevel.DEFAULT
    override fun parameters() = JsonObject(mapOf(
        "type" to JsonPrimitive("object"),
        "properties" to JsonObject(mapOf(
            "detail" to JsonObject(mapOf(
                "type" to JsonPrimitive("string"),
                "description" to JsonPrimitive("'low' (default, fast) or 'high' (larger image) — resolution of the capture")
            ))
        ))
    ))

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val endpoint = pcEndpoint(settings)
            ?: return ToolResult.Failure("PC address not set. Add the PC's IP in Settings → PC bridge first.")
        val maxEdge = if (args["detail"]?.toString()?.contains("high") == true) 1920 else 1280
        val reply = PcCommandClient.screenshot(endpoint.first, endpoint.second, maxEdge)
        val b64 = reply.imageBase64
        if (!reply.ok || b64.isNullOrBlank()) {
            return ToolResult.Failure("PC screenshot failed: ${reply.error ?: reply.summary.take(200)}")
        }
        return try {
            val bytes = java.util.Base64.getDecoder().decode(b64)
            val dir = java.io.File(ctx.appContext.filesDir, "pc_screenshots").apply { mkdirs() }
            val file = java.io.File(dir, "pc-${System.currentTimeMillis()}.jpg")
            java.io.FileOutputStream(file).use { it.write(bytes) }
            ToolResult.Success(
                summary = "PC screenshot captured (${bytes.size / 1024} KB). ${reply.summary}",
                dataJson = "PC_SCREENSHOT:${file.absolutePath}"
            )
        } catch (t: Throwable) {
            ToolResult.Failure("Could not save PC screenshot: ${t.message}")
        }
    }
}

/** Health probe — SAFE, lets Maya check the PC before promising anything. */
class PcPingTool(private val settings: SettingsRepository) : Tool {
    override val name = "pc_ping"
    override val description = "Check whether the user's PC assistant is reachable and its relay is listening."
    override val risk = RiskLevel.SAFE
    override fun parameters() = JsonObject(emptyMap())

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val endpoint = pcEndpoint(settings)
            ?: return ToolResult.Failure("PC address not set. Add the PC's IP in Settings → PC bridge first.")
        val reply = PcCommandClient.ping(endpoint.first, endpoint.second)
        return if (reply.ok) {
            ToolResult.Success("PC is online (${endpoint.first}:${endpoint.second}). ${reply.summary}")
        } else {
            ToolResult.Failure(reply.error ?: "PC not reachable.")
        }
    }
}
