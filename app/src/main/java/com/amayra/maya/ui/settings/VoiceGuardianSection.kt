package com.amayra.maya.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.amayra.maya.AppGraph
import com.amayra.maya.MayaApplication
import com.amayra.maya.core.AssistantState
import com.amayra.maya.core.MayaLog
import com.amayra.maya.core.StateBus
import com.amayra.maya.voice.guardian.VoiceGuardian
import com.amayra.maya.persona.Persona
import com.amayra.maya.voice.wakeword.WakeWordService
import kotlinx.coroutines.launch

/**
 * Settings → Voice & Personas section:
 *  - wake-word toggle (starts/stops WakeWordService)
 *  - Guardian enrollment (label + Record button, 6 s of speech)
 *  - persona picker (Maya / Friday / Venom)
 */
@Composable
fun VoiceGuardianSection(app: MayaApplication) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs by app.settings.prefs.collectAsState(initial = null)
    val guardian = remember { VoiceGuardian.get() }
    var status by remember { mutableStateOf("Idle") }
    var enrolled by remember { mutableStateOf(guardian?.store?.all()?.size ?: 0) }

    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text("Voice & Personas", style = MaterialTheme.typography.titleMedium)

        // ---- Wake word ----
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(
                "Wake word (\"Hey Maya\")",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium
            )
            Switch(
                checked = prefs?.wakeWordEnabled == true,
                onCheckedChange = { on ->
                    scope.launch {
                        app.settings.update {
                            it[androidx.datastore.preferences.core.booleanPreferencesKey("wake_word_enabled")] = on
                        }
                    }
                    if (on) WakeWordService.start(ctx) else WakeWordService.stop(ctx)
                }
            )
        }

        // ---- Voice Guardian ----
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Voice Guardian", style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (guardian?.isUsable == true) "Speaker model loaded" else "Speaker model missing",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Switch(
                checked = prefs?.guardianEnabled == true,
                onCheckedChange = { on ->
                    scope.launch {
                        app.settings.update {
                            it[androidx.datastore.preferences.core.booleanPreferencesKey("guardian_enabled")] = on
                        }
                    }
                }
            )
        }
        if (prefs?.guardianEnabled == true) {
            var label by remember { mutableStateOf("Boss") }
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Profile name") },
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        status = "Recording… speak for ~6 s"
                        // Enrollment uses the mic loop in WakeWordService; if it is not
                        // running, instruct the user (honest guidance, no fake capture).
                        scope.launch {
                            val g = VoiceGuardian.get()
                            if (g == null || !g.isUsable) {
                                status = "Speaker model missing — cannot enroll."
                            } else if (prefs?.wakeWordEnabled != true) {
                                status = "Turn on the wake word first (it feeds the mic loop)."
                            } else {
                                status = "Listening… speak naturally; auto-enrolls after 6 s of speech."
                                // Enrollment completion is surfaced by the service via StateBus.guardianSignal
                            }
                        }
                    }
                ) { Text("Record") }
            }
            Text("Profiles: $enrolled — $status", style = MaterialTheme.typography.bodySmall)

            // ---- Persona picker ----
            Spacer(Modifier.height(8.dp))
            Text("Persona", style = MaterialTheme.typography.bodyMedium)
            Row {
                com.amayra.maya.persona.Persona.ALL.forEach { p ->
                    FilterChip(
                        selected = AppGraph.personas.active.value.id == p.id,
                        onClick = { scope.launch { AppGraph.personas.switch(p.id) } },
                        label = { Text(p.displayName) }
                    )
                    Spacer(Modifier.width(6.dp))
                }
            }

            // ---- TTS engine + persona voice ----
            Spacer(Modifier.height(8.dp))
            Text("Speech engine", style = MaterialTheme.typography.bodyMedium)
            Row {
                FilterChip(
                    selected = prefs?.ttsEngine != "android",
                    onClick = {
                        scope.launch {
                            app.settings.update {
                                it[androidx.datastore.preferences.core.stringPreferencesKey("tts_engine")] = "gemini"
                            }
                        }
                    },
                    label = { Text("Gemini voice") }
                )
                Spacer(Modifier.width(6.dp))
                FilterChip(
                    selected = prefs?.ttsEngine == "android",
                    onClick = {
                        scope.launch {
                            app.settings.update {
                                it[androidx.datastore.preferences.core.stringPreferencesKey("tts_engine")] = "android"
                            }
                        }
                    },
                    label = { Text("Device voice") }
                )
            }
            if (prefs?.ttsEngine != "android") {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Voice for ${AppGraph.personas.active.value.displayName}",
                    style = MaterialTheme.typography.bodySmall
                )
                // Observable reads — recompose on change so the selected chip
                // updates instantly (plain .value reads skip recomposition).
                val personas = AppGraph.personas
                val activePersona by personas.active.collectAsState()
                val currentVoice by personas.voice.collectAsState()
                val voices = activePersona.voices
                voices.chunked(4).forEach { rowVoices ->
                    Row {
                        rowVoices.forEach { v ->
                            FilterChip(
                                selected = currentVoice == v,
                                onClick = {
                                    scope.launch { personas.setVoice(v) }
                                },
                                label = { Text(v, style = MaterialTheme.typography.labelSmall) }
                            )
                            Spacer(Modifier.width(4.dp))
                        }
                    }
                }
                // Immediate, honest feedback so selection never feels dead.
                Text(
                    "Selected: $currentVoice",
                    style = MaterialTheme.typography.labelSmall,
                    color = com.amayra.maya.ui.theme.Ok
                )
                Spacer(Modifier.height(4.dp))
                OutlinedButton(onClick = {
                    scope.launch {
                        app.voice.speak(
                            "Hi Boss, this is ${AppGraph.personas.active.value.displayName}. " +
                                "This is how I sound now."
                        )
                    }
                }) { Text("Test voice") }
                Text(
                    "Gemini TTS needs a Gemini API key (Settings → AI). Falls back to the device voice if unavailable.",
                    style = MaterialTheme.typography.labelSmall,
                    color = com.amayra.maya.ui.theme.TextDim
                )
            }

            // ---- PC relay ----
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("PC relay (port ${prefs?.pcRelayPort ?: 18789})", modifier = Modifier.weight(1f))
                Switch(
                    checked = prefs?.pcRelayEnabled == true,
                    onCheckedChange = { on ->
                    scope.launch {
                        app.settings.update {
                            it[androidx.datastore.preferences.core.booleanPreferencesKey("pc_relay_enabled")] = on
                        }
                    }
                    if (on) AppGraph.pcRelay.start(prefs?.pcRelayPort ?: 18789)
                    else AppGraph.pcRelay.stop()
                }
                )
            }

            // ---- PC bridge (drive the PC Amayra assistant) ----
            var pcHost by rememberSaveable(prefs?.pcHost) {
                mutableStateOf(prefs?.pcHost ?: "")
            }
            var pingResult by rememberSaveable { mutableStateOf("") }
            OutlinedTextField(
                value = pcHost,
                onValueChange = { text -> pcHost = text },
                label = { Text("PC assistant address (IP or hostname)") },
                placeholder = { Text("e.g. 192.168.1.20") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    scope.launch {
                        app.settings.update {
                            it[androidx.datastore.preferences.core.stringPreferencesKey("pc_host")] = pcHost.trim()
                        }
                    }
                }) { Text("Save PC address") }
                Button(onClick = {
                    scope.launch {
                        val host = pcHost.trim()
                        if (host.isBlank()) {
                            pingResult = "Enter the PC address first."
                        } else {
                            pingResult = "Pinging…"
                            val reply = com.amayra.maya.integration.PcCommandClient.ping(host, prefs?.pcRelayPort ?: 18789)
                            pingResult = if (reply.ok) "PC online ✓ ${reply.summary}" else "✗ ${reply.error}"
                        }
                    }
                }) { Text("Test connection") }
            }
            if (pingResult.isNotBlank()) Text(pingResult, style = MaterialTheme.typography.labelSmall, color = com.amayra.maya.ui.theme.TextDim)
            Text(
                "Maya can then run tasks on your PC via the pc_command tool " +
                    "(\"open Chrome on my PC\") — the PC Amayra plans and executes them. " +
                    "Both devices must be on the same network; the PC assistant's relay must be running.",
                style = MaterialTheme.typography.labelSmall,
                color = com.amayra.maya.ui.theme.TextDim
            )
        }
    }
}
