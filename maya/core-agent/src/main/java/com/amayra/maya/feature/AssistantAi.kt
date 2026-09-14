package com.amayra.maya.feature

/**
 * AI surface available to tool modules without importing the composition root.
 * Implemented by [com.amayra.maya.ai.AiClient] and registered by the app module.
 */
interface AssistantAi {
    suspend fun visionOnce(imageDataUrl: String, question: String): String?
}

/** Holder for the registered [AssistantAi]; null until AppGraph wires it. */
object AssistantAiHolder {
    @Volatile var ai: AssistantAi? = null
}
