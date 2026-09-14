package com.amayra.pc.tools

import com.amayra.pc.core.PcLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Base64
import javax.imageio.ImageIO

/**
 * Amayra's "eyes": screenshot capture returned as base64 JPEG for multimodal
 * understanding, plus wait/verify helpers for the OBSERVE→VERIFY loop.
 *
 * The brain attaches the (downscaled) capture to the next model round as an
 * inline_data part alongside the functionResponse, so Gemini visually verifies
 * the screen. Full-size originals are kept in data/screenshots/ for audit.
 */
class VisionTools {

    fun registerAll(registry: ToolRegistry) {
        registry.register(Screenshot())
        registry.register(Wait())
    }

    private fun str(args: JsonObject, key: String): String? = (args[key] as? JsonPrimitive)?.contentOrNull

    inner class Screenshot : Tool {
        override val name = "read_screen"
        override val description = "Take a screenshot of the current screen. Returns size info and saves to data/screenshots/ for visual inspection. [risk=SAFE]"
        override val risk = RiskLevel.SAFE
        override fun parameters() = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("region") {
                    put("type", "string")
                    put("description", "Optional: 'full' (default). Coordinates cropping coming later.")
                }
            }
        }
        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
            try {
                val shot = Hands.robot.createScreenCapture(java.awt.Rectangle(Hands.screenSize))
                val dir = Path.of("data", "screenshots")
                Files.createDirectories(dir)
                val file = dir.resolve("screen-${System.currentTimeMillis()}.jpg")
                val baos = ByteArrayOutputStream()
                ImageIO.write(shot, "jpg", baos)
                Files.write(file, baos.toByteArray(), StandardOpenOption.CREATE)
                val b64 = Base64.getEncoder().encodeToString(baos.toByteArray())
                PcLog.d("VISION", "Screenshot ${file.fileName} (${b64.length / 1024} KB base64)")
                ToolResult.Success(
                    summary = "Screenshot captured (${shot.width}x${shot.height}) saved to ${file}.",
                    dataJson = buildJsonObject {
                        put("path", file.toString())
                        put("width", shot.width)
                        put("height", shot.height)
                        put("base64", b64)
                    }.toString()
                )
            } catch (t: Throwable) {
                ToolResult.Failure("Screenshot failed: ${t.message}", retryable = true)
            }
        }
    }

    inner class Wait : Tool {
        override val name = "wait"
        override val description = "Wait for a moment (milliseconds, max 10000) — e.g. while an app loads — then continue. [risk=SAFE]"
        override val risk = RiskLevel.SAFE
        override fun parameters() = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("ms") { put("type", "integer") }
            }
            putJsonArray("required") { add(JsonPrimitive("ms")) }
        }
        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
            val ms = ((args["ms"] as? JsonPrimitive)?.intOrNull ?: 1000).coerceIn(100, 10_000)
            delay(ms.toLong())
            return ToolResult.Success("Waited ${ms}ms.")
        }
    }
}
