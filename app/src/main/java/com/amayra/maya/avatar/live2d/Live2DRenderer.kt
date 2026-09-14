package com.amayra.maya.avatar.live2d

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.amayra.maya.avatar.AvatarRenderer
import com.amayra.maya.avatar.AvatarScene as Scene
import com.amayra.maya.avatar.HolographicAvatarRenderer
import com.amayra.maya.core.MayaLog
import com.amayra.maya.settings.SettingsRepository

/**
 * The seam where the actual Live2D Cubism engine plugs in. An implementation loads
 * the validated model via [Live2DModelRepository] (assets), updates Cubism parameter
 * values from [Live2DController.Live2DFrameParams] each frame, and renders into the
 * Compose DrawScope (typically via a GL-backed texture shared with the Compose
 * canvas, or a wrapping AndroidView over GLSurfaceView).
 *
 * The Core so/DLL and JNI bindings are NOT redistributable via Maven — they come
 * from the license-gated Cubism SDK download. Until an engine module is added,
 * [Live2DAvatarRenderer] shows an honest placeholder and Maya's chat/voice keep
 * working; nothing here pretends the Cubism runtime is present.
 */
interface CubismEngine {
    /** Human-readable engine id, e.g. "cubism-native-5-r2 (JNI)". */
    val engineName: String

    /**
     * Load + initialize from the packaged assets. Must return false (never throw)
     * when the SDK runtime, model, textures or moc3 are unusable.
     */
    fun load(repository: Live2DModelRepository): Boolean

    /** Push one frame of parameter values + scene timing to the Cubism model. */
    fun update(params: Live2DController.Live2DFrameParams, scene: Scene, deltaSec: Float)

    /** Render the current model state. */
    fun render(scope: DrawScope)

    /** Release GPU/CPU resources. */
    fun release()
}

/** Registry for engine bindings discovered at app start (empty by default). */
object Live2DEngines {
    private val engines = mutableListOf<CubismEngine>()
    val available: List<CubismEngine> get() = engines.toList()
    fun register(e: CubismEngine) {
        engines += e
        MayaLog.i("L2D", "Cubism engine registered: ${e.engineName}")
    }
}

/**
 * AvatarRenderer implementation for the Live2D style. Renders through the first
 * registered [CubismEngine] when the packaged model is READY; otherwise draws a
 * clearly-labeled placeholder (reusing the holographic visuals) with the exact
 * status/reason, so the app stays beautiful and honest without a model.
 */
class Live2DAvatarRenderer(
    private val repository: Live2DModelRepository,
    private val live2d: Live2DController,
    private val settings: SettingsRepository
) : AvatarRenderer {

    override val styleId = "live2d"
    override val styleLabel = "Live2D (Cubism)"

    private val fallback = HolographicAvatarRenderer()
    private var engine: CubismEngine? = null
    private var engineTried = false
    private var lastFrameNanos: Long = 0L

    override fun draw(scope: DrawScope, scene: Scene) = with(scope) {
        val status = repository.status()
        when (status.state) {
            Live2DModelRepository.Live2DModelStatus.State.READY -> {
                val eng = ensureEngine()
                if (eng != null) {
                    val now = System.nanoTime()
                    val delta = if (lastFrameNanos == 0L) 0.016f
                        else ((now - lastFrameNanos) / 1_000_000_000f).coerceIn(0.001f, 0.1f)
                    lastFrameNanos = now
                    val params = live2d.frame(scene, now / 1_000_000_000.0)
                    eng.update(params, scene, delta)
                    eng.render(this)
                } else {
                    // Model is real and validated, but no Cubism engine binding is
                    // packaged. Show placeholder + precise reason (never a lie).
                    fallback.draw(this, scene)
                    drawStatusBadge("Live2D model ready — engine binding not packaged")
                }
            }
            else -> {
                fallback.draw(this, scene)
                drawStatusBadge(
                    if (status.state == Live2DModelRepository.Live2DModelStatus.State.NOT_PACKAGED)
                        "Live2D placeholder — no model packaged"
                    else "Live2D model invalid — see status"
                )
            }
        }
    }

    /** Tries engine init exactly once; result cached. Returns null when unavailable. */
    private fun ensureEngine(): CubismEngine? {
        if (engineTried) return engine
        engineTried = true
        val candidate = Live2DEngines.available.firstOrNull() ?: return null
        engine = if (runCatching { candidate.load(repository) }.getOrDefault(false)) {
            MayaLog.i("L2D", "Cubism engine loaded: ${candidate.engineName}")
            candidate
        } else {
            MayaLog.w("L2D", "Cubism engine ${candidate.engineName} failed to load the model")
            null
        }
        return engine
    }

    /** Small clearly-labeled badge so the placeholder is never mistaken for Live2D. */
    private fun DrawScope.drawStatusBadge(text: String) {
        val w = size.minDimension
        val badgeW = w * 0.62f
        val badgeH = w * 0.075f
        val left = (size.width - badgeW) / 2f
        val top = size.height * 0.94f - badgeH
        drawRoundRect(
            color = Color(0xE6121722),
            topLeft = androidx.compose.ui.geometry.Offset(left, top),
            size = androidx.compose.ui.geometry.Size(badgeW, badgeH),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(badgeH / 3f)
        )
        drawRoundRect(
            color = Color(0xFF00E5FF).copy(alpha = 0.55f),
            topLeft = androidx.compose.ui.geometry.Offset(left, top),
            size = androidx.compose.ui.geometry.Size(badgeW, badgeH),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(badgeH / 3f),
            style = androidx.compose.ui.graphics.drawscope.Stroke(1.5f)
        )
        // Bars encode "placeholder", real text status is shown in the UI layer.
        val bars = 5
        val barW = badgeW * 0.06f
        val gap = (badgeW - bars * barW) / (bars + 1)
        for (i in 0 until bars) {
            val h = badgeH * (0.3f + 0.4f * ((i % 3) + 1) / 3f)
            drawRect(
                color = Color(0xFF8A93A8),
                topLeft = androidx.compose.ui.geometry.Offset(left + gap + i * (barW + gap), top + (badgeH - h) / 2f),
                size = androidx.compose.ui.geometry.Size(barW, h)
            )
        }
        if (text.isNotBlank()) MayaLog.i("L2D", text)
    }
}
