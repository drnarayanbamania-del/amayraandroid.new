package com.amayra.maya.avatar

import com.amayra.maya.core.AssistantState
import com.amayra.maya.core.Emotion
import com.amayra.maya.core.StateBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.min
import kotlin.math.sin

/**
 * Turns Maya's REAL state machine (StateBus.state + StateBus.emotion) into a
 * continuous visual scene for the avatar renderer. This is the single owner of
 * avatar animation timing — the renderer draws whatever this produces.
 *
 * Performance: one ~33ms frame loop ONLY while a composable is collecting the
 * scene (view is visible); a static frame is emitted when animation is disabled.
 */
class AvatarController(private val scope: CoroutineScope) {

    /** Latest computed scene. Frames are pushed here; UI collects with collectAsState. */
    val scene = kotlinx.coroutines.flow.MutableStateFlow(
        AvatarScene(AssistantState.Idle, Emotion.NEUTRAL, 0f, 0f, 0f, 0f, 0f, 0f)
    )

    /** Word-count based duration estimate for the current utterance (~160 wpm + tail). */
    @Volatile private var speakTotalSec: Float = 0f
    @Volatile private var speechClock: Float = 0f
    @Volatile private var speakStartMillis: Long = 0L

    /** Called by VoiceController when an utterance actually starts (TTS onStart). */
    fun onSpeechStart(text: String) {
        speakTotalSec = estimateDuration(text)
        speakStartMillis = System.currentTimeMillis()
        speechClock = 0f
    }

    /** Called by VoiceController when TTS completes/errors/is stopped. */
    fun onSpeechEnd() {
        speakTotalSec = 0f
        speechClock = 0f
    }

    /**
     * Drive the animation loop while [active] is collected by a visible composable.
     * Composable-visible lifetime == animation lifetime, so backgrounding the app
     * stops the loop automatically (lifecycle-safe, no battery drain).
     */
    fun start(active: kotlinx.coroutines.flow.StateFlow<Boolean>, prefs: com.amayra.maya.settings.SettingsRepository) {
        scope.launch {
            active.collect { isVisible ->
                if (!isVisible) return@collect
                while (isActive && active.value) {
                    val p = prefs.prefsCache
                    val st = StateBus.state.value
                    val speaking = st is AssistantState.Speaking
                    val t = if (p?.idleAnimEnabled == false && !speaking) 0f
                            else System.currentTimeMillis() / 1000f
                    if (speaking) {
                        speechClock = (System.currentTimeMillis() - speakStartMillis) / 1000f
                    }
                    val sceneNow = scene.value
                    scene.value = AvatarScene(
                        state = st,
                        emotion = StateBus.emotion.value,
                        timeSec = t,
                        speechSec = speechClock,
                        mouthOpen = if (p?.speakingAnimEnabled == false) 0f else mouthOpen(speaking, speechClock, sceneNow.mouthOpen),
                        speakProgress = if (speakTotalSec > 0f) speechClock / speakTotalSec else 0f,
                        attentionX = attention(t, st, sceneNow.attentionX, axis = 0),
                        attentionY = attention(t, st, sceneNow.attentionY, axis = 1)
                    )
                    delay(32) // ~30fps; cheap draw code comfortably reaches 60 when needed
                }
                // Frozen static frame while hidden — renderer draws a calm pose.
                scene.value = scene.value.copy(timeSec = 0f, mouthOpen = 0f)
            }
        }
    }

    /** Mouth envelope: opens on a ~7-8Hz syllabic rhythm with phrase pauses. */
    private fun mouthOpen(speaking: Boolean, speechSec: Float, previous: Float): Float {
        if (!speaking) return 0f
        val syllable = (sin(speechSec * 2f * Math.PI.toFloat() * 7.2f) * 0.5f + 0.5f)
        val phrase = 0.55f + 0.45f * sin(speechSec * 0.9f)
        val target = (syllable * phrase).coerceIn(0f, 1f)
        return previous + (target - previous) * 0.35f // smoothing for natural lips
    }

    /** Attention drift: idle sway + thinking scan + listening fixation. */
    private fun attention(t: Float, st: AssistantState, previous: Float, axis: Int): Float {
        val target = when (st) {
            is AssistantState.Processing, is AssistantState.ToolExecution ->
                sin(t * 0.7f + axis * 1.7f) * 0.8f
            is AssistantState.Listening -> 0f // fixate on the user
            else -> sin(t * 0.35f + axis * 2.3f) * 0.25f
        }
        return previous + (target - previous) * 0.06f
    }

    private fun estimateDuration(text: String): Float {
        val words = text.trim().split(Regex("\\s+")).count { it.isNotEmpty() }
        return min(90f, words / 160f * 60f + 1.2f) // ~160 wpm + tail
    }
}
