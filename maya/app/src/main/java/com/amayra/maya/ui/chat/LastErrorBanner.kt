package com.amayra.maya.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amayra.maya.ai.ChatMessage
import com.amayra.maya.core.MayaCognitiveCore
import com.amayra.maya.core.StateBus
import com.amayra.maya.ui.theme.Accent
import com.amayra.maya.ui.theme.Danger
import com.amayra.maya.ui.theme.Warn

/**
 * Prominent, actionable banner for the most recent failed AI turn (quota
 * exhausted, bad key, no network…). Rendering lives in the chat UI instead of
 * a log line so "Maya isn't replying" is never a mystery.
 *
 * Shows only while the failure is the latest assistant outcome; any newer
 * successful reply (or a retry that works) hides it automatically.
 */
@Composable
fun LastErrorBanner(
    core: MayaCognitiveCore,
    onOpenAiSettings: () -> Unit = { StateBus.requestAiSettings() }
) {
    val turns by core.turnsState.collectAsState()
    val lastErrIdx = turns.indexOfLast { it.errorKind != null }
    if (lastErrIdx < 0) return
    // Stale failure: a later assistant turn succeeded — don't nag.
    if (turns.drop(lastErrIdx + 1).any { it.role == "assistant" && it.errorKind == null }) return
    val err = turns[lastErrIdx]

    val ui = errorUi(err)
    var busy by remember { mutableStateOf(false) }
    // A new failure (different turn instance) clears the in-flight retry guard.
    LaunchedEffect(err) { busy = false }

    Row(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFF241522), RoundedCornerShape(12.dp))
            .border(1.dp, ui.color.copy(alpha = 0.65f), RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(ui.emoji, fontSize = 14.sp)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(ui.title, color = ui.color, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text(
                ui.hint,
                color = Color(0xFFC9B8C4),
                fontSize = 10.sp,
                lineHeight = 13.sp
            )
        }
        Spacer(Modifier.width(8.dp))
        // Action: Retry (re-sends the last user message) for transient failures.
        if (ui.retryable) {
            Box(
                Modifier
                    .background(ui.color.copy(alpha = 0.18f), RoundedCornerShape(999.dp))
                    .border(1.dp, ui.color, RoundedCornerShape(999.dp))
                    .clickable(enabled = !busy) {
                        if (core.retryLastUser()) busy = true
                    }
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
                Text(
                    if (busy) "Retrying…" else "Retry",
                    color = ui.color, fontSize = 10.sp, fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(Modifier.width(6.dp))
        }
        // Action: jump to AI settings for config problems (key / quota / model).
        if (ui.fixInSettings) {
            Box(
                Modifier
                    .background(Color(0xFF00E5C7).copy(alpha = 0.15f), RoundedCornerShape(999.dp))
                    .border(1.dp, Accent.copy(alpha = 0.8f), RoundedCornerShape(999.dp))
                    .clickable { onOpenAiSettings() }
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
                Text("Fix ▸", color = Accent, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
    Spacer(Modifier.height(6.dp))
}

private data class ErrUi(
    val emoji: String,
    val title: String,
    val hint: String,
    val color: Color,
    val retryable: Boolean,
    val fixInSettings: Boolean
)

private fun errorUi(err: ChatMessage): ErrUi = when (err.errorKind) {
    "rate_limit" -> ErrUi(
        "⏳", "AI quota finished",
        "Free daily quota used up. New key lo, doosra provider chuno, ya reset ka wait karo.",
        Warn, retryable = true, fixInSettings = true
    )
    "auth" -> ErrUi(
        "🔑", "API key rejected",
        "Key invalid ya miss ho gayi — AI settings mein check karo.",
        Danger, retryable = false, fixInSettings = true
    )
    "network" -> ErrUi(
        "📡", "Network unreachable",
        "Internet connection check karo, phir retry.",
        Accent, retryable = true, fixInSettings = false
    )
    "timeout" -> ErrUi(
        "⏱", "AI took too long",
        "Provider slow tha — ek baar aur try karo.",
        Accent, retryable = true, fixInSettings = false
    )
    "unsupported" -> ErrUi(
        "🚫", "Model can't do that",
        "Yeh feature is model par nahi chalta — AI settings mein model badlo.",
        Warn, retryable = false, fixInSettings = true
    )
    else -> ErrUi(
        "⚠️", "AI provider error",
        err.content.removePrefix("⚠️ ").ifBlank { "Provider ne error diya." },
        Warn, retryable = true, fixInSettings = false
    )
}
