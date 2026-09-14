package com.amayra.maya.core

/**
 * Formal assistant state machine (mirrors reference behavior model):
 * IDLE -> LISTENING -> PROCESSING -> TOOL_EXECUTION -> RESPONDING -> SPEAKING -> IDLE
 * plus ERROR and SLEEPING terminals.
 */
sealed interface AssistantState {
    val label: String

    data object Idle : AssistantState { override val label = "Idle" }
    data class Listening(val partial: String = "") : AssistantState { override val label = "Listening" }
    data object Processing : AssistantState { override val label = "Thinking" }
    data class ToolExecution(val tool: String) : AssistantState { override val label = "Running $tool" }
    data object Responding : AssistantState { override val label = "Responding" }
    data class Speaking(val text: String = "") : AssistantState { override val label = "Speaking" }
    data object Sleeping : AssistantState { override val label = "Sleeping" }
    data class Error(val message: String) : AssistantState { override val label = "Error" }

    companion object {
        /** Terminal states a turn may return to Idle from. */
        fun isActive(state: AssistantState): Boolean = when (state) {
            is Idle, is Sleeping -> false
            else -> true
        }
    }
}

/** Avatar emotional state (drives avatar rendering). */
enum class Emotion { NEUTRAL, HAPPY, SAD, ANGRY, SURPRISED, SLEEPY }

/** Global state bus — single source of truth consumed by UI, voice and avatar. */
object StateBus {
    private val _state = kotlinx.coroutines.flow.MutableStateFlow<AssistantState>(AssistantState.Idle)
    val state: kotlinx.coroutines.flow.StateFlow<AssistantState> = _state

    private val _emotion = kotlinx.coroutines.flow.MutableStateFlow(Emotion.NEUTRAL)
    val emotion: kotlinx.coroutines.flow.StateFlow<Emotion> = _emotion

    fun setState(s: AssistantState) { _state.value = s }
    fun setEmotion(e: Emotion) { _emotion.value = e }
    fun toIdleIfActive() {
        if (AssistantState.isActive(_state.value) && _state.value !is AssistantState.Listening) {
            _state.value = AssistantState.Idle
        }
    }

    /** Fired by WakeWordService when "Hey Maya" triggers. */
    private val _wakeSignal = kotlinx.coroutines.flow.MutableSharedFlow<Long>(extraBufferCapacity = 4)
    val wakeSignal: kotlinx.coroutines.flow.SharedFlow<Long> = _wakeSignal
    fun publishWake() { _wakeSignal.tryEmit(System.nanoTime()) }

    /** Guardian verification results (label to probability) for UI/diagnostics. */
    private val _guardianSignal = kotlinx.coroutines.flow.MutableSharedFlow<GuardianSignal>(extraBufferCapacity = 4)
    val guardianSignal: kotlinx.coroutines.flow.SharedFlow<GuardianSignal> = _guardianSignal
    fun publishGuardian(s: GuardianSignal) { _guardianSignal.tryEmit(s) }

    /** Emitted when the accessibility service starts/stops — UI reflects capability state. */
    private val _accessibilityLive = kotlinx.coroutines.flow.MutableStateFlow(false)
    val accessibilityLive: kotlinx.coroutines.flow.StateFlow<Boolean> = _accessibilityLive
    fun setAccessibilityLive(v: Boolean) { _accessibilityLive.value = v }

    data class GuardianSignal(val label: String, val probability: Float, val accepted: Boolean)

    /** Screen-share consent request (UI shows the MediaProjection dialog). */
    private val _screenShareRequest = kotlinx.coroutines.flow.MutableSharedFlow<Boolean>(extraBufferCapacity = 2)
    val screenShareRequest: kotlinx.coroutines.flow.SharedFlow<Boolean> = _screenShareRequest
    fun requestScreenShareStart() { _screenShareRequest.tryEmit(true) }
    fun requestScreenShareStop() { _screenShareRequest.tryEmit(false) }

    /** Barcode scanner open request (UI navigates to the scanner screen). */
    private val _scanRequest = kotlinx.coroutines.flow.MutableSharedFlow<Long>(extraBufferCapacity = 2)
    val scanRequest: kotlinx.coroutines.flow.SharedFlow<Long> = _scanRequest
    fun requestScan() { _scanRequest.tryEmit(System.nanoTime()) }

    /** Navigate to the AI settings screen (chat error banner "Fix" action). */
    private val _settingsRequest = kotlinx.coroutines.flow.MutableSharedFlow<Long>(extraBufferCapacity = 2)
    val settingsRequest: kotlinx.coroutines.flow.SharedFlow<Long> = _settingsRequest
    fun requestAiSettings() { _settingsRequest.tryEmit(System.nanoTime()) }

    /** Navigate to the diagnostics screen (engine-status dialog action). */
    private val _diagRequest = kotlinx.coroutines.flow.MutableSharedFlow<Long>(extraBufferCapacity = 2)
    val diagRequest: kotlinx.coroutines.flow.SharedFlow<Long> = _diagRequest
    fun requestDiagnostics() { _diagRequest.tryEmit(System.nanoTime()) }
}
