package com.amayra.maya.ui.diagnostics

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.amayra.maya.MayaApplication
import com.amayra.maya.integration.OpenClawManager
import com.amayra.maya.integration.TermuxManager
import com.amayra.maya.integration.WhatsAppManager
import com.amayra.maya.ui.theme.Danger
import com.amayra.maya.ui.theme.Ok
import com.amayra.maya.ui.theme.TextDim
import com.amayra.maya.ui.theme.Warn
import kotlinx.coroutines.launch

data class DiagRow(val label: String, val ok: Boolean?, val detail: String)

/** Maya Diagnostics: dependency status with actionable repair hints. */
@Composable
fun DiagnosticsScreen(app: MayaApplication) {
    val rows = remember { mutableStateOf(listOf<DiagRow>()) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        rows.value = listOf(
            DiagRow(
                "Device",
                true,
                "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})"
            ),
            DiagRow("Network", true, "Assumed available (AI calls will confirm)"),
            DiagRow(
                "Gemini key",
                if (app.secure.getString(com.amayra.maya.memory.SecureStore.KEY_GEMINI).isNullOrBlank()) null else true,
                if (app.secure.getString(com.amayra.maya.memory.SecureStore.KEY_GEMINI).isNullOrBlank()) "Not set — add it in Settings → AI"
                else "Configured (stored encrypted)"
            ),
            DiagRow(
                "OpenAI-compat key",
                if (app.secure.getString(com.amayra.maya.memory.SecureStore.KEY_OPENAI_COMPAT).isNullOrBlank()) null else true,
                if (app.secure.getString(com.amayra.maya.memory.SecureStore.KEY_OPENAI_COMPAT).isNullOrBlank()) "Not set (needed for Groq/OpenRouter/OpenAI/local)"
                else "Configured (stored encrypted)"
            ),
            DiagRow(
                "Termux",
                if (TermuxManager.isInstalled(app)) true else false,
                if (TermuxManager.isInstalled(app))
                    "Installed · RUN_COMMAND ${if (TermuxManager.hasRunCommandPermission(app)) "granted" else "NOT granted"}"
                else "Not installed — get it from F-Droid, then enable 'Allow external apps'"
            ),
            DiagRow(
                "WhatsApp",
                if (WhatsAppManager.isInstalled(app)) true else false,
                if (WhatsAppManager.isInstalled(app)) "Installed" else "Not installed"
            ),
            DiagRow("OpenClaw gateway", null, "Probing…"),
            DiagRow(
                "Notification listener",
                null,
                "Enable in Android Settings → Notification access → Maya (needed for WhatsApp auto-reply)"
            ),
            DiagRow(
                "Microphone",
                if (app.voice.hasMicPermission()) true else false,
                if (app.voice.hasMicPermission()) "RECORD_AUDIO granted" else "Grant in Android Settings → Apps → Maya → Permissions"
            ),
            DiagRow(
                "TTS",
                if (app.voice.ttsReady) true else false,
                if (app.voice.ttsReady) "TextToSpeech ready" else "TTS engine not ready yet (or no engine installed)"
            ),
            DiagRow(
                "Battery optimization",
                null,
                "If background standby is killed, exempt Maya: Settings → Battery → Unrestricted"
            )
        )
        // ---- v4.15.1 subsystems -------------------------------------------

        val prefs = app.settings.prefsCache
        val guardian = runCatching { app.guardian }.getOrNull()
        val guardianModels = if (guardian?.isUsable == true) {
            val parts = mutableListOf("ECAPA embedder OK (model ${guardian.modelId})")
            parts += if (guardian.vad != null) "Silero VAD loaded" else "VAD missing (DSP fallback)"
            parts += if (guardian.normalizer.cohortSize > 0) "cohort: ${guardian.normalizer.cohortSize} voices" else "no cohort (raw cosine)"
            val n = guardian.store.all().size
            parts += if (n > 0) "$n enrolled voiceprint(s)" else "no voiceprints enrolled — Settings → Voice Guardian"
            parts.joinToString(" · ")
        } else "Models missing from assets/guardian/ — Guardian inert"

        rows.value += listOf(
            DiagRow(
                "Voice Guardian",
                guardian?.isUsable,
                guardianModels
            ),
            DiagRow(
                "Wake word",
                if (com.amayra.maya.voice.wakeword.WakeWordService.serviceRunning) true
                else prefs?.wakeWordEnabled?.not(),
                when {
                    com.amayra.maya.voice.wakeword.WakeWordService.serviceRunning ->
                        "Foreground service running" +
                            if (com.amayra.maya.voice.wakeword.WakeWordService.engineLoaded) " · engine loaded" else " · engine loading…"
                    prefs?.wakeWordEnabled == true ->
                        "Enabled in Settings but service not running — toggle it off/on or reopen the app"
                    else -> "Off (enable in Settings → Voice Guardian & Personas)"
                }
            ),
            DiagRow(
                "Accessibility service",
                com.amayra.maya.accessibility.MayaAccessibilityService.isLive,
                if (com.amayra.maya.accessibility.MayaAccessibilityService.isLive)
                    "Connected — macros, gestures and screen reading available"
                else
                    "Not enabled — Android Settings → Accessibility → Maya (needed for macros & screen control)"
            ),
            DiagRow(
                "PC relay",
                com.amayra.maya.AppGraph.pcRelay.running,
                when {
                    com.amayra.maya.AppGraph.pcRelay.running -> "Listening on port ${prefs?.pcRelayPort ?: com.amayra.maya.integration.PcRelay.DEFAULT_PORT} — JSON lines over TCP"
                    prefs?.pcRelayEnabled == true ->
                        "Enabled in Settings but not listening — toggle it off/on (port ${prefs?.pcRelayPort ?: com.amayra.maya.integration.PcRelay.DEFAULT_PORT})"
                    else -> "Off (port would be ${prefs?.pcRelayPort ?: com.amayra.maya.integration.PcRelay.DEFAULT_PORT} — enable in Settings)"
                }
            )
        )

        // Network probe runs on IO via the suspending API; patch the row when done.
        val oc = OpenClawManager.probe()
        rows.value = rows.value.map { r ->
            if (r.label == "OpenClaw gateway") DiagRow("OpenClaw gateway", oc.reachable, oc.detail) else r
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Amayra Diagnostics", style = MaterialTheme.typography.headlineMedium)
        rows.value.forEach { r ->
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = com.amayra.maya.ui.theme.Surface
                )
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        r.label + "  " + when (r.ok) {
                            true -> "✅"
                            false -> "❌"
                            null -> "ℹ️"
                        },
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(r.detail, style = MaterialTheme.typography.bodyMedium, color = TextDim)
                }
            }
        }
    }
}
