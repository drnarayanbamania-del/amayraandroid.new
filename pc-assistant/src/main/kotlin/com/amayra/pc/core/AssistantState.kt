package com.amayra.pc.core

/**
 * Amayra PC assistant state machine — extends the Android app's model with the
 * agentic states from the design brief:
 * IDLE, PROCESSING, PLANNING, TOOL_EXECUTION, OBSERVING, VERIFYING,
 * WAITING, RECOVERING, RESPONDING, ERROR.
 */
sealed interface AssistantState {
    val label: String

    data object Idle : AssistantState { override val label = "Idle" }
    data class Listening(val partial: String = "") : AssistantState { override val label = if (partial.isBlank()) "Listening" else "Hearing: $partial" }
    data object Processing : AssistantState { override val label = "Thinking" }
    data object Planning : AssistantState { override val label = "Planning" }
    data class ToolExecution(val tool: String) : AssistantState { override val label = "Running $tool" }
    data object Observing : AssistantState { override val label = "Observing" }
    data object Verifying : AssistantState { override val label = "Verifying" }
    data object Waiting : AssistantState { override val label = "Waiting" }
    data object Recovering : AssistantState { override val label = "Recovering" }
    data object Responding : AssistantState { override val label = "Responding" }
    data class Speaking(val text: String = "") : AssistantState { override val label = "Speaking" }
    data class Error(val message: String) : AssistantState { override val label = "Error: $message" }
}

/** Global state bus for UI, CLI and relay consumers. */
object StateBus {
    private val _state = kotlinx.coroutines.flow.MutableStateFlow<AssistantState>(AssistantState.Idle)
    val state: kotlinx.coroutines.flow.StateFlow<AssistantState> = _state

    fun setState(s: AssistantState) { _state.value = s }
    fun reset() { _state.value = AssistantState.Idle }
}
