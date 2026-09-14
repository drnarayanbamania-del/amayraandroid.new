package com.amayra.maya.ai

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Quota-aware chat provider ordering (AiClient.chatProviderOrder).
 *
 * Policy: TTS always runs on Gemini (GeminiTtsClient), so Gemini's remaining
 * daily budget belongs to the user's voice. When Gemini chat 429s, chat spills
 * to the OpenAI-compat provider for a cooldown window, then auto-resumes.
 */
class AiClientFallbackTest {

    @Test
    fun `non-gemini primary uses only that provider`() {
        assertEquals(
            listOf("openai_compat"),
            AiClient.chatProviderOrder(primary = "openai_compat", hasCompatKey = true, geminiCoolingDown = false)
        )
        assertEquals(
            listOf("openai_compat"),
            AiClient.chatProviderOrder(primary = "openai_compat", hasCompatKey = false, geminiCoolingDown = true)
        )
    }

    @Test
    fun `gemini without a compat key stays gemini-only (honest failure)`() {
        assertEquals(
            listOf("gemini"),
            AiClient.chatProviderOrder(primary = "gemini", hasCompatKey = false, geminiCoolingDown = false)
        )
        assertEquals(
            listOf("gemini"),
            AiClient.chatProviderOrder(primary = "gemini", hasCompatKey = false, geminiCoolingDown = true)
        )
    }

    @Test
    fun `gemini primary with compat key spills to fallback on quota`() {
        assertEquals(
            listOf("gemini", "openai_compat"),
            AiClient.chatProviderOrder(primary = "gemini", hasCompatKey = true, geminiCoolingDown = false)
        )
    }

    @Test
    fun `during cooldown compat leads and gemini auto-resumes last`() {
        assertEquals(
            listOf("openai_compat", "gemini"),
            AiClient.chatProviderOrder(primary = "gemini", hasCompatKey = true, geminiCoolingDown = true)
        )
    }
}
