package com.amayra.maya.ui.diagnostics

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.amayra.maya.MayaApplication
import com.amayra.maya.core.AgentDebugBus
import com.amayra.maya.core.StateBus
import com.amayra.maya.perms.PermissionCenter
import com.amayra.maya.tools.RiskLevel
import com.amayra.maya.ui.theme.Accent
import com.amayra.maya.ui.theme.Bg
import com.amayra.maya.ui.theme.Danger
import com.amayra.maya.ui.theme.Ok
import com.amayra.maya.ui.theme.Surface
import com.amayra.maya.ui.theme.SurfaceHigh
import com.amayra.maya.ui.theme.TextDim
import com.amayra.maya.ui.theme.Warn
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Developer debug panel (blueprint §OBSERVABILITY): live agent state, current tool,
 * arguments, results, execution duration, errors, loaded tools, permission status.
 * Every value is read from the real execution paths (AgentDebugBus, StateBus,
 * ToolRegistry, PermissionCenter) — nothing mocked, no secrets (MayaLog.redact).
 */
@Composable
fun AgentDebugPanel(app: MayaApplication, onDismiss: () -> Unit) {
    val toolEvents by AgentDebugBus.toolEvents.collectAsState()
    val current by AgentDebugBus.currentTool.collectAsState()
    val turnEvents by AgentDebugBus.turnEvents.collectAsState()
    val state by StateBus.state.collectAsState()
    val prefs by app.settings.prefs.collectAsState(initial = null)
    if (prefs == null) return  // settings not hydrated yet

    var perms by remember { mutableStateOf<List<PermissionCenter.Perm>>(emptyList()) }
    LaunchedEffect(Unit) {
        perms = PermissionCenter.snapshot(app)
        // Re-probe once after a beat: state can settle right after opening (e.g. a
        // tool just finished enabling a capability).
        kotlinx.coroutines.delay(1500)
        perms = PermissionCenter.snapshot(app)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            color = Bg,
            shape = RoundedCornerShape(20.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Accent.copy(alpha = 0.35f)),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // ── Header ────────────────────────────────────────────────
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(10.dp)
                            .background(if (current != null) Ok else Accent, CircleShape)
                    )
                    Column(Modifier.weight(1f).padding(start = 8.dp)) {
                        Text("AGENT DEBUG", color = Accent, fontSize = 15.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                        Text(
                            "${prefs?.provider ?: "gemini"} · ${prefs?.model ?: "?"} · state: ${state.label}",
                            color = TextDim, fontSize = 11.sp, fontFamily = FontFamily.Monospace
                        )
                    }
                    Text("✕", color = TextDim, fontSize = 16.sp,
                        modifier = Modifier.clickable { onDismiss() }.padding(4.dp))
                }

                // ── Current tool ──────────────────────────────────────────
                Section("CURRENT TOOL")
                val c = current
                if (c == null) DimText("Idle — no tool running.")
                else ToolCard(c)

                // ── Turn log ──────────────────────────────────────────────
                Section("TURN LOG")
                if (turnEvents.isEmpty()) DimText("No turns yet.")
                else turnEvents.take(6).forEach { TurnRow(it) }

                // ── Tool executions ───────────────────────────────────────
                Section("TOOL EXECUTIONS — latest ${minOf(12, toolEvents.size)} of ${toolEvents.size}")
                if (toolEvents.isEmpty()) DimText("No tools ran yet this session.")
                else
                    Column(
                        Modifier
                            .heightIn(max = 320.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        toolEvents.take(12).forEach { ToolCard(it) }
                    }

                // ── Tools loaded ──────────────────────────────────────────
                Section("TOOLS LOADED (${app.registry.all().size})")
                RiskSummary(app.registry.all())

                // ── Permissions ───────────────────────────────────────────
                Section("PERMISSIONS")
                if (perms.isEmpty()) DimText("Probing…")
                else perms.forEach { PermRow(it) }

                // ── Screen context (live foreground, read-only) ──────────
                Section("SCREEN CONTEXT")
                val a11y = remember {
                    runCatching {
                        com.amayra.maya.accessibility.MayaAccessibilityService.instance
                            ?.roots()?.firstOrNull()?.packageName?.toString()
                    }.getOrNull()
                }
                DimText(
                    if (a11y != null) "Foreground package: $a11y"
                    else "Accessibility service not live — screen context unavailable."
                )

                // ── Footer ────────────────────────────────────────────────
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .background(Warn.copy(alpha = 0.10f), RoundedCornerShape(999.dp))
                            .border(1.dp, Warn.copy(alpha = 0.55f), RoundedCornerShape(999.dp))
                            .clickable { AgentDebugBus.clear() }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text("Clear log", color = Warn, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Box(Modifier.weight(1f))
                    Text("live · nothing mocked · secrets redacted", color = TextDim, fontSize = 10.sp)
                }
            }
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title, color = Accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
        fontFamily = FontFamily.Monospace, letterSpacing = 1.sp
    )
}

@Composable
private fun DimText(s: String) {
    Text(s, color = TextDim, fontSize = 12.sp)
}

private fun outcomeColor(outcome: String): Color = when (outcome) {
    "running" -> Warn
    "success" -> Ok
    "confirm" -> Accent
    else -> Danger
}

private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

@Composable
private fun ToolCard(e: AgentDebugBus.ToolEvent) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Surface, RoundedCornerShape(10.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(e.tool, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
            Text(e.outcome.uppercase(), color = outcomeColor(e.outcome), fontSize = 10.sp,
                fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        }
        if (e.argsJson.isNotBlank() && e.argsJson != "{}") {
            Text("args: ${e.argsJson}", color = TextDim, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        }
        if (e.summary.isNotBlank()) {
            Text(e.summary, color = TextDim, fontSize = 11.sp)
        }
        Row {
            Text(timeFmt.format(Date(e.epochMs)), color = TextDim, fontSize = 9.sp)
            if (e.durationMs > 0) {
                Text("  ·  ${e.durationMs} ms", color = TextDim, fontSize = 9.sp)
            }
        }
    }
}

@Composable
private fun TurnRow(e: AgentDebugBus.TurnEvent) {
    Row(verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().background(Surface, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 6.dp)) {
        Text(
            when (e.phase) {
                "start" -> "▶"
                "done" -> "✓"
                "error" -> "✗"
                else -> "■"
            },
            color = when (e.phase) { "done" -> Ok; "error" -> Danger; else -> Warn },
            fontSize = 11.sp
        )
        Column(Modifier.weight(1f).padding(start = 8.dp)) {
            Text(e.detail.ifBlank { e.phase }, color = Color.White, fontSize = 11.sp, maxLines = 2)
            Text(timeFmt.format(Date(e.epochMs)), color = TextDim, fontSize = 9.sp)
        }
        Text(e.phase.uppercase(), color = TextDim, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun RiskSummary(tools: List<com.amayra.maya.tools.Tool>) {
    val byRisk = tools.groupBy { it.risk }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        RiskLevel.entries.forEach { r ->
            val n = byRisk[r]?.size ?: 0
            Box(
                Modifier
                    .background(SurfaceHigh, RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text("$r: $n", color = if (n > 0) Accent else TextDim, fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
private fun PermRow(p: PermissionCenter.Perm) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            when (p.granted) { true -> "✅"; false -> "❌"; null -> "ℹ️" },
            fontSize = 12.sp
        )
        Column(Modifier.weight(1f).padding(start = 8.dp)) {
            Text(p.name, color = Color.White, fontSize = 12.sp)
            Text(
                if (p.granted == false) "Fix: ${p.howToFix}" else p.why,
                color = TextDim, fontSize = 10.sp
            )
        }
    }
}
