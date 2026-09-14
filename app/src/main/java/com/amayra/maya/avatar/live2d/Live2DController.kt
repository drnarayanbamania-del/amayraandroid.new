package com.amayra.maya.avatar.live2d

import com.amayra.maya.avatar.AvatarController
import com.amayra.maya.avatar.AvatarScene
import com.amayra.maya.core.AssistantState
import com.amayra.maya.core.MayaLog
import com.amayra.maya.settings.SettingsRepository
import kotlin.random.Random

/**
 * Drives the STANDARD Cubism parameter ids for one frame, from Maya's real state
 * machine and the shared [AvatarController] scene (which is already fed by TTS).
 *
 * This class owns Live2D-specific animation policy — natural randomized blinking,
 * eye attention, mouth/lip-sync values, motion-group selection and expression
 * application. It holds no rendering code: an engine binding (Cubism SDK or future
 * re-implementation) consumes the values via [Live2DFrameParams].
 */
class Live2DController(
    private val repository: Live2DModelRepository,
    private val avatar: AvatarController,
    private val settings: SettingsRepository
) {

    /** Parameter values for the current frame, in the standard Cubism namespace. */
    data class Live2DFrameParams(
        val mouthOpenY: Float = 0f,
        val mouthForm: Float = 0f,
        val eyeLOpen: Float = 1f,
        val eyeROpen: Float = 1f,
        val eyeBallX: Float = 0f,
        val eyeBallY: Float = 0f,
        val angleX: Float = 0f,
        val angleY: Float = 0f,
        val angleZ: Float = 0f,
        val bodyAngleX: Float = 0f,
        val breath: Float = 0f,
        /** Motion group the engine should play for this frame's state. */
        val motionGroup: String = Live2DFormat.MotionGroups.IDLE,
        /** Expression file name applied for this frame's emotion, if any. */
        val expression: String? = null
    )

    // Blink state machine: randomized interval, 60-140ms close, human-like.
    private var nextBlinkAt = 0.0
    private var blinkStart = -1.0
    private val blinkDurationSec = 0.11f

    // Current motion group so the engine can detect changes (restart motion on switch).
    var currentMotionGroup: String = Live2DFormat.MotionGroups.IDLE
        private set

    /** Currently applied expression name (from settings-driven emotion mapping). */
    var currentExpression: String? = null
        private set

    /** Tap reaction: temporarily plays the greeting motion group (if the model has one). */
    @Volatile private var tapOverrideUntil: Double = 0.0

    /** Called when the user taps the avatar (lightweight, never blocks input). */
    fun onTap() {
        tapOverrideUntil = System.nanoTime() / 1_000_000_000.0 + 2.5
        MayaLog.i("L2D", "Tap reaction: greeting override for 2.5s")
    }

    /** Computes the parameter set for the given scene. Pure + cheap (per-frame safe). */
    fun frame(scene: AvatarScene, nowSec: Double): Live2DFrameParams {
        val p = settings.prefsCache
        val speaking = scene.state is AssistantState.Speaking

        // ── Mouth / lip-sync ────────────────────────────────────────────
        // Amplitude-driven: the shared AvatarController derives mouthOpen from the
        // TTS utterance lifecycle (onSpeechStart/onSpeechEnd from VoiceController).
        // This is honest amplitude-based lip sync — NOT phoneme-level.
        val lipsyncOn = p?.lipsyncEnabled ?: true
        val speakingAnimOn = p?.speakingAnimEnabled ?: true
        val mouthOpen = if (lipsyncOn && speakingAnimOn) scene.mouthOpen else 0f

        // ── Blinking (randomized natural interval) ──────────────────────
        val blinkOn = p?.blinkingEnabled ?: true
        var eyeOpen = 1f
        if (blinkOn && scene.timeSec > 0f) {
            if (nextBlinkAt == 0.0) nextBlinkAt = nowSec + nextBlinkDelay()
            if (blinkStart < 0 && nowSec >= nextBlinkAt) {
                blinkStart = nowSec
                nextBlinkAt = nowSec + nextBlinkDelay()
            }
            if (blinkStart >= 0) {
                val phase = ((nowSec - blinkStart) / blinkDurationSec).toFloat()
                if (phase >= 1f) blinkStart = -1.0
                else eyeOpen = 1f - kotlin.math.sin(phase * Math.PI.toFloat()) // close→open
            }
            // Suppress blinking during strong expressions (surprised keeps eyes wide).
            if (scene.emotion == com.amayra.maya.core.Emotion.SURPRISED) eyeOpen = 1f
        }

        // ── Eyes / head attention ───────────────────────────────────────
        val eyeX = scene.attentionX * 0.9f
        val eyeY = scene.attentionY * 0.6f
        val headX = scene.attentionX * 12f   // degrees, standard AngleX range (-30..30)
        val headY = scene.attentionY * 8f

        // Breathing: slow sinusoidal breath parameter (standard range 0..1).
        val breath = if (scene.timeSec > 0f)
            (kotlin.math.sin(scene.timeSec * 1.6f) * 0.5f + 0.5f) else 0f

        // ── Motion group selection ─────────────────────────────────────
        val requested = if (nowSec < tapOverrideUntil) Live2DFormat.MotionGroups.GREETING
        else Live2DFormat.motionGroupFor(scene.state)
        val available = repository.status().motionGroups
        val group = if (requested in available || available.isEmpty()) requested
        else gracefulGroup(requested, available)
        if (group != currentMotionGroup) {
            currentMotionGroup = group
            MayaLog.i("L2D", "Motion group -> $group")
        }

        // ── Expression from emotion (only when the model ships them) ────
        val exprsOn = p?.expressionsEnabled ?: true
        currentExpression = if (exprsOn) expressionForEmotion(scene.emotion) else null

        return Live2DFrameParams(
            mouthOpenY = mouthOpen,
            mouthForm = when (scene.emotion) {
                com.amayra.maya.core.Emotion.HAPPY -> 0.8f
                com.amayra.maya.core.Emotion.SAD -> -0.4f
                else -> 0.3f
            },
            eyeLOpen = eyeOpen,
            eyeROpen = eyeOpen,
            eyeBallX = eyeX,
            eyeBallY = eyeY,
            angleX = headX,
            angleY = headY,
            angleZ = scene.attentionX * 4f,
            bodyAngleX = scene.attentionX * 6f,
            breath = breath,
            motionGroup = group,
            expression = currentExpression
        )
    }

    /** Randomized natural blink delay: 2.2–6.5s, occasionally a quick double-blink. */
    private fun nextBlinkDelay(): Double {
        val base = Random.nextDouble(2.2, 6.5)
        return if (Random.nextDouble() < 0.12) base + 0.28 else base
    }

    /** Graceful motion fallback chain when the model lacks the requested group. */
    private fun gracefulGroup(requested: String, available: List<String>): String = when (requested) {
        Live2DFormat.MotionGroups.THINKING ->
            if (Live2DFormat.MotionGroups.LISTENING in available) Live2DFormat.MotionGroups.LISTENING else Live2DFormat.MotionGroups.IDLE
        Live2DFormat.MotionGroups.SPEAKING ->
            if (Live2DFormat.MotionGroups.GREETING in available) Live2DFormat.MotionGroups.GREETING else Live2DFormat.MotionGroups.IDLE
        Live2DFormat.MotionGroups.ERROR ->
            if (Live2DFormat.MotionGroups.CONCERNED in available) Live2DFormat.MotionGroups.CONCERNED else Live2DFormat.MotionGroups.IDLE
        Live2DFormat.MotionGroups.LISTENING ->
            if (Live2DFormat.MotionGroups.GREETING in available) Live2DFormat.MotionGroups.GREETING else Live2DFormat.MotionGroups.IDLE
        else -> Live2DFormat.MotionGroups.IDLE
    }

    /** Emotion → expression file name (only applied if the model defines it). */
    private fun expressionForEmotion(e: com.amayra.maya.core.Emotion): String? = when (e) {
        com.amayra.maya.core.Emotion.HAPPY -> "happy"
        com.amayra.maya.core.Emotion.SAD -> "concerned"
        com.amayra.maya.core.Emotion.ANGRY -> "angry"
        com.amayra.maya.core.Emotion.SURPRISED -> "surprised"
        else -> null
    }
}
