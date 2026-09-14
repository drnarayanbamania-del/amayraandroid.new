package com.amayra.pc.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

/**
 * PC assistant configuration. API key can come from env var GEMINI_API_KEY,
 * or from config.json next to the program (never committed).
 */
@Serializable
data class PcConfig(
    val geminiApiKey: String = "",
    val model: String = "gemini-2.0-flash",
    val relayPort: Int = 18789,
    val temperature: Float = 0.6f,
    val maxTokens: Int = 2048,
    val maxToolRounds: Int = 12,
    val persona: String = "amayra"
) {
    companion object {
        fun load(dir: Path = Path.of("config")): PcConfig {
            val file = dir.resolve("config.json")
            val json = Json { ignoreUnknownKeys = true }
            val base = if (Files.exists(file)) {
                try {
                    json.decodeFromJsonElement(
                        PcConfig.serializer(),
                        json.parseToJsonElement(Files.readString(file))
                    )
                } catch (t: Throwable) {
                    PcLog.e("CONFIG", "Failed to parse ${file}, using defaults", t)
                    PcConfig()
                }
            } else PcConfig()
            val envKey = System.getenv("GEMINI_API_KEY")
            return if (!envKey.isNullOrBlank() && base.geminiApiKey.isBlank()) base.copy(geminiApiKey = envKey) else base
        }
    }
}
