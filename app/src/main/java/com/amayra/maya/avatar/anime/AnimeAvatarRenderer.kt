package com.amayra.maya.avatar.anime

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import com.amayra.maya.avatar.AvatarRenderer
import com.amayra.maya.avatar.AvatarScene
import com.amayra.maya.core.Emotion
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Professional android-companion renderer: an anime figure with a robotic
 * holographic stage — animated particle field, scanline sweep, glowing circuit
 * seams on the outfit, energy core in the chest, and a hex-grid floor. Outfit
 * (dress-up) follows the current [Emotion]/persona mood automatically.
 */
class AnimeAvatarRenderer : AvatarRenderer {

    override val styleId = "anime_2d"
    override val styleLabel = "Android Companion"

    private var nextBlinkAt = 2.0f
    private var blinkPhase = -1.0f
    private var lastTime = 0f

    override fun draw(scope: DrawScope, scene: AvatarScene) = with(scope) {
        val dt = (scene.timeSec - lastTime).let { if (it in 0.001f..0.5f) it else 0.016f }
        lastTime = scene.timeSec
        if (blinkPhase < 0f && scene.timeSec >= nextBlinkAt) blinkPhase = 0f
        if (blinkPhase >= 0f) {
            blinkPhase += dt / BLINK_SEC
            if (blinkPhase >= 1f) {
                blinkPhase = -1f
                nextBlinkAt = scene.timeSec + 1.5f + (scene.timeSec * 7.13f % 3f)
            }
        }
        val blink = if (blinkPhase < 0f) 0f
            else (1f - abs(2f * blinkPhase - 1f)).coerceIn(0f, 1f)

        val mood = moodOf(scene.emotion)
        val cx = size.width / 2f
        val s = minOf(size.width, size.height) * 0.94f
        val topY = (size.height - s) / 2f

        drawBackground(scene, mood, cx, s)

        val bob = sin(scene.timeSec * 2f * PI.toFloat() / 3.4f) * s * 0.012f
        val leanX = scene.attentionX * s * 0.03f
        // drawCharacter's local origin is the unit-box's TOP-LEFT (it draws at
        // s*0.5 internally), so translate by the box's top-left — not the center.
        translate(cx - s / 2f + leanX, topY + bob + scene.attentionY * s * 0.02f) {
            drawCharacter(s, scene, blink, mood)
        }

        drawHoloFloor(scene, mood, cx, topY + s, s)
    }

    // ── Animated background: particles + scanline + mood vignette ─────────

    private fun DrawScope.drawBackground(scene: AvatarScene, mood: Mood, cx: Float, s: Float) {
        val t = scene.timeSec
        // Deep space vignette with mood tint.
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(mood.glow.copy(alpha = 0.10f), Color(0xFF07090F)),
                center = Offset(cx, size.height * 0.45f),
                radius = size.maxDimension * 0.75f
            )
        )

        // Floating particles: three depth layers, deterministic orbits.
        repeat(PARTICLES) { i ->
            val seed = i * 12.9898f
            val speed = 0.15f + (i % 5) * 0.06f
            val a = t * speed + seed
            val px = (sin(a) * 0.5f + 0.5f) * size.width
            val py = ((cos(a * 0.7f + seed) + 1f) / 2f) * size.height * 0.85f
            val depth = 0.4f + (i % 3) * 0.3f
            drawCircle(
                mood.accent.copy(alpha = 0.25f + 0.35f * depth * (0.6f + 0.4f * sin(a * 2.3f))),
                s * 0.004f * depth,
                Offset(px, py)
            )
        }

        // Scanline sweep — thin bright bar travelling down, robotic feel.
        val sweepY = ((t * 0.35f) % 1.2f) * size.height - size.height * 0.1f
        drawRect(
            brush = Brush.verticalGradient(
                listOf(Color.Transparent, mood.accent.copy(alpha = 0.16f), Color.Transparent),
                startY = sweepY - s * 0.06f, endY = sweepY + s * 0.06f
            ),
            topLeft = Offset(0f, sweepY - s * 0.06f),
            size = Size(size.width, s * 0.12f)
        )
    }

    // ── Holographic hex-grid floor ─────────────────────────────────────────

    private fun DrawScope.drawHoloFloor(scene: AvatarScene, mood: Mood, cx: Float, feetY: Float, s: Float) {
        val t = scene.timeSec
        val rx = s * 0.36f
        // Pulsing base ellipse.
        val pulse = 0.5f + 0.5f * sin(t * 2.2f)
        drawOval(
            mood.accent.copy(alpha = 0.10f + 0.08f * pulse),
            topLeft = Offset(cx - rx, feetY - s * 0.045f - s * 0.10f),
            size = Size(rx * 2f, s * 0.20f)
        )
        drawOval(
            brush = Brush.horizontalGradient(listOf(mood.accent.copy(alpha = 0.05f), mood.accent.copy(alpha = 0.7f), mood.accent.copy(alpha = 0.05f))),
            topLeft = Offset(cx - rx, feetY - s * 0.05f - s * 0.10f),
            size = Size(rx * 2f, s * 0.10f),
            style = Stroke(s * 0.012f)
        )
        // Concentric energy rings expanding outward (ripple from the figure).
        repeat(2) { k ->
            val prog = ((t * 0.5f + k * 0.5f) % 1f)
            val rrx = rx * (0.3f + 0.9f * prog)
            drawOval(
                mood.accent.copy(alpha = 0.35f * (1f - prog)),
                topLeft = Offset(cx - rrx, feetY - s * 0.05f * (rrx / rx) - s * 0.10f + (1f - prog) * s * 0.02f),
                size = Size(rrx * 2f, s * 0.10f * (rrx / rx).coerceAtLeast(0.4f)),
                style = Stroke(1.5f)
            )
        }
        // Fine grid ticks on the floor.
        repeat(7) { i ->
            val gx = cx - rx + (i + 0.5f) * (rx * 2f / 7f)
            drawLine(
                mood.accent.copy(alpha = 0.20f),
                Offset(gx, feetY - s * 0.095f),
                Offset(gx, feetY - s * 0.070f),
                1.5f
            )
        }
    }

    // ── The character ──────────────────────────────────────────────────────

    private fun DrawScope.drawCharacter(s: Float, scene: AvatarScene, blink: Float, mood: Mood) {
        val cx = s * 0.5f
        val t = scene.timeSec

        // ── Outfit: sleek bodysuit with glowing seams ─────────────────────
        val bodyTop = s * 0.52f
        val body = Path().apply {
            moveTo(cx - s * 0.13f, bodyTop)
            cubicTo(cx - s * 0.20f, s * 0.66f, cx - s * 0.26f, s * 0.74f, cx - s * 0.22f, s * 0.82f)
            lineTo(cx + s * 0.22f, s * 0.82f)
            cubicTo(cx + s * 0.26f, s * 0.74f, cx + s * 0.20f, s * 0.66f, cx + s * 0.13f, bodyTop)
            close()
        }
        drawPath(body, Brush.verticalGradient(listOf(mood.dress, mood.dress.darken(0.45f)), startY = bodyTop, endY = s * 0.82f))
        drawPath(body, mood.accent.copy(alpha = 0.55f), style = Stroke(s * 0.006f))
        // Circuit seams: two glowing vertical traces with travelling light dots.
        for (side in listOf(-1f, 1f)) {
            val tx = cx + side * s * 0.07f
            drawLine(mood.accent.copy(alpha = 0.7f), Offset(tx, bodyTop + s * 0.06f), Offset(tx + side * s * 0.03f, s * 0.80f), s * 0.006f)
            val prog = (t * 0.6f) % 1f
            val py = bodyTop + s * 0.06f + prog * (s * 0.74f - s * 0.06f)
            val px = tx + side * s * 0.03f * ((py - bodyTop) / (s * 0.74f))
            drawCircle(Color.White.copy(alpha = 0.9f), s * 0.007f, Offset(px, py))
            drawCircle(mood.accent.copy(alpha = 0.5f), s * 0.014f, Offset(px, py))
        }

        // ── Energy core in the chest (beats, reacts to speaking) ──────────
        val corePulse = 0.7f + 0.3f * sin(t * 3.4f) + scene.mouthOpen * 0.4f
        drawCircle(mood.accent.copy(alpha = 0.25f * corePulse), s * 0.035f, Offset(cx, s * 0.60f))
        drawCircle(mood.glow.copy(alpha = 0.9f), s * 0.016f, Offset(cx, s * 0.60f))
        drawCircle(Color.White, s * 0.007f, Offset(cx, s * 0.60f))

        // Legs with glowing boot rims.
        drawLine(mood.skin.darken(0.1f), Offset(cx - s * 0.07f, s * 0.82f), Offset(cx - s * 0.07f, s * 0.97f), s * 0.035f)
        drawLine(mood.skin.darken(0.1f), Offset(cx + s * 0.07f, s * 0.82f), Offset(cx + s * 0.07f, s * 0.97f), s * 0.035f)
        drawLine(mood.accent, Offset(cx - s * 0.10f, s * 0.965f), Offset(cx - s * 0.04f, s * 0.965f), s * 0.012f)
        drawLine(mood.accent, Offset(cx + s * 0.04f, s * 0.965f), Offset(cx + s * 0.10f, s * 0.965f), s * 0.012f)

        // Arms — mechanical sleeves + gentle sway.
        val sway = sin(t * 1.1f) * s * 0.02f
        drawLine(mood.dress.darken(0.15f), Offset(cx - s * 0.14f, bodyTop + s * 0.02f), Offset(cx - s * 0.17f, bodyTop + s * 0.12f), s * 0.042f)
        drawLine(mood.dress.darken(0.15f), Offset(cx + s * 0.14f, bodyTop + s * 0.02f), Offset(cx + s * 0.17f, bodyTop + s * 0.12f), s * 0.042f)
        drawLine(mood.skin, Offset(cx - s * 0.17f, bodyTop + s * 0.12f), Offset(cx - s * 0.20f, bodyTop + s * 0.22f + sway), s * 0.030f)
        drawLine(mood.skin, Offset(cx + s * 0.17f, bodyTop + s * 0.12f), Offset(cx + s * 0.20f, bodyTop + s * 0.22f - sway), s * 0.030f)
        // Joint glow at elbows.
        drawCircle(mood.accent.copy(alpha = 0.8f), s * 0.008f, Offset(cx - s * 0.17f, bodyTop + s * 0.12f))
        drawCircle(mood.accent.copy(alpha = 0.8f), s * 0.008f, Offset(cx + s * 0.17f, bodyTop + s * 0.12f))

        // ── Head ──────────────────────────────────────────────────────────
        val headCx = cx
        val headCy = s * 0.30f
        val headR = s * 0.17f
        drawOval(mood.skin, topLeft = Offset(headCx - headR * 0.92f, headCy - headR), size = Size(headR * 1.84f, headR * 2f))

        // Hair: back mass + bangs + side strands.
        drawOval(
            Brush.verticalGradient(listOf(mood.hair, mood.hair.darken(0.3f))),
            topLeft = Offset(headCx - headR * 1.08f, headCy - headR * 1.18f),
            size = Size(headR * 2.16f, headR * 2.2f)
        )
        drawOval(mood.skin, topLeft = Offset(headCx - headR * 0.70f, headCy - headR * 0.72f), size = Size(headR * 1.40f, headR * 1.72f))
        val bang = Path().apply {
            moveTo(headCx - headR * 0.95f, headCy - headR * 0.45f)
            for (i in 0..3) {
                val x0 = headCx - headR * 0.95f + i * headR * 0.475f
                cubicTo(x0 + headR * 0.1f, headCy - headR * 0.95f,
                    x0 + headR * 0.38f, headCy - headR * 0.95f,
                    x0 + headR * 0.475f, headCy - headR * 0.45f)
            }
            lineTo(headCx + headR * 0.95f, headCy - headR * 0.9f)
            lineTo(headCx - headR * 0.95f, headCy - headR * 0.9f)
            close()
        }
        drawPath(bang, mood.hair)
        drawLine(mood.hair, Offset(headCx - headR * 1.02f, headCy - headR * 0.6f), Offset(headCx - headR * 1.12f, headCy + headR * 1.5f), s * 0.028f)
        drawLine(mood.hair, Offset(headCx + headR * 1.02f, headCy - headR * 0.6f), Offset(headCx + headR * 1.12f, headCy + headR * 1.5f), s * 0.028f)

        // ── Robotic halo: rotating arc ring above the head ─────────────────
        val haloY = headCy - headR * 1.45f
        val haloR = headR * 0.85f
        repeat(3) { k ->
            val start = t * 1.2f + k * (2f * PI.toFloat() / 3f)
            drawArc(
                mood.accent.copy(alpha = 0.85f),
                startAngle = start * 180f / PI.toFloat(),
                sweepAngle = 42f,
                useCenter = false,
                topLeft = Offset(headCx - haloR, haloY - haloR * 0.28f),
                size = Size(haloR * 2f, haloR * 0.56f),
                style = Stroke(s * 0.008f)
            )
        }

        // ── Eyes with tech-highlight ──────────────────────────────────────
        val eyeY = headCy + headR * 0.12f
        val eyeDx = headR * 0.42f
        val gazeX = scene.attentionX * headR * 0.10f
        val gazeY = scene.attentionY * headR * 0.07f
        val openH = headR * 0.34f * (1f - blink)
        for (side in listOf(-1f, 1f)) {
            val ex = headCx + side * eyeDx
            drawOval(Color(0xFFF4F7FF), Offset(ex - headR * 0.20f, eyeY - openH / 2f), Size(headR * 0.40f, openH))
            if (openH > headR * 0.05f) {
                drawOval(mood.iris, Offset(ex - headR * 0.14f + gazeX, eyeY - openH * 0.42f + gazeY), Size(headR * 0.28f, openH * 0.84f))
                drawOval(Color(0xFF14121C), Offset(ex - headR * 0.07f + gazeX, eyeY - openH * 0.26f + gazeY), Size(headR * 0.14f, openH * 0.5f))
                drawCircle(Color.White.copy(alpha = 0.9f), headR * 0.05f, Offset(ex - headR * 0.06f + gazeX, eyeY - openH * 0.28f + gazeY))
                // Tiny HUD glint in the iris.
                drawCircle(mood.accent.copy(alpha = 0.9f), headR * 0.02f, Offset(ex + headR * 0.05f + gazeX, eyeY + openH * 0.10f + gazeY))
            } else {
                drawLine(Color(0xFF2A2233), Offset(ex - headR * 0.18f, eyeY), Offset(ex + headR * 0.18f, eyeY), s * 0.012f)
            }
            drawLine(Color(0xFF2A2233), Offset(ex - headR * 0.20f, eyeY - openH * 0.55f), Offset(ex + headR * 0.20f, eyeY - openH * 0.55f), s * 0.014f)
        }

        // Blush when happy.
        if (mood.blush > 0f) {
            drawCircle(mood.blushColor.copy(alpha = 0.5f * mood.blush), headR * 0.14f, Offset(headCx - headR * 0.62f, eyeY + headR * 0.30f))
            drawCircle(mood.blushColor.copy(alpha = 0.5f * mood.blush), headR * 0.14f, Offset(headCx + headR * 0.62f, eyeY + headR * 0.30f))
        }

        // ── Mouth ─────────────────────────────────────────────────────────
        val mY = headCy + headR * 0.62f
        val open = scene.mouthOpen
        if (open > 0.04f) {
            drawOval(Color(0xFF8C3A4A), Offset(headCx - headR * 0.13f, mY - headR * 0.16f * open), Size(headR * 0.26f, headR * 0.32f * (0.3f + open)))
        } else {
            drawLine(Color(0xFF8C3A4A), Offset(headCx - headR * 0.10f, mY), Offset(headCx + headR * 0.10f, mY), s * 0.010f)
        }

        // Side audio-emitter sparks on the dress shoulders while speaking.
        if (scene.state is com.amayra.maya.core.AssistantState.Speaking) {
            repeat(3) { i ->
                val a = t * 6f + i * 2.1f
                val px = cx + sin(a) * s * 0.20f
                val py = bodyTop - s * 0.02f + sin(a * 1.7f) * s * 0.02f
                drawCircle(mood.accent.copy(alpha = 0.6f), s * 0.006f, Offset(px, py))
            }
        }
    }

    private fun moodOf(e: Emotion) = when (e) {
        Emotion.HAPPY -> Mood(
            hair = Color(0xFFFFD34D), iris = Color(0xFF37B6A9), dress = Color(0xFF3B2A55),
            accent = Color(0xFFFF5FA2), glow = Color(0xFFFF5FA2), blush = 1f
        )
        Emotion.ANGRY -> Mood(
            hair = Color(0xFFE4485B), iris = Color(0xFFFFC93C), dress = Color(0xFF41121E),
            accent = Color(0xFFFF3B5C), glow = Color(0xFFFF3B5C), blush = 0f
        )
        Emotion.SAD -> Mood(
            hair = Color(0xFF9DB8E8), iris = Color(0xFF5A7DD6), dress = Color(0xFF22304C),
            accent = Color(0xFF6FD3FF), glow = Color(0xFF6FD3FF), blush = 0.3f
        )
        Emotion.SURPRISED -> Mood(
            hair = Color(0xFFFFD34D), iris = Color(0xFFFFC93C), dress = Color(0xFF2E2344),
            accent = Color(0xFFFFD166), glow = Color(0xFFFFD166), blush = 0.4f
        )
        Emotion.SLEEPY -> Mood(
            hair = Color(0xFFC3A6E8), iris = Color(0xFF7C5CB8), dress = Color(0xFF2B2440),
            accent = Color(0xFF9D8CFF), glow = Color(0xFF9D8CFF), blush = 0.3f
        )
        else -> Mood(
            hair = Color(0xFFFFD34D), iris = Color(0xFF37B6A9), dress = Color(0xFF2E2344),
            accent = Color(0xFFB46BFF), glow = Color(0xFFB46BFF), blush = 0.5f
        )
    }

    private data class Mood(
        val hair: Color, val iris: Color, val dress: Color, val accent: Color,
        val glow: Color, val blush: Float, val blushColor: Color = Color(0xFFFF8FA8),
        val skin: Color = Color(0xFFFFE7D4)
    )

    private fun Color.darken(f: Float) = Color(
        (red * (1f - f)).coerceIn(0f, 1f),
        (green * (1f - f)).coerceIn(0f, 1f),
        (blue * (1f - f)).coerceIn(0f, 1f),
        alpha
    )

    private companion object {
        const val BLINK_SEC = 0.22f
        const val PARTICLES = 26
    }
}
