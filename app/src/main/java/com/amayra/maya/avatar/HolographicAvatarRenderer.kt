package com.amayra.maya.avatar

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.amayra.maya.core.AssistantState
import com.amayra.maya.core.Emotion
import kotlin.math.abs
import kotlin.math.sin

/**
 * JARVIS-style holographic 2D renderer: female AI head + shoulders with emissive
 * cyan/blue aesthetic, halo rings, per-state glow, blinking, emotion-driven brows,
 * and a mouth envelope synced to the [AvatarScene] speech clock.
 * Pure draw code — all timing/state lives in [AvatarController].
 */
class HolographicAvatarRenderer : AvatarRenderer {

    override val styleId = "holographic_2d"
    override val styleLabel = "Holographic 2D"

    // Palette (matches Maya dark UI)
    private val core = Color(0xFF00E5FF)
    private val coreDeep = Color(0xFF0A9CC8)
    private val faceFill = Color(0xFF0B2233)
    private val hair = Color(0xFF17607F)
    private val bright = Color(0xFFBDF4FF)
    private val errColor = Color(0xFFFF5470)

    override fun draw(scope: DrawScope, scene: AvatarScene) = with(scope) {
        val s = size.minDimension
        val t = scene.timeSec
        val err = scene.state is AssistantState.Error
        val main = if (err) errColor else core

        val bob = sin(t * 0.9f) * s * 0.006f // breath bob
        val breath = 1f + sin(t * 0.9f) * 0.012f
        val cx = size.width / 2f
        val cy = size.height * 0.46f + bob
        val rx = s * 0.185f * breath
        val ry = s * 0.24f * breath

        drawAura(cx, cy, rx, ry, scene, main, err)
        drawHaloRings(cx, cy, rx * 2.6f, scene, main)
        drawBody(cx, cy, s, main, err)
        drawHairBack(cx, cy, rx, ry, main)
        drawFace(cx, cy, rx, ry, main, err)
        drawHairFront(cx, cy, rx, ry, main)
        drawEyes(cx, cy, rx, ry, scene, err)
        drawBrows(cx, cy, rx, ry, scene)
        drawNose(cx, cy, rx, ry, main)
        drawLips(cx, cy, rx, ry, scene)
        drawStateExtras(cx, cy, rx, ry, scene, main)
    }

    // ── layers ───────────────────────────────────────────────────────────

    private fun DrawScope.drawAura(
        cx: Float, cy: Float, rx: Float, ry: Float,
        scene: AvatarScene, main: Color, err: Boolean
    ) {
        val pulse = when (scene.state) {
            is AssistantState.Listening -> 0.5f + 0.5f * sin(scene.timeSec * 5f)
            is AssistantState.Processing, is AssistantState.ToolExecution -> 0.4f + 0.3f * sin(scene.timeSec * 9f)
            is AssistantState.Speaking -> 0.45f + 0.5f * scene.mouthOpen
            is AssistantState.Error -> 0.55f + 0.45f * sin(scene.timeSec * 12f)
            else -> 0.22f + 0.1f * sin(scene.timeSec * 1.4f)
        }
        val auraR = maxOf(rx, ry) * (2.1f + 0.22f * pulse)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    main.copy(alpha = if (err) 0.34f else 0.26f * pulse + 0.10f),
                    main.copy(alpha = 0.05f),
                    Color.Transparent
                ),
                center = Offset(cx, cy),
                radius = auraR
            ),
            radius = auraR,
            center = Offset(cx, cy)
        )
        // Speaking emphasis ring tracks mouth openness.
        if (scene.state is AssistantState.Speaking) {
            drawCircle(
                color = main.copy(alpha = 0.5f),
                radius = maxOf(rx, ry) * (1.28f + 0.14f * scene.mouthOpen),
                center = Offset(cx, cy),
                style = Stroke(width = 2.5f + 3f * scene.mouthOpen)
            )
        }
    }

    private fun DrawScope.drawHaloRings(cx: Float, cy: Float, r: Float, scene: AvatarScene, main: Color) {
        val active = scene.state !is AssistantState.Sleeping
        val a1 = if (active) scene.timeSec * 42f else 0f
        val a2 = if (active) -scene.timeSec * 27f else 0f
        val alpha = when (scene.state) {
            is AssistantState.Sleeping -> 0.10f
            is AssistantState.Error -> 0.30f
            else -> 0.38f
        }
        val top = Offset(cx - r, cy - r * 0.36f)
        val sz = Size(r * 2f, r * 0.72f)
        drawArc(main.copy(alpha = alpha), a1, 118f, false, topLeft = top, size = sz, style = Stroke(2.2f, cap = StrokeCap.Round))
        drawArc(main.copy(alpha = alpha * 0.8f), a2, 232f, false, topLeft = top, size = sz, style = Stroke(1.4f))
        drawArc(main.copy(alpha = alpha * 0.5f), a1 + 180f, 62f, false, topLeft = top, size = sz, style = Stroke(3.4f, cap = StrokeCap.Round))
    }

    private fun DrawScope.drawBody(cx: Float, cy: Float, s: Float, main: Color, err: Boolean) {
        val neckW = s * 0.075f
        val neckH = s * 0.075f
        // Neck
        drawRoundRect(
            color = faceFill,
            topLeft = Offset(cx - neckW / 2f, cy + s * 0.075f),
            size = Size(neckW, neckH),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(neckW * 0.3f)
        )
        // Shoulders (upper half ellipse)
        val shRx = s * 0.33f
        val shRy = s * 0.185f
        val shCy = cy + s * 0.155f
        drawArc(
            color = if (err) Color(0xFF2A1220) else Color(0xFF0D1B2C),
            startAngle = 180f, sweepAngle = 180f, useCenter = true,
            topLeft = Offset(cx - shRx, shCy - shRy * 0.35f),
            size = Size(shRx * 2f, shRy * 2f)
        )
        drawArc(
            color = main.copy(alpha = 0.45f),
            startAngle = 180f, sweepAngle = 180f, useCenter = false,
            topLeft = Offset(cx - shRx, shCy - shRy * 0.35f),
            size = Size(shRx * 2f, shRy * 2f),
            style = Stroke(2.6f, cap = StrokeCap.Round)
        )
        // Chest core light
        drawCircle(main.copy(alpha = 0.65f), s * 0.016f, Offset(cx, shCy + shRy * 0.18f))
        drawCircle(main.copy(alpha = 0.16f), s * 0.045f, Offset(cx, shCy + shRy * 0.18f))
    }

    private fun DrawScope.drawHairBack(cx: Float, cy: Float, rx: Float, ry: Float, main: Color) {
        // Long back hair silhouette
        drawOval(
            brush = Brush.verticalGradient(
                colors = listOf(hair.copy(alpha = 0.95f), hair.copy(alpha = 0.25f)),
                startY = cy - ry, endY = cy + ry * 1.9f
            ),
            topLeft = Offset(cx - rx * 1.28f, cy - ry * 1.06f),
            size = Size(rx * 2.56f, ry * 2.95f)
        )
    }

    private fun DrawScope.drawFace(cx: Float, cy: Float, rx: Float, ry: Float, main: Color, err: Boolean) {
        drawOval(
            brush = Brush.verticalGradient(
                colors = listOf(
                    (if (err) Color(0xFF2B1220) else Color(0xFF0E2A40)),
                    faceFill
                ),
                startY = cy - ry, endY = cy + ry
            ),
            topLeft = Offset(cx - rx, cy - ry),
            size = Size(rx * 2f, ry * 2f)
        )
        drawOval(
            main.copy(alpha = 0.85f),
            topLeft = Offset(cx - rx, cy - ry),
            size = Size(rx * 2f, ry * 2f),
            style = Stroke(2.4f)
        )
        // Cheek highlights
        drawCircle(main.copy(alpha = 0.10f), rx * 0.20f, Offset(cx - rx * 0.62f, cy + ry * 0.12f))
        drawCircle(main.copy(alpha = 0.10f), rx * 0.20f, Offset(cx + rx * 0.62f, cy + ry * 0.12f))
    }

    private fun DrawScope.drawHairFront(cx: Float, cy: Float, rx: Float, ry: Float, main: Color) {
        // Fringe sweeping across the forehead
        drawArc(
            brush = Brush.linearGradient(listOf(hair, hair.copy(alpha = 0.55f))),
            startAngle = 188f, sweepAngle = 164f, useCenter = true,
            topLeft = Offset(cx - rx * 1.04f, cy - ry * 1.05f),
            size = Size(rx * 2.08f, ry * 1.6f)
        )
        // Side locks
        drawArc(
            hair.copy(alpha = 0.9f), 95f, 55f, true,
            topLeft = Offset(cx + rx * 0.72f, cy - ry * 0.35f),
            size = Size(rx * 0.62f, ry * 1.7f)
        )
        drawArc(
            hair.copy(alpha = 0.9f), 30f, 55f, true,
            topLeft = Offset(cx - rx * 1.34f, cy - ry * 0.35f),
            size = Size(rx * 0.62f, ry * 1.7f)
        )
    }

    private fun blinkClosed(t: Float): Float {
        val period = 4.3f
        val ph = t % period
        val dur = 0.14f
        return if (ph < dur) 1f - abs(ph / dur * 2f - 1f) else 0f
    }

    private fun DrawScope.drawEyes(cx: Float, cy: Float, rx: Float, ry: Float, scene: AvatarScene, err: Boolean) {
        val eyeY = cy - ry * 0.06f
        val dx = rx * 0.42f
        val eyeW = rx * 0.30f
        val eyeH = rx * 0.19f
        val closedByEmotion = scene.state is AssistantState.Sleeping ||
            (scene.state is AssistantState.Idle && scene.emotion == Emotion.SLEEPY)
        val blink = blinkClosed(scene.timeSec)
        val openK = if (closedByEmotion) 0.04f else 1f - blink * 0.96f

        val brightCol = if (err) Color(0xFFFFC2CE) else bright
        val pupil = if (err) errColor else core

        for (side in listOf(-1f, 1f)) {
            val ex = cx + side * dx
            // Sclera
            drawOval(
                brightCol.copy(alpha = 0.92f),
                topLeft = Offset(ex - eyeW / 2f, eyeY - eyeH / 2f * openK - eyeH * 0.001f),
                size = Size(eyeW, maxOf(eyeH * openK, eyeH * 0.06f))
            )
            if (openK > 0.25f) {
                val px = ex + scene.attentionX * eyeW * 0.22f
                val py = eyeY + scene.attentionY * eyeH * 0.5f
                // Iris + glow pupil
                drawOval(
                    pupil.copy(alpha = 0.95f),
                    topLeft = Offset(px - eyeW * 0.16f, py - eyeH * 0.34f),
                    size = Size(eyeW * 0.32f, eyeH * 0.68f)
                )
                drawCircle(Color.White.copy(alpha = 0.85f), eyeW * 0.045f, Offset(px - eyeW * 0.05f, py - eyeH * 0.12f))
            } else {
                // Closed lash line
                drawLine(
                    brightCol.copy(alpha = 0.8f),
                    Offset(ex - eyeW / 2f, eyeY), Offset(ex + eyeW / 2f, eyeY),
                    strokeWidth = 2.4f, cap = StrokeCap.Round
                )
            }
        }
    }

    private fun DrawScope.drawBrows(cx: Float, cy: Float, rx: Float, ry: Float, scene: AvatarScene) {
        var lift = 0f
        var tilt = 0f
        when (scene.emotion) {
            Emotion.HAPPY -> lift = -rx * 0.05f
            Emotion.SAD -> { lift = rx * 0.01f; tilt = rx * 0.05f }
            Emotion.ANGRY -> tilt = -rx * 0.06f
            Emotion.SURPRISED -> lift = -rx * 0.08f
            else -> {}
        }
        val by = cy - ry * 0.24f + lift
        val dx = rx * 0.42f
        val w = rx * 0.30f
        for (side in listOf(-1f, 1f)) {
            val bx = cx + side * dx
            val innerDrop = tilt * side
            drawLine(
                Color(0xFF9FDCEB).copy(alpha = 0.9f),
                Offset(bx - w / 2f, by + innerDrop),
                Offset(bx + w / 2f, by - innerDrop),
                strokeWidth = 2.6f, cap = StrokeCap.Round
            )
        }
    }

    private fun DrawScope.drawNose(cx: Float, cy: Float, rx: Float, ry: Float, main: Color) {
        drawLine(
            main.copy(alpha = 0.5f),
            Offset(cx, cy + ry * 0.02f),
            Offset(cx + rx * 0.03f, cy + ry * 0.10f),
            strokeWidth = 2f, cap = StrokeCap.Round
        )
    }

    private fun DrawScope.drawLips(cx: Float, cy: Float, rx: Float, ry: Float, scene: AvatarScene) {
        val mouthY = cy + ry * 0.42f
        val w = rx * 0.34f
        val open = scene.mouthOpen * rx * 0.16f
        val smile = when (scene.emotion) {
            Emotion.HAPPY -> ry * 0.05f
            Emotion.SAD -> -ry * 0.045f
            else -> 0f
        }
        if (open > rx * 0.012f) {
            // Open mouth: dark interior + lip glow
            drawOval(
                Color(0xFF071018),
                topLeft = Offset(cx - w / 2f, mouthY - open),
                size = Size(w, open * 2f)
            )
            drawOval(
                core.copy(alpha = 0.75f),
                topLeft = Offset(cx - w / 2f, mouthY - open),
                size = Size(w, open * 2f),
                style = Stroke(1.8f)
            )
        } else {
            // Closed: gentle bow curve
            drawArc(
                bright.copy(alpha = 0.85f),
                if (smile >= 0f) 200f else 160f, 140f, false,
                topLeft = Offset(cx - w / 2f, mouthY - w * 0.22f - smile),
                size = Size(w, w * 0.44f),
                style = Stroke(2.6f, cap = StrokeCap.Round)
            )
        }
    }

    private fun DrawScope.drawStateExtras(cx: Float, cy: Float, rx: Float, ry: Float, scene: AvatarScene, main: Color) {
        when (scene.state) {
            is AssistantState.Listening -> drawMicBadge(cx + rx * 1.12f, cy + ry * 0.62f, rx, scene, main)
            is AssistantState.Processing, is AssistantState.ToolExecution -> {
                // Scanline sweep over the face
                val prog = (scene.timeSec * 0.55f) % 1f
                val y = cy - ry + prog * ry * 2f
                drawLine(
                    main.copy(alpha = 0.45f),
                    Offset(cx - rx * 0.86f, y), Offset(cx + rx * 0.86f, y),
                    strokeWidth = 2f
                )
            }
            else -> {}
        }
    }

    private fun DrawScope.drawMicBadge(bx: Float, by: Float, rx: Float, scene: AvatarScene, main: Color) {
        val r = rx * 0.30f
        val pulse = 0.5f + 0.5f * sin(scene.timeSec * 5.5f)
        drawCircle(main.copy(alpha = 0.18f + 0.2f * pulse), r * 1.5f, Offset(bx, by))
        drawCircle(Color(0xFF0B2233), r, Offset(bx, by))
        drawCircle(main.copy(alpha = 0.9f), r, Offset(bx, by), style = Stroke(2f))
        // Mic capsule
        val capW = r * 0.42f
        val capH = r * 0.72f
        drawRoundRect(
            color = bright,
            topLeft = Offset(bx - capW / 2f, by - capH * 0.72f),
            size = Size(capW, capH),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(capW / 2f)
        )
        // Stand
        drawLine(bright, Offset(bx, by + r * 0.1f), Offset(bx, by + r * 0.42f), 2.2f)
        drawLine(bright, Offset(bx - r * 0.28f, by + r * 0.42f), Offset(bx + r * 0.28f, by + r * 0.42f), 2.2f, StrokeCap.Round)
    }
}
