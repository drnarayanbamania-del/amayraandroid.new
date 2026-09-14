package com.amayra.pc.ai

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Provider-neutral chat message. */
@Serializable
data class ChatMessage(
    val role: String, // system | user | assistant | tool
    val content: String,
    val toolName: String? = null,
    val toolArgsJson: String? = null,
    /** Gemini 3.x thought_signature from the original functionCall — must be replayed with it. */
    val thoughtSignature: String? = null,
    /** Optional inline image (base64, no data: prefix) attached to this message. */
    val imageBase64: String? = null,
    val imageMime: String = "image/jpeg"
)

@Serializable
data class ToolSpec(
    val name: String,
    val description: String,
    val parameters: JsonObject
)

@Serializable
data class ToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String,
    val thoughtSignature: String? = null
)

data class ChatResponse(
    val text: String?,
    val toolCalls: List<ToolCall> = emptyList()
)

sealed class AiError(message: String) : Exception(message) {
    class Authentication(message: String = "Invalid or missing Gemini API key.") : AiError(message)
    class RateLimit(message: String = "Rate limit exceeded.") : AiError(message)
    class Network(message: String, cause: Throwable? = null) : AiError(message)
    class Provider(message: String) : AiError(message)
}

data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val tools: List<ToolSpec> = emptyList(),
    val temperature: Float,
    val maxTokens: Int
)
