package com.amayra.maya.ai

/**
 * THE single owner of provider/model resolution. SettingsRepository maps stored
 * DataStore values through these pure functions; nothing else decides which
 * model id reaches the wire.
 *
 * Invariants (each pinned by a test):
 *  - hand-typed garbage never reaches a provider (falls back to that provider's default)
 *  - a model id belonging to the other provider family is swapped for the
 *    active provider's default (a Groq model on Gemini 404s and vice-versa)
 *  - retired model names migrate to current defaults
 */
object ProviderResolution {

    const val PROVIDER_GEMINI = "gemini"
    const val PROVIDER_OPENAI_COMPAT = "openai_compat"

    const val GEMINI_CHAT_MODEL = "gemini-3.6-flash"
    const val GROQ_CHAT_MODEL = "llama-3.3-70b-versatile"

    const val GEMINI_TTS_MODEL = "gemini-3.1-flash-tts-preview"

    /** Retired chat models → current Gemini default (only meaningful on the Gemini provider). */
    private val RETIRED_CHAT_MODELS = setOf("gemini-2.0-flash", "gemini-2.5-flash")

    /** Retired TTS models → current Gemini TTS default. */
    private val RETIRED_TTS_MODELS = setOf(
        "gemini-2.5-flash-preview-tts", "gemini-2.5-flash-tts",
        "gemini-2.5-pro-tts", "gemini-2.5-flash-lite-preview-tts"
    )

    private val MODEL_ID = Regex("[a-zA-Z0-9._/-]+")

    /** Known-good compat model prefixes; anything else on a compat provider is junk. */
    private val KNOWN_COMPAT_PREFIXES = listOf("llama", "mixtral", "gemma", "qwen", "deepseek", "mistral")

    fun isCompat(provider: String) = provider != PROVIDER_GEMINI

    fun defaultChatModel(provider: String) =
        if (isCompat(provider)) GROQ_CHAT_MODEL else GEMINI_CHAT_MODEL

    /**
     * Resolve the chat model id for [provider] from the stored preference.
     * Pure: same inputs always yield the same output; no I/O, no DataStore.
     */
    fun resolveChatModel(provider: String, stored: String?): String {
        if (stored == null) return defaultChatModel(provider)
        if (stored in RETIRED_CHAT_MODELS) return GEMINI_CHAT_MODEL
        // Well-formed model ids have no empty/garbled segments: "llama-.37b-…"
        // (keyboard corruption) is rejected even though it regex-matches.
        val valid = stored.isNotBlank() && stored.matches(MODEL_ID) &&
            !stored.contains("-.") && !stored.contains("..") && !stored.endsWith("-")
        return when {
            !valid -> defaultChatModel(provider)
            !isCompat(provider) && !stored.startsWith("gemini") -> GEMINI_CHAT_MODEL
            isCompat(provider) && stored.startsWith("gemini") -> GROQ_CHAT_MODEL
            isCompat(provider) && KNOWN_COMPAT_PREFIXES.none { stored.startsWith(it) } -> GROQ_CHAT_MODEL
            else -> stored
        }
    }

    /** Retired TTS models migrate to the current default; everything else passes through. */
    fun resolveTtsModel(stored: String?): String =
        if (stored == null || stored in RETIRED_TTS_MODELS) GEMINI_TTS_MODEL else stored
}
