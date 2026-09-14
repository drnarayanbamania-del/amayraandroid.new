package com.amayra.maya.ui.settings

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import android.Manifest
import android.content.pm.PackageManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.amayra.maya.MayaApplication
import com.amayra.maya.ai.OpenAiCompatClient
import com.amayra.maya.core.MayaCoreService
import com.amayra.maya.memory.SecureStore

/** Full settings surface: AI, Voice, Avatar, Memory, WhatsApp, SOS, Standby. */
@Composable
fun SettingsScreen(app: MayaApplication, onOpenPermissions: () -> Unit = {}) {
    val prefs by app.settings.prefs.collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    val p = prefs ?: return

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)

        // ── AI ────────────────────────────────────────────────
        SectionCard("AI Provider") {
            LabeledRow("Provider") {
                ProviderDropdown(app, p.provider)
            }
            OutlinedTextField(
                value = p.model,
                onValueChange = { v -> scope.launch { app.settings.update { it[androidx.datastore.preferences.core.stringPreferencesKey("model")] = v } } },
                label = { Text("Model") },
                modifier = Modifier.fillMaxWidth()
            )
            if (p.provider == "openai_compat") {
                OutlinedTextField(
                    value = p.baseUrl,
                    onValueChange = { v -> scope.launch { app.settings.update { it[androidx.datastore.preferences.core.stringPreferencesKey("base_url")] = v } } },
                    label = { Text("Base URL (OpenAI-compatible)") },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "Presets: " + OpenAiCompatClient.PRESET_BASE_URLS.joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium,
                    color = com.amayra.maya.ui.theme.TextDim
                )
            }
            var gemKey by remember { mutableStateOf("") }
            var compatKey by remember { mutableStateOf("") }
            var keyStatus by remember { mutableStateOf<String?>(null) }
            OutlinedTextField(
                value = gemKey,
                onValueChange = { gemKey = it },
                label = { Text("Gemini API key (stored encrypted)") },
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation()
            )
            OutlinedTextField(
                value = compatKey,
                onValueChange = { compatKey = it },
                label = { Text("OpenAI-compatible API key — GROQ goes here") },
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation()
            )
            Text(
                "Chat falls back to this provider automatically when Gemini hits its daily quota. Groq: console.groq.com → API Keys.",
                style = MaterialTheme.typography.labelMedium,
                color = com.amayra.maya.ui.theme.TextDim
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    scope.launch {
                        var ok = true
                        if (gemKey.isNotBlank()) {
                            app.secure.putString(SecureStore.KEY_GEMINI, gemKey.trim())
                            // Read-back verification so "Saved ✓" is a claim we proved.
                            ok = app.secure.getString(SecureStore.KEY_GEMINI)?.isNotBlank() == true
                        }
                        if (compatKey.isNotBlank()) {
                            app.secure.putString(SecureStore.KEY_OPENAI_COMPAT, compatKey.trim())
                            ok = ok && app.secure.getString(SecureStore.KEY_OPENAI_COMPAT)?.isNotBlank() == true
                        }
                        keyStatus = when {
                            gemKey.isBlank() && compatKey.isBlank() -> "Nothing to save — paste a key first."
                            ok -> "✅ Key(s) saved and verified."
                            else -> "⚠️ Save failed — try again or reinstall if it persists."
                        }
                        if (ok && (gemKey.isNotBlank() || compatKey.isNotBlank())) {
                            gemKey = ""
                            compatKey = ""
                        }
                    }
                }) { Text("Save keys") }
                OutlinedButton(onClick = {
                    scope.launch {
                        app.secure.remove(SecureStore.KEY_GEMINI)
                        app.secure.remove(SecureStore.KEY_OPENAI_COMPAT)
                    }
                }) { Text("Clear keys") }
            }
            keyStatus?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelMedium,
                    color = com.amayra.maya.ui.theme.TextDim
                )
            }
            Text(
                "Keys are encrypted with Android Keystore. They never appear in logs or backups.",
                style = MaterialTheme.typography.labelMedium,
                color = com.amayra.maya.ui.theme.TextDim
            )
        }

        // ── Voice / Avatar ────────────────────────────────────
        SectionCard("Voice & Avatar") {
            ToggleRow("Auto-speak replies", p.autoSpeak) { v ->
                scope.launch { app.settings.update { it[androidx.datastore.preferences.core.booleanPreferencesKey("auto_speak")] = v } }
            }
            // TTS engine picker: "android" = quota-free device voice (works all
            // day); "gemini" = persona voice but burns the daily Gemini budget.
            LabeledRow("Reply voice") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = p.ttsEngine == "piper",
                        onClick = { scope.launch { app.settings.update { it[androidx.datastore.preferences.core.stringPreferencesKey("tts_engine")] = "piper" } } },
                        label = { Text("Amayra (offline)") }
                    )
                    FilterChip(
                        selected = p.ttsEngine == "gemini",
                        onClick = { scope.launch { app.settings.update { it[androidx.datastore.preferences.core.stringPreferencesKey("tts_engine")] = "gemini" } } },
                        label = { Text("Maya (Gemini)") }
                    )
                    FilterChip(
                        selected = p.ttsEngine == "android",
                        onClick = { scope.launch { app.settings.update { it[androidx.datastore.preferences.core.stringPreferencesKey("tts_engine")] = "android" } } },
                        label = { Text("Android") }
                    )
                }
            }
            Text(
                when (p.ttsEngine) {
                    "piper" -> "Natural young-female Hindi neural voice — fully offline, unlimited, no quota ever."
                    "android" -> "Device voice — unlimited, no Gemini quota used."
                    else -> "Natural persona voice — uses Gemini quota (falls back to device voice when quota runs out)."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // Hands-free: after every spoken reply Maya listens again on her own —
            // no mic tap, no wake word, just keep talking (blueprint §4 follow-ups).
            ToggleRow("Hands-free conversation", p.handsFreeListening) { v ->
                scope.launch { app.settings.update { it[androidx.datastore.preferences.core.booleanPreferencesKey("hands_free_listening")] = v } }
            }
            Text(
                "After each reply Maya listens again automatically — just keep talking. " +
                    "Turn off to go back to tap-the-mic each time.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            ToggleRow("Avatar enabled", p.avatarEnabled) { v ->
                scope.launch { app.settings.update { it[androidx.datastore.preferences.core.booleanPreferencesKey("avatar_enabled")] = v } }
            }
            if (p.avatarEnabled) {
                LabeledRow("Avatar style") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        com.amayra.maya.avatar.AvatarRenderers.available.forEach { r ->
                            FilterChip(
                                selected = p.avatarStyle == r.styleId,
                                onClick = {
                                    scope.launch { app.settings.update { it[androidx.datastore.preferences.core.stringPreferencesKey("avatar_style")] = r.styleId } }
                                },
                                label = { Text(r.styleLabel) }
                            )
                        }
                    }
                }
                LabeledRow("Avatar size") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("small", "medium", "large").forEach { s ->
                            FilterChip(
                                selected = p.avatarSize == s,
                                onClick = {
                                    scope.launch { app.settings.update { it[androidx.datastore.preferences.core.stringPreferencesKey("avatar_size")] = s } }
                                },
                                label = { Text(s.replaceFirstChar { c -> c.uppercase() }) }
                            )
                        }
                    }
                }
                ToggleRow("Speaking animation (mouth sync)", p.speakingAnimEnabled) { v ->
                    scope.launch { app.settings.update { it[androidx.datastore.preferences.core.booleanPreferencesKey("avatar_speaking_anim")] = v } }
                }
                ToggleRow("Idle animation (breathing/blink)", p.idleAnimEnabled) { v ->
                    scope.launch { app.settings.update { it[androidx.datastore.preferences.core.booleanPreferencesKey("avatar_idle_anim")] = v } }
                }
                ToggleRow("Blinking", p.blinkingEnabled) { v ->
                    scope.launch { app.settings.update { it[androidx.datastore.preferences.core.booleanPreferencesKey("avatar_blinking")] = v } }
                }
                ToggleRow("Lip sync (mouth follows TTS)", p.lipsyncEnabled) { v ->
                    scope.launch { app.settings.update { it[androidx.datastore.preferences.core.booleanPreferencesKey("avatar_lipsync")] = v } }
                }
                ToggleRow("Expressions", p.expressionsEnabled) { v ->
                    scope.launch { app.settings.update { it[androidx.datastore.preferences.core.booleanPreferencesKey("avatar_expressions")] = v } }
                }
                ToggleRow("Voice chat mode by default", p.voiceChatDefault) { v ->
                    scope.launch { app.settings.update { it[androidx.datastore.preferences.core.booleanPreferencesKey("voice_chat_default")] = v } }
                }
                Text(
                    "Live2D (Cubism) style: place maya.model3.json + maya.moc3 + textures under app/src/main/assets/live2d/maya/ — the app validates and loads them automatically (see the bundled README.txt).",
                    style = MaterialTheme.typography.labelMedium,
                    color = com.amayra.maya.ui.theme.TextDim
                )
            }
        }

        // ── Memory ───────────────────────────────────────────
        SectionCard("Memory") {
            ToggleRow("Memory enabled", p.memoryEnabled) { v ->
                scope.launch { app.settings.update { it[androidx.datastore.preferences.core.booleanPreferencesKey("memory_enabled")] = v } }
            }
            OutlinedButton(onClick = {
                scope.launch { app.db.memoryDao.deleteAll() }
            }) { Text("Clear all memory") }
        }

        // ── WhatsApp auto-reply ──────────────────────────────
        SectionCard("WhatsApp Auto-Reply") {
            val ctx = androidx.compose.ui.platform.LocalContext.current
            val contactPermission = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { granted ->
                com.amayra.maya.core.MayaLog.i("WA", if (granted) "READ_CONTACTS granted — auto-reply can resolve contact names" else "READ_CONTACTS denied — auto-reply will use clipboard fallback")
            }
            val contactsGranted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
            if (!contactsGranted) {
                Text(
                    "Auto-reply resolves contact names to phone numbers. Grant contacts access so replies reach the right chat; without it, replies fall back to your clipboard.",
                    style = MaterialTheme.typography.labelMedium,
                    color = com.amayra.maya.ui.theme.TextDim
                )
                Button(onClick = { contactPermission.launch(Manifest.permission.READ_CONTACTS) }) {
                    Text("Grant contacts access")
                }
            }
            ToggleRow("Auto-reply enabled", p.waAutoReplyEnabled) { v ->
                scope.launch { app.settings.update { it[androidx.datastore.preferences.core.booleanPreferencesKey("wa_auto_reply_enabled")] = v } }
            }
            OutlinedTextField(
                value = p.waAutoReplyMaxPerDay.toString(),
                onValueChange = { v ->
                    scope.launch {
                        app.settings.update { it[androidx.datastore.preferences.core.intPreferencesKey("wa_auto_reply_max")] = v.toIntOrNull() ?: 20 }
                    }
                },
                label = { Text("Max replies per day") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = p.waAutoReplyContacts,
                onValueChange = { v ->
                    scope.launch { app.settings.update { it[androidx.datastore.preferences.core.stringPreferencesKey("wa_auto_reply_contacts")] = v } }
                },
                label = { Text("Allowed contacts (comma-separated, empty = all)") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = p.waAutoReplyQuietHours,
                onValueChange = { v ->
                    scope.launch { app.settings.update { it[androidx.datastore.preferences.core.stringPreferencesKey("wa_auto_reply_quiet")] = v } }
                },
                label = { Text("Quiet hours (HH:MM-HH:MM)") },
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                "Replies always go through the WhatsApp app for you to review. Every reply is logged in Maya → Tools.",
                style = MaterialTheme.typography.labelMedium,
                color = com.amayra.maya.ui.theme.TextDim
            )
        }

        // ── SOS ──────────────────────────────────────────────
        SectionCard("SOS Emergency") {
            ToggleRow("SOS enabled", p.sosEnabled) { v ->
                scope.launch { app.settings.update { it[androidx.datastore.preferences.core.booleanPreferencesKey("sos_enabled")] = v } }
            }
            OutlinedTextField(
                value = p.sosContactNumber,
                onValueChange = { v ->
                    scope.launch { app.settings.update { it[androidx.datastore.preferences.core.stringPreferencesKey("sos_number")] = v } }
                },
                label = { Text("Emergency contact number (E.164)") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = p.sosContactName,
                onValueChange = { v ->
                    scope.launch { app.settings.update { it[androidx.datastore.preferences.core.stringPreferencesKey("sos_name")] = v } }
                },
                label = { Text("Emergency contact name") },
                modifier = Modifier.fillMaxWidth()
            )
        }

        // ── Standby ──────────────────────────────────────────
        SectionCard("Standby & Background") {
            ToggleRow("Standby service (persistent assistant)", p.standbyEnabled) { v ->
                scope.launch {
                    app.settings.update { it[androidx.datastore.preferences.core.booleanPreferencesKey("standby_enabled")] = v }
                    if (v) MayaCoreService.start(app)
                }
            }
            ToggleRow("Proactive greeting on launch", p.proactiveGreeting) { v ->
                scope.launch { app.settings.update { it[androidx.datastore.preferences.core.booleanPreferencesKey("proactive_greeting")] = v } }
            }
            Text(
                "For OEMs (Xiaomi/OPPO/Vivo): allow autostart and disable battery optimization for Maya so standby survives reboots.",
                style = MaterialTheme.typography.labelMedium,
                color = com.amayra.maya.ui.theme.TextDim
            )
        }

        // ── Voice Guardian / Wake word / Personas (v4.15.1 parity) ──
        SectionCard("Voice Guardian & Personas") {
            VoiceGuardianSection(app)
        }

        // ── Advanced / safety & access ─────────────────────────
        SectionCard("Advanced") {
            NavRow("Permissions", "Grant the access Maya's features need") { onOpenPermissions() }
            NavRow("Voice Guardian", "Answer only your voice") {
                // Guardian controls live in the section below; scroll anchor is enough here.
            }
            NavRow("Emergency SOS", "Contacts, siren and what gets sent") {
                // SOS settings live in the SOS section above.
            }
            NavRow("WhatsApp auto-reply", "Answer messages for you while you are away") {
                // WhatsApp section above owns this.
            }
        }

        Spacer(Modifier.padding(bottom = 30.dp))
    }
}

@Composable
private fun NavRow(label: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = com.amayra.maya.ui.theme.TextDim)
        }
        TextButton(onClick = onClick) { Text("Open") }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    // Matches the chat polish: rounded surface + hairline stroke separation.
    Card(
        Modifier.fillMaxWidth().border(1.dp, com.amayra.maya.ui.theme.Stroke, RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = com.amayra.maya.ui.theme.Surface)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun LabeledRow(label: String, content: @Composable () -> Unit) {
    Column {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        content()
    }
}

@Composable
private fun ProviderDropdown(app: MayaApplication, current: String) {
    val scope = rememberCoroutineScope()
    Column {
        Text(
            if (current == "gemini") "Google Gemini"
            else "OpenAI-compatible (Groq / OpenRouter / OpenAI / local)",
            style = MaterialTheme.typography.bodyMedium
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = current == "gemini",
                onClick = {
                    scope.launch {
                        app.settings.update {
                            it[androidx.datastore.preferences.core.stringPreferencesKey("provider")] = "gemini"
                        }
                    }
                },
                label = { Text("Gemini") }
            )
            FilterChip(
                selected = current != "gemini",
                onClick = {
                    scope.launch {
                        app.settings.update {
                            it[androidx.datastore.preferences.core.stringPreferencesKey("provider")] = "openai_compat"
                        }
                    }
                },
                label = { Text("OpenAI-compatible") }
            )
        }
    }
}
