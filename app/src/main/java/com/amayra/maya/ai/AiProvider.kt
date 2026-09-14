package com.amayra.maya.ai

/** Provider-neutral AI client interface. Implementations stream events. */
interface AiProvider {
    val id: String

    /**
     * Streams a chat completion. Exactly one terminal event is emitted
     * (Done or Failed); Delta/Deltas/ToolCallDelta events precede it.
     */
    suspend fun chatStream(req: ChatRequest, onEvent: suspend (ChatStreamEvent) -> Unit)
}
