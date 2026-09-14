package com.amayra.maya.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Live agent introspection for the developer debug panel (blueprint §OBSERVABILITY).
 *
 * Everything here is *emitted from the real execution paths* — the tool registry's
 * execution choke point and the cognitive core's turn loop — so the panel shows
 * what actually ran, not what was planned. Args/summaries are redacted through
 * [MayaLog.redact] before storage, so keys/tokens can never leak into the UI.
 *
 * Pure Kotlin + coroutines: unit-testable without Android.
 */
object AgentDebugBus {

    /** Outcome kinds: running → success | failure | confirm | declined. */
    data class ToolEvent(
        val id: Long,
        val tool: String,
        val argsJson: String,      // redacted, truncated
        val outcome: String,       // "running" | "success" | "failure" | "confirm" | "declined"
        val summary: String,       // redacted, truncated
        val durationMs: Long,      // 0 while running
        val epochMs: Long,
    )

    data class TurnEvent(
        val epochMs: Long,
        val phase: String,         // "start" | "done" | "error" | "cancel"
        val detail: String,        // redacted, truncated
    )

    private const val MAX_TOOL_EVENTS = 40
    private const val MAX_TURN_EVENTS = 12
    private const val MAX_TEXT = 240

    private val ids = java.util.concurrent.atomic.AtomicLong(0)

    private val _toolEvents = MutableStateFlow<List<ToolEvent>>(emptyList()) // newest first
    val toolEvents: StateFlow<List<ToolEvent>> = _toolEvents

    private val _currentTool = MutableStateFlow<ToolEvent?>(null)
    val currentTool: StateFlow<ToolEvent?> = _currentTool

    private val _turnEvents = MutableStateFlow<List<TurnEvent>>(emptyList()) // newest first
    val turnEvents: StateFlow<List<TurnEvent>> = _turnEvents

    /** Registry starts a real execution; returns the id to finish it with. */
    fun toolStarted(tool: String, argsJson: String): Long {
        val id = ids.incrementAndGet()
        upsertTool(
            ToolEvent(
                id = id, tool = tool,
                argsJson = clip(MayaLog.redact(argsJson)),
                outcome = "running", summary = "", durationMs = 0, epochMs = System.currentTimeMillis(),
            )
        )
        return id
    }

    /** Instantly-rejected executions (unknown tool / malformed args): no running phase. */
    fun toolRejected(tool: String, argsJson: String, error: String) {
        upsertTool(
            ToolEvent(
                id = ids.incrementAndGet(), tool = tool,
                argsJson = clip(MayaLog.redact(argsJson)),
                outcome = "failure", summary = clip(MayaLog.redact(error)),
                durationMs = 0, epochMs = System.currentTimeMillis(),
            )
        )
    }

    /** Registry finishes the execution started with [id]. */
    fun toolFinished(id: Long, outcome: String, summary: String, durationMs: Long) {
        synchronized(this) {
            val list = _toolEvents.value
            val idx = list.indexOfFirst { it.id == id }
            if (idx >= 0) {
                val e = list[idx]
                val updated = e.copy(
                    outcome = outcome,
                    summary = clip(MayaLog.redact(summary)),
                    durationMs = durationMs,
                )
                _toolEvents.value = list.toMutableList().apply { set(idx, updated) }
                if (_currentTool.value?.id == id) _currentTool.value = null
            }
        }
    }

    fun turnStarted(userText: String) {
        pushTurn(TurnEvent(System.currentTimeMillis(), "start", clip(MayaLog.redact(userText))))
    }

    fun turnFinished(detail: String) {
        pushTurn(TurnEvent(System.currentTimeMillis(), "done", clip(MayaLog.redact(detail))))
    }

    fun turnError(detail: String) {
        pushTurn(TurnEvent(System.currentTimeMillis(), "error", clip(MayaLog.redact(detail))))
    }

    fun turnCancelled() {
        pushTurn(TurnEvent(System.currentTimeMillis(), "cancel", "cancelled by user"))
    }

    fun clear() {
        synchronized(this) {
            _toolEvents.value = emptyList()
            _turnEvents.value = emptyList()
            _currentTool.value = null
        }
    }

    // -- internals -----------------------------------------------------------

    private fun upsertTool(e: ToolEvent) {
        synchronized(this) {
            _toolEvents.value = (listOf(e) + _toolEvents.value).take(MAX_TOOL_EVENTS)
            if (e.outcome == "running") _currentTool.value = e
        }
    }

    private fun pushTurn(e: TurnEvent) {
        synchronized(this) {
            _turnEvents.value = (listOf(e) + _turnEvents.value).take(MAX_TURN_EVENTS)
        }
    }

    private fun clip(s: String) = if (s.length > MAX_TEXT) s.take(MAX_TEXT) + "…" else s
}
