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

    /** Milliseconds after a Gemini 429 before chat tries Gemini first again. */
    @Volatile private var geminiRateLimitedUntil = 0L

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

    /** Streams a full chat turn with tools to the given collector.
     *
     * Quota-aware: TTS always runs on Gemini (GeminiTtsClient), so when the
     * Gemini chat endpoint hits its daily budget (HTTP 429) the turn is retried
     * on the OpenAI-compat provider (Groq/OpenRouter/…, when a key exists) and
     * Gemini chat is skipped for RATE_LIMIT_COOLDOWN_MS — reserving the
     * remaining Gemini budget for the user's voice.
     */
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
        val hasCompatKey = !secure.getString(SecureStore.KEY_OPENAI_COMPAT).isNullOrBlank()
        val order = chatProviderOrder(
            primary = p.provider,
            hasCompatKey = hasCompatKey,
            geminiCoolingDown = geminiRateLimitedUntil > System.currentTimeMillis()
        )
        var failed: AiError? = null
        for (id in order) {
            failed = null
            // Spilled turns keep the Gemini model name — swap in a sane default
            // for the compat endpoint (Groq) so it isn't rejected as unknown.
            val turnReq = if (id == "openai_compat" && req.model.startsWith("gemini", ignoreCase = true)) {
                req.copy(model = FALLBACK_COMPAT_MODEL)
            } else req
            provider(id).chatStream(turnReq) { ev ->
                when (ev) {
                    is ChatStreamEvent.Deltas -> onDeltas(ev.text)
                    is ChatStreamEvent.ToolCallDetected -> onToolCall(ev.call)
                    is ChatStreamEvent.Done -> {}
                    is ChatStreamEvent.Failed -> failed = ev.error
                }
            }
            if (failed == null) return null
            // Gemini quota hit mid-turn → spill this turn (and the next few
            // minutes of chat) to the fallback provider. Any other error, or a
            // fallback-provider failure, surfaces to the user as-is.
            if (failed is AiError.RateLimit && id == "gemini" && order.size > 1) {
                geminiRateLimitedUntil = System.currentTimeMillis() + RATE_LIMIT_COOLDOWN_MS
                com.amayra.maya.core.MayaLog.i(
                    "AI",
                    "Gemini quota hit — chat falls back to OpenAI-compat provider for ${RATE_LIMIT_COOLDOWN_MS / 60_000} min (TTS keeps Gemini)"
                )
                continue
            }
            return failed
        }
        return failed
    }

    private fun provider(id: String): AiProvider = when (id) {
        "gemini" -> gemini
        "openai_compat", "groq", "openrouter", "openai" -> openAi
        else -> gemini
    }

    companion object {
        /** How long chat avoids Gemini after a 429 (TTS is unaffected). */
        const val RATE_LIMIT_COOLDOWN_MS = 10 * 60 * 1000L

        /** Model used when a Gemini-primary turn spills to the compat provider.
         * llama-3.3-70b-versatile went Enterprise-only on Groq (2026) — GPT-OSS
         * 120B is the free-tier production model with native tool calling. */
        const val FALLBACK_COMPAT_MODEL = "openai/gpt-oss-120b"

        /**
         * Ordered chat providers for this turn. Pure function so the fallback
         * policy is unit-testable without Android scaffolding:
         *  - non-Gemini primary: use it, no fallback configured.
         *  - Gemini primary without a compat key: Gemini only (honest failure).
         *  - Gemini cooling down: compat provider first, Gemini last (auto-resume).
         *  - otherwise: Gemini first, compat as the same-turn spill on 429.
         */
        fun chatProviderOrder(
            primary: String,
            hasCompatKey: Boolean,
            geminiCoolingDown: Boolean
        ): List<String> = when {
            primary != "gemini" -> listOf(primary)
            !hasCompatKey -> listOf("gemini")
            geminiCoolingDown -> listOf("openai_compat", "gemini")
            else -> listOf("gemini", "openai_compat")
        }

        fun newHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
