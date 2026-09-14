package com.amayra.maya.ui.setup

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.amayra.maya.MayaApplication
import com.amayra.maya.memory.SecureStore
import kotlinx.coroutines.launch

/**
 * First-run setup gate: welcome -> Gemini API key -> permissions primer.
 * Shown before anything else on a fresh install; the proactive greeting is
 * gated on [com.amayra.maya.settings.UserPreferences.setupComplete], so Maya
 * never talks over this screen. Skippable at every step — never trap the user.
 */
@Composable
fun SetupGateScreen(app: MayaApplication, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf(0) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Spacer(Modifier.height(24.dp))
        LinearProgressIndicator(
            progress = { (step + 1) / 3f },
            modifier = Modifier.fillMaxWidth(),
            color = com.amayra.maya.ui.theme.Accent
        )
        when (step) {
            0 -> WelcomeStep(onNext = { step = 1 })
            1 -> ApiKeyStep(
                app = app,
                onNext = {
                    scope.launch {
                        app.settings.update { it[androidx.datastore.preferences.core.booleanPreferencesKey("setup_complete")] = true }
                    }
                    step = 2
                },
                onSkip = {
                    scope.launch {
                        app.settings.update { it[androidx.datastore.preferences.core.booleanPreferencesKey("setup_complete")] = true }
                    }
                    step = 2
                }
            )
            2 -> PermissionsStep(
                ctx = ctx,
                onDone = {
                    scope.launch {
                        app.settings.update { it[androidx.datastore.preferences.core.booleanPreferencesKey("setup_complete")] = true }
                        app.core.start()
                    }
                    onDone()
                }
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun WelcomeStep(onNext: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Meet Amayra", style = MaterialTheme.typography.headlineLarge)
        Text(
            "Your personal AI that lives on your phone. She can talk, listen, see your screen, " +
                "run your apps and answer only to your voice — once you set her up.",
            style = MaterialTheme.typography.bodyLarge,
            color = com.amayra.maya.ui.theme.TextDim
        )
        SetupBullet("A Gemini API key unlocks her brain (free tier works)")
        SetupBullet("Microphone so you can talk to her")
        SetupBullet("A few permissions so she can act for you")
        Spacer(Modifier.height(8.dp))
        Button(onClick = onNext, modifier = Modifier.fillMaxWidth()) { Text("Let's go") }
    }
}

@Composable
private fun SetupBullet(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("•  ", style = MaterialTheme.typography.bodyLarge, color = com.amayra.maya.ui.theme.Accent)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ApiKeyStep(app: MayaApplication, onNext: () -> Unit, onSkip: () -> Unit) {
    var key by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Connect her brain", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Paste a Gemini API key — get one free at aistudio.google.com/apikey. " +
                "It's stored encrypted on this device only.",
            style = MaterialTheme.typography.bodyMedium,
            color = com.amayra.maya.ui.theme.TextDim
        )
        OutlinedTextField(
            value = key,
            onValueChange = { key = it },
            label = { Text("Gemini API key") },
            modifier = Modifier.fillMaxWidth(),
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true
        )
        Button(
            onClick = {
                if (key.isNotBlank()) {
                    app.secure.putString(SecureStore.KEY_GEMINI, key.trim())
                }
                onNext()
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = key.isNotBlank()
        ) { Text("Save & continue") }
        TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) {
            Text("Skip for now — I'll add it in Settings")
        }
    }
}

@Composable
private fun PermissionsStep(ctx: android.content.Context, onDone: () -> Unit) {
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { onDone() }

    val micGranted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("One permission to start", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Maya needs your microphone so you can talk to her. Everything else — calls, SMS, " +
                "notifications, accessibility — you can grant later from Settings → Advanced → " +
                "Permissions, and only when you want that feature.",
            style = MaterialTheme.typography.bodyMedium,
            color = com.amayra.maya.ui.theme.TextDim
        )
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = com.amayra.maya.ui.theme.Surface)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Microphone", style = MaterialTheme.typography.titleSmall)
                Text(
                    if (micGranted) "Granted ✓" else "Needed for voice conversation",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (micGranted) com.amayra.maya.ui.theme.Ok else com.amayra.maya.ui.theme.TextDim
                )
            }
        }
        Button(
            onClick = { if (micGranted) onDone() else launcher.launch(Manifest.permission.RECORD_AUDIO) },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = com.amayra.maya.ui.theme.Accent)
        ) { Text(if (micGranted) "Start talking to Maya" else "Allow microphone & start") }
        TextButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
            Text("Not now")
        }
    }
}
