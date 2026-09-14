package com.amayra.maya.ai

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** A single message in the provider-neutral conversation format. */
@Serializable
data class ChatMessage(
    val role: String, // system | user | assistant | tool
    val content: String,
    val toolCallId: String? = null,
    val toolName: String? = null,
    /** Raw arguments JSON of the tool call (tool role) — replayed to Gemini as functionCall.args. */
    val toolArgsJson: String? = null,
    /** Gemini 3.x thought_signature from the original functionCall — must be replayed with it. */
    val thoughtSignature: String? = null,
    /** Base64 data URL or https URL for image attachments (user role only). */
    val imageUri: String? = null
)

/** OpenAI-style tool function declaration sent to the provider. */
@Serializable
data class ToolSpec(
    val name: String,
    val description: String,
    /** JSON schema for the tool parameters (OpenAI function-calling style). */
    val parameters: JsonObject
)

/** A tool call requested by the model. */
@Serializable
data class ToolCall(
    val id: String,
    val name: String,
    /** Raw JSON arguments as produced by the model. */
    val argumentsJson: String,
    /** Gemini 3.x thought_signature accompanying the functionCall (replay-required). */
    val thoughtSignature: String? = null
)

/** Result of a chat completion. */
data class ChatResponse(
    val text: String?,
    val toolCalls: List<ToolCall> = emptyList(),
    val finishReason: String? = null
)

/** Incremental chat stream events. */
sealed interface ChatStreamEvent {
    data class Deltas(val text: String) : ChatStreamEvent
    /** Model requested a tool; completeArgsJson carries the full arguments JSON. */
    data class ToolCallDetected(val call: ToolCall) : ChatStreamEvent
    data class Done(val finishReason: String?) : ChatStreamEvent
    data class Failed(val error: AiError) : ChatStreamEvent
}

/** Provider-neutral structured errors (never fake success). */
sealed class AiError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Authentication(message: String = "Invalid or missing API key. Check Settings → AI.") : AiError(message)
    class RateLimit(message: String = "Rate limit / quota exceeded. Wait and retry.") : AiError(message)
    class Network(message: String = "Network unreachable. Check connection.", cause: Throwable? = null) : AiError(message, cause)
    class Timeout(message: String = "Request timed out.") : AiError(message)
    class Provider(message: String) : AiError(message)
    class Unsupported(message: String = "This provider/model does not support that feature.") : AiError(message)
}

/** Chat turn request passed to providers. */
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val tools: List<ToolSpec> = emptyList(),
    val temperature: Float,
    val maxTokens: Int
)
