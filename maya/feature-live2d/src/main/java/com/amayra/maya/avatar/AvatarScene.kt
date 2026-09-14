package com.amayra.maya.avatar

import androidx.compose.ui.graphics.drawscope.DrawScope
import com.amayra.maya.core.AssistantState
import com.amayra.maya.core.Emotion

/**
 * Everything a renderer needs to draw one frame of the avatar.
 * Produced by [AvatarController] from Maya's REAL state machine (StateBus) —
 * never a parallel state system.
 */
data class AvatarScene(
    val state: AssistantState,
    val emotion: Emotion,
    /** Ambient clock in seconds; frozen when idle animation is off (static pose). */
    val timeSec: Float,
    /** Speech clock in seconds; always advances while speaking. */
    val speechSec: Float,
    /** 0..1 smoothed mouth-open amount (already gated by the speaking-anim setting). */
    val mouthOpen: Float,
    /** 0..1 progress through the current utterance estimate (1+ when done). */
    val speakProgress: Float,
    /** -1..1 subtle head/eye attention offsets (drift + thinking). */
    val attentionX: Float,
    val attentionY: Float
)

/**
 * Pluggable avatar renderer. The 2D holographic renderer ships today; a Live2D or
 * lightweight-3D renderer can be added behind this same interface without touching
 * the voice system, the state machine, or the UI — they only ever see [AvatarScene].
 */
interface AvatarRenderer {
    val styleId: String
    val styleLabel: String
    /** Draw one frame. Implementations must be pure draw code (no state ownership). */
    fun draw(scope: DrawScope, scene: AvatarScene)
}

/** Registry of available avatar styles (drives the Settings style picker). */
object AvatarRenderers {
    private val holographic = HolographicAvatarRenderer()
    private val registered = mutableListOf<AvatarRenderer>()

    val available: List<AvatarRenderer>
        get() = listOf(holographic) + registered.toList()

    /** Adds a renderer (e.g. the Live2D style) at app start. */
    fun register(renderer: AvatarRenderer) {
        if (registered.none { it.styleId == renderer.styleId }) registered += renderer
    }

    fun byId(id: String): AvatarRenderer =
        available.firstOrNull { it.styleId == id } ?: holographic

    fun labelOf(id: String): String = byId(id).styleLabel
}
