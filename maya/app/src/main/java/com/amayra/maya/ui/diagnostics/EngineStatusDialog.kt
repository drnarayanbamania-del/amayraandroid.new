package com.amayra.maya.ui.diagnostics

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.amayra.maya.MayaApplication
import com.amayra.maya.core.MayaLog
import com.amayra.maya.ui.theme.Accent
import com.amayra.maya.ui.theme.Warn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

/**
 * Engine-status modal in the style of the reference design: model specs on top,
 * live network diagnostics below, honest values only.
 *
 * Every figure here is MEASURED on-device, never fabricated:
 *  - Gateway latency: real HEAD request against the active provider's base URL
 *  - Security: TLS/HTTP version from the actual response handshake
 *  - Active link: real ConnectivityManager transport
 *  - Local exec: real Termux install check
 *  - Tools: real count from the live ToolRegistry
 * Unmeasurable values show "unknown"/"unreachable" rather than invented numbers.
 */

private val PanelBg = Color(0xFF0E1320)
private val CardBg = Color(0xFF141A2A)
private val ChipBg = Color(0xFF1A2133)
private val Cyan = Color(0xFF00E5FF)
private val Green = Color(0xFF4CE0A6)
private val Purple = Color(0xFFB46BFF)
private val Dim = Color(0xFF8A93A8)
private val Mono = FontFamily.Monospace

/** One measured diagnostic row: icon chip, label, value pill. */
@Composable
private fun SpecRow(emoji: String, label: String, value: String, valueColor: Color) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(CardBg, RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(30.dp)
                .background(ChipBg, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) { Text(emoji, fontSize = 14.sp) }
        Spacer(Modifier.width(10.dp))
        Text(
            label,
            color = Color(0xFFE8EAF2),
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f)
        )
        Text(
            value,
            color = valueColor,
            fontSize = 12.sp,
            fontFamily = Mono,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .background(valueColor.copy(alpha = 0.10f), RoundedCornerShape(10.dp))
                .border(1.dp, valueColor.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
                .padding(horizontal = 10.dp, vertical = 5.dp)
        )
    }
}

private data class NetProbe(
    val loading: Boolean = true,
    val latencyMs: Int? = null,
    val security: String? = null,
    val failed: Boolean = false
)

/** Measure gateway latency + TLS/HTTP protocol with one real HEAD request. */
private suspend fun probeGateway(http: OkHttpClient, baseUrl: String): NetProbe = withContext(Dispatchers.IO) {
    val url = baseUrl.trimEnd('/').toHttpUrlOrNull()
        ?: return@withContext NetProbe(loading = false, failed = true)
    val req = try {
        okhttp3.Request.Builder().url(url).head().build()
    } catch (t: Throwable) {
        return@withContext NetProbe(loading = false, failed = true)
    }
    val t0 = System.nanoTime()
    try {
        http.newCall(req).execute().use { resp ->
            val ms = ((System.nanoTime() - t0) / 1_000_000).toInt()
            // tlsVersion / protocol come from the ACTUAL handshake response.
            val tls = resp.handshake?.tlsVersion?.toString()
            val proto = resp.protocol.toString().uppercase()
            NetProbe(
                loading = false,
                latencyMs = ms,
                security = if (tls != null) "$tls · $proto" else proto,
                failed = false
            )
        }
    } catch (t: Throwable) {
        MayaLog.w("DIAG", "gateway probe failed: ${t.message}")
        NetProbe(loading = false, failed = true)
    }
}

/** Real transport of the active network: WiFi / Cellular / Ethernet / VPN / none. */
private fun activeLink(ctx: android.content.Context): String {
    val cm = ctx.getSystemService(android.net.ConnectivityManager::class.java) ?: return "unknown"
    val net = cm.activeNetwork ?: return "no connection"
    val caps = cm.getNetworkCapabilities(net) ?: return "unknown"
    return when {
        caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
        caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
        caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
        caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
        else -> "other"
    }
}

/** Honest shell-execution capability: Termux installed AND callable. */
private fun localExecLabel(app: MayaApplication): Pair<String, Color> = try {
    if (com.amayra.maya.integration.TermuxManager.isInstalled(app)) "Termux ready" to Green
    else "Not installed" to Dim
} catch (t: Throwable) {
    "Not installed" to Dim
}

@Composable
fun EngineStatusDialog(app: MayaApplication, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val prefs by app.settings.prefs.collectAsState(initial = null)
    val p = prefs ?: return

    // pingKey increments on "Test Ping" -> re-runs the probe effect.
    var pingKey by remember { mutableStateOf(0) }
    var probe by remember(p.baseUrl) { mutableStateOf(NetProbe()) }
    LaunchedEffect(p.baseUrl, p.provider, pingKey) {
        probe = NetProbe()
        val base = if (p.provider == "openai_compat") p.baseUrl
                   else "https://generativelanguage.googleapis.com"
        probe = probeGateway(com.amayra.maya.ai.AiClient.newHttpClient(), base)
    }

    val tools = remember { app.registry.all().size }
    val (execLabel, execColor) = remember { localExecLabel(app) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .padding(horizontal = 18.dp)
                .fillMaxWidth()
                .background(PanelBg, RoundedCornerShape(22.dp))
                .border(1.dp, Color(0xFF232B3E), RoundedCornerShape(22.dp))
                .padding(18.dp)
        ) {
            // ── Header: model + status dot ────────────────────────────────
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(9.dp)
                        .background(if (probe.failed) Warn else Green, CircleShape)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    p.model.uppercase(),
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "✕",
                    color = Dim,
                    fontSize = 16.sp,
                    modifier = Modifier
                        .clickable { onDismiss() }
                        .padding(6.dp)
                )
            }
            Text(
                "Live engine status · measured on-device",
                color = Dim,
                fontSize = 11.sp,
                modifier = Modifier.padding(start = 17.dp, bottom = 12.dp)
            )

            // ── ENGINE SPECIFICATIONS ─────────────────────────────────────
            Text(
                "ENGINE SPECIFICATIONS",
                color = Accent,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SpecRow("🧠", "Provider", p.provider, Purple)
                SpecRow("⌘", "Tools loaded", "$tools live", Cyan)
                SpecRow("🔊", "Voice engine", p.ttsEngine, Cyan)
                SpecRow("⌨️", "Local exec", execLabel, execColor)
            }
            Spacer(Modifier.height(14.dp))

            // ── REAL-TIME NETWORK DIAGNOSTICS ─────────────────────────────
            Text(
                "REAL-TIME NETWORK DIAGNOSTICS",
                color = Accent,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SpecRow(
                    "▶",
                    "Gateway latency",
                    when {
                        probe.loading -> "measuring…"
                        probe.latencyMs != null -> "${probe.latencyMs} ms"
                        else -> "unreachable"
                    },
                    if (probe.failed) Warn else Green
                )
                SpecRow(
                    "🛡",
                    "Security",
                    probe.security ?: if (probe.loading) "measuring…" else "unknown",
                    if (probe.failed) Warn else Green
                )
                SpecRow("📶", "Active link", activeLink(context), Cyan)
            }
            Spacer(Modifier.height(14.dp))

            // ── Actions ───────────────────────────────────────────────────
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionChip(
                    text = if (probe.loading) "Pinging…" else "↻ Test Ping",
                    border = Cyan,
                    modifier = Modifier.weight(1f),
                    enabled = !probe.loading
                ) { pingKey++ }
                ActionChip(
                    text = "Diagnostics ▸",
                    border = Purple,
                    modifier = Modifier.weight(1f)
                ) {
                    onDismiss()
                    com.amayra.maya.core.StateBus.requestDiagnostics()
                }
            }
            Spacer(Modifier.height(12.dp))

            // ── Status strip ──────────────────────────────────────────────
            val ready = !probe.loading && !probe.failed
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(
                        if (ready) Color(0xFF00E5FF) else Warn.copy(alpha = 0.9f),
                        RoundedCornerShape(14.dp)
                    )
                    .padding(vertical = 13.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    when {
                        probe.loading -> "Measuring · $tools tools loaded"
                        ready -> "Active & Ready  |  ${probe.latencyMs} ms"
                        else -> "Provider unreachable — check network"
                    },
                    color = Color(0xFF06121C),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = Mono
                )
            }
        }
    }
}

@Composable
private fun ActionChip(
    text: String,
    border: Color,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Box(
        modifier
            .background(border.copy(alpha = 0.08f), RoundedCornerShape(14.dp))
            .border(1.dp, border.copy(alpha = if (enabled) 0.7f else 0.25f), RoundedCornerShape(14.dp))
            .clickable(enabled = enabled) { onClick() }
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            color = border.copy(alpha = if (enabled) 1f else 0.4f),
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}
