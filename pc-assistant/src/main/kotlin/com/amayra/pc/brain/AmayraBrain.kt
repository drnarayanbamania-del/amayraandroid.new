package com.amayra.pc.brain

import com.amayra.pc.ai.*
import com.amayra.pc.core.*
import com.amayra.pc.tools.*
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.ImageIO

/**
 * Amayra's brain: the OBSERVE → THINK → ACT → VERIFY agent loop.
 *
 * The model plans, calls structured tools, receives structured results
 * (success/error/retryable) and continues until it can answer. It is never
 * told a task succeeded unless the tool actually reported success.
 */
class AmayraBrain(
    private val client: GeminiClient,
    private val registry: ToolRegistry,
    private val config: PcConfig,
    private val confirmation: suspend (title: String, details: String) -> Boolean
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val history = mutableListOf<ChatMessage>()

    fun toolContext() = ToolContext(confirmation)

    /** System prompt: JARVIS-style personality, never fake success, memory-aware. */
    private fun systemPrompt(): String {
        val memories = MemoryStore.recall(limit = 15)
        val memoryBlock = if (memories.isEmpty()) "(none yet — learn as we go)"
        else memories.joinToString("\n") { "- [${it.category}] ${it.content}" }
        return """
You are Amayra — a voice-first personal computer assistant running on the user's Windows PC.
Personality: mature, intelligent, confident, quietly witty. You sound like a competent
partner, not a customer-support bot. Occasional dry humor is welcome when it fits;
never let a joke delay or distort a task. Never childish, never sycophantic.

Operating rules:
1. ACT, don't pretend. If you say you opened something, you must have called the tool
   and received success:true. Never claim success on a failed action.
2. For multi-step goals, plan silently and execute step by step. Use `wait` after
   launching apps before interacting with them. `read_screen` returns the actual
   screenshot as an image you can SEE — use it to verify state, find buttons and
   their approximate positions before clicking, and confirm an action worked.
   When you see the target, give its approximate center coordinates in your
   reasoning and use mouse_move/mouse_click accordingly; prefer text labels and
   keyboard shortcuts (keyboard_hotkey) over raw coordinates whenever possible.
3. Verify before reporting: after a click/navigation, check the result (tool result
   summary, screen state) before telling the user it worked.
4. On failure: retry once if retryable, try a different approach, then honestly
   report what failed and what you suggest.
5. Use `remember` to persist durable user preferences, corrections, and workflows.
   If the user corrects you ("no, the other one"), save it as CORRECTIONS.
6. Be concise in final answers — a sentence or three. Skip inventories of steps.
7. Conversation vs command: if the user is chatting, chat back. If they issue an
   instruction, do it.
8. Destructive or sensitive actions (deleting files, sending messages, purchases,
   force-killing with unsaved work) go through the confirmation system — never
   attempt to bypass it.

What you know about the user so far:
$memoryBlock
""".trim()
    }

    /** Process one user turn; returns Amayra's final spoken/written reply. */
    suspend fun handleUserInput(input: String): String {
        StateBus.setState(AssistantState.Processing)
        history += ChatMessage(role = "user", content = input)

        val toolCtx = toolContext()
        var rounds = 0
        var lastAssistantText: String? = null

        while (rounds < config.maxToolRounds) {
            rounds++
            val req = ChatRequest(
                model = config.model,
                messages = listOf(ChatMessage(role = "system", content = systemPrompt())) + history.toList(),
                tools = registry.specs(),
                temperature = config.temperature,
                maxTokens = config.maxTokens
            )
            val resp = try {
                client.chat(req)
            } catch (e: AiError.Authentication) {
                StateBus.setState(AssistantState.Error(e.message ?: "auth")); return "I can't reach my brain — ${e.message}"
            } catch (e: AiError) {
                StateBus.setState(AssistantState.Recovering)
                history += ChatMessage(role = "user", content = "(system: provider error: ${e.message})")
                return "Something's wrong with my connection to the AI provider: ${e.message}. Give me a moment and try again."
            }

            if (resp.toolCalls.isNotEmpty()) {
                // Replay assistant function-call message for context.
                resp.toolCalls.forEach { call ->
                    history += ChatMessage(role = "assistant", content = "", toolName = call.name, toolArgsJson = call.argumentsJson, thoughtSignature = call.thoughtSignature)
                    StateBus.setState(AssistantState.ToolExecution(call.name))
                    val result = registry.execute(call.name, call.argumentsJson, toolCtx)
                    PcLog.i("BRAIN", "${call.name} -> ${result.ok}")
                    var imageB64: String? = null
                    var imageMime = "image/jpeg"
                    var resultContent = result.toStructuredJson(call.name)
                    if (call.name == "read_screen" && result is ToolResult.Success) {
                        val prepared = prepareScreenshot(result.dataJson)
                        if (prepared != null) {
                            imageB64 = prepared.first
                            imageMime = prepared.second
                            // Keep the JSON text small: the image rides as a separate part.
                            resultContent = resultContent.replace(Regex("\"base64\":\"[A-Za-z0-9+/=]+\""), "\"base64\":\"(attached as inline_data part)\"")
                        }
                    }
                    history += ChatMessage(role = "tool", content = resultContent, toolName = call.name,
                        imageBase64 = imageB64, imageMime = imageMime)
                }
                // After tool results, we may be verifying/observing next round.
                StateBus.setState(AssistantState.Verifying)
                continue
            }

            lastAssistantText = resp.text
            break
        }

        StateBus.setState(AssistantState.Responding)
        val reply = lastAssistantText
            ?: "I hit my step limit on that one without finishing — want me to keep going?"
        history += ChatMessage(role = "assistant", content = reply)
        // Keep history bounded.
        while (history.size > 60) history.removeAt(0)
        StateBus.reset()
        return reply
    }

    fun resetConversation() { history.clear() }

    /**
     * Downscale a captured screenshot to a token-efficient size for visual
     * verification (max 1280 px on the long edge, JPEG q0.6). Returns
     * (base64, mime) or null when the data is unusable.
     */
    private fun prepareScreenshot(dataJson: String?): Pair<String, String>? {
        val b64 = dataJson?.let {
            runCatching {
                val obj = Json.parseToJsonElement(it) as? JsonObject ?: return@runCatching null
                (obj["base64"] as? JsonPrimitive)?.content
            }.getOrNull()
        } ?: return null
        if (b64.isBlank()) return null
        return try {
            val raw = Base64.getDecoder().decode(b64)
            val img = ImageIO.read(java.io.ByteArrayInputStream(raw)) ?: return null
            val maxEdge = 1280
            val scale = minOf(1.0, maxEdge.toDouble() / maxOf(img.width, img.height))
            val out = ByteArrayOutputStream()
            if (scale < 1.0) {
                val w = (img.width * scale).toInt().coerceAtLeast(1)
                val h = (img.height * scale).toInt().coerceAtLeast(1)
                val resized = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB)
                val g = resized.createGraphics()
                g.drawImage(img, 0, 0, w, h, null)
                g.dispose()
                ImageIO.write(resized, "jpg", out)
            } else {
                ImageIO.write(img, "jpg", out)
            }
            Base64.getEncoder().encodeToString(out.toByteArray()) to "image/jpeg"
        } catch (t: Throwable) {
            PcLog.w("BRAIN", "Screenshot prep failed: ${t.message}")
            null
        }
    }
}
