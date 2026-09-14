package com.amayra.maya.ai

import com.amayra.maya.settings.SettingsRepository
import com.amayra.maya.memory.SecureStore
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Facade over the configured provider. Picks Gemini or an OpenAI-compatible
 * endpoint from user settings and exposes streaming + one-shot helpers.
 */
class AiClient(
    private val http: OkHttpClient,
    private val settings: SettingsRepository,
    private val secure: SecureStore
) {
    private val gemini by lazy { GeminiClient(http, keyProvider = { secure.getString(SecureStore.KEY_GEMINI) }) }
    private val openAi by lazy {
        OpenAiCompatClient(
            http,
            baseUrlProvider = { settings.prefsCache?.baseUrl ?: OpenAiCompatClient.PRESET_BASE_URLS[0] },
            keyProvider = { secure.getString(SecureStore.KEY_OPENAI_COMPAT) }
        )
    }

    /**
     * One-shot multimodal describe: sends one image (data URL) + question to the
     * configured vision-capable model. Returns null on failure (logged honestly).
     */
    suspend fun visionOnce(imageDataUrl: String, question: String): String? {
        val p = settings.prefs.first()
        val req = ChatRequest(
            model = p.model,
            messages = listOf(
                ChatMessage(role = "system", content = "You describe phone screens precisely and concisely for a voice assistant. State the app, the screen's purpose, and the actionable elements (buttons, fields, lists) with their exact labels."),
                ChatMessage(role = "user", content = question, imageUri = imageDataUrl)
            ),
            temperature = 0.3f,
            maxTokens = 400
        )
        val sb = StringBuilder()
        var failed: AiError? = null
        provider(p.provider).chatStream(req) { ev ->
            when (ev) {
                is ChatStreamEvent.Deltas -> sb.append(ev.text)
                is ChatStreamEvent.ToolCallDetected -> {}
                is ChatStreamEvent.Done -> {}
                is ChatStreamEvent.Failed -> failed = ev.error
            }
        }
        failed?.let {
            com.amayra.maya.core.MayaLog.w("AI", "visionOnce failed: ${it.message}")
            return null
        }
        return sb.toString().takeIf { it.isNotBlank() }
    }

    /** Blocking-free one-shot completion; returns null with a MayaLog entry on failure. */
    suspend fun completeOnce(system: String, user: String): String? {
        val p = settings.prefs.first()
        val req = ChatRequest(
            model = p.model,
            messages = listOf(
                ChatMessage(role = "system", content = system),
                ChatMessage(role = "user", content = user)
            ),
            temperature = 0.6f,
            maxTokens = 300
        )
        val sb = StringBuilder()
        var failed: AiError? = null
        provider(p.provider).chatStream(req) { ev ->
            when (ev) {
                is ChatStreamEvent.Deltas -> sb.append(ev.text)
                is ChatStreamEvent.ToolCallDetected -> {}
                is ChatStreamEvent.Done -> {}
                is ChatStreamEvent.Failed -> failed = ev.error
            }
        }
        failed?.let {
            com.amayra.maya.core.MayaLog.w("AI", "completeOnce failed: ${it.message}")
            return null
        }
        return sb.toString().takeIf { it.isNotBlank() }
    }

    /** Streams a full chat turn with tools to the given collector. */
    suspend fun streamChat(
        messages: List<ChatMessage>,
        tools: List<ToolSpec>,
        onDeltas: suspend (String) -> Unit,
        onToolCall: suspend (ToolCall) -> Unit
    ): AiError? {
        val p = settings.prefs.first()
        val req = ChatRequest(
            model = p.model,
            messages = messages,
            tools = tools,
            temperature = p.temperature,
            maxTokens = p.maxTokens
        )
        var failed: AiError? = null
        provider(p.provider).chatStream(req) { ev ->
            when (ev) {
                is ChatStreamEvent.Deltas -> onDeltas(ev.text)
                is ChatStreamEvent.ToolCallDetected -> onToolCall(ev.call)
                is ChatStreamEvent.Done -> {}
                is ChatStreamEvent.Failed -> failed = ev.error
            }
        }
        return failed
    }

    private fun provider(id: String): AiProvider = when (id) {
        "gemini" -> gemini
        "openai_compat", "groq", "openrouter", "openai" -> openAi
        else -> gemini
    }

    companion object {
        fun newHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
