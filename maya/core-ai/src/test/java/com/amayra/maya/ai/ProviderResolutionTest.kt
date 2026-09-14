package com.amayra.maya.ai

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the single owner of provider/model resolution: garbage, cross-provider,
 * retired, and empty model ids must never reach the wire.
 */
class ProviderResolutionTest {

    // -- Gemini provider ----------------------------------------------------

    @Test
    fun `gemini keeps a valid gemini model`() {
        assertEquals("gemini-3.6-flash", ProviderResolution.resolveChatModel("gemini", "gemini-3.6-flash"))
    }

    @Test
    fun `gemini swaps a stray compat model for the gemini default`() {
        assertEquals(
            ProviderResolution.GEMINI_CHAT_MODEL,
            ProviderResolution.resolveChatModel("gemini", "llama-3.3-70b-versatile")
        )
    }

    @Test
    fun `gemini maps garbage to the gemini default`() {
        assertEquals(
            ProviderResolution.GEMINI_CHAT_MODEL,
            ProviderResolution.resolveChatModel("gemini", "Groq ko llama chahiye,")
        )
    }

    // -- OpenAI-compatible provider ------------------------------------------

    @Test
    fun `compat keeps a valid compat model`() {
        assertEquals(
            "llama-3.3-70b-versatile",
            ProviderResolution.resolveChatModel("openai_compat", "llama-3.3-70b-versatile")
        )
    }

    @Test
    fun `compat keeps provider-namespaced slugs like openai_gpt-oss-120b`() {
        // Regression: bare-prefix allowlist silently rewrote Groq's namespaced
        // ids back to the default — the user's saved model never stuck.
        assertEquals(
            "openai/gpt-oss-120b",
            ProviderResolution.resolveChatModel("openai_compat", "openai/gpt-oss-120b")
        )
        assertEquals(
            "meta-llama/llama-4-scout-17b",
            ProviderResolution.resolveChatModel("openai_compat", "meta-llama/llama-4-scout-17b")
        )
    }

    @Test
    fun `compat swaps a stray gemini model for the compat default`() {
        assertEquals(
            ProviderResolution.GROQ_CHAT_MODEL,
            ProviderResolution.resolveChatModel("openai_compat", "gemini-3.6-flash")
        )
    }

    @Test
    fun `compat maps garbage and blank and null to the compat default`() {
        assertEquals(
            ProviderResolution.GROQ_CHAT_MODEL,
            ProviderResolution.resolveChatModel("openai_compat", "llama-.37b-rsatiles")
        )
        assertEquals(
            ProviderResolution.GROQ_CHAT_MODEL,
            ProviderResolution.resolveChatModel("openai_compat", "")
        )
        assertEquals(
            ProviderResolution.GROQ_CHAT_MODEL,
            ProviderResolution.resolveChatModel("openai_compat", null)
        )
    }

    // -- Retired models -------------------------------------------------------

    @Test
    fun `retired chat models migrate to the gemini default`() {
        assertEquals(ProviderResolution.GEMINI_CHAT_MODEL, ProviderResolution.resolveChatModel("gemini", "gemini-2.0-flash"))
        assertEquals(ProviderResolution.GEMINI_CHAT_MODEL, ProviderResolution.resolveChatModel("gemini", "gemini-2.5-flash"))
    }

    @Test
    fun `retired tts models migrate to the current tts default`() {
        assertEquals(
            ProviderResolution.GEMINI_TTS_MODEL,
            ProviderResolution.resolveTtsModel("gemini-2.5-flash-tts")
        )
        assertEquals(
            ProviderResolution.GEMINI_TTS_MODEL,
            ProviderResolution.resolveTtsModel(null)
        )
        assertEquals("my-custom-tts", ProviderResolution.resolveTtsModel("my-custom-tts"))
    }
}
