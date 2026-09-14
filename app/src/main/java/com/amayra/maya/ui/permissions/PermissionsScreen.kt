package com.amayra.maya.ui.permissions

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.amayra.maya.MainActivity
import com.amayra.maya.ui.theme.Ok
import com.amayra.maya.ui.theme.Surface

/**
 * Reference-parity Permissions screen: every capability Maya can use, one card
 * each, live Granted/Grant state, direct intents to the exact system page for
 * special accesses (accessibility, notifications, battery, overlay, assistant,
 * screen capture). State re-checks when the user returns from system settings.
 */
@Composable
fun PermissionsScreen() {
    val ctx = LocalContext.current
    var refresh by remember { mutableStateOf(0) }

    // Re-check when the user comes back from a system settings page.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) refresh++
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    val runtimeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refresh++ }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Permissions", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Maya needs these permissions to do everything for you. Allow only what you want.",
            style = MaterialTheme.typography.bodyMedium,
            color = com.amayra.maya.ui.theme.TextDim
        )

        permissionGroups().forEach { g ->
            PermCard(
                title = g.title,
                description = g.description,
                granted = g.granted(ctx),
                onGrant = {
                    val missing = g.missingPermissions(ctx)
                    if (missing.isNotEmpty()) runtimeLauncher.launch(missing.toTypedArray())
                    g.specialIntent?.let { ctx.startActivity(it) }
                    refresh++
                }
            )
        }

        specialCards().forEach { c ->
            PermCard(
                title = c.title,
                description = c.description,
                granted = c.granted(ctx),
                onGrant = {
                    try {
                        ctx.startActivity(c.intent(ctx))
                    } catch (_: Exception) {
                        // Fall back to the app details page if the panel is missing.
                        ctx.startActivity(appDetails(ctx))
                    }
                    refresh++
                },
                grantLabel = c.actionLabel
            )
        }
    }
}

@Composable
private fun PermCard(
    title: String,
    description: String,
    granted: Boolean,
    onGrant: () -> Unit,
    grantLabel: String = "Grant"
) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Surface)) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(description, style = MaterialTheme.typography.bodySmall, color = com.amayra.maya.ui.theme.TextDim)
            }
            Spacer(Modifier.padding(4.dp))
            if (granted) {
                TextButton(onClick = onGrant) { Text("Granted", color = Ok) }
            } else {
                Button(
                    onClick = onGrant,
                    colors = ButtonDefaults.buttonColors(containerColor = com.amayra.maya.ui.theme.Accent)
                ) { Text(grantLabel) }
            }
        }
    }
}

// ---- card models ---------------------------------------------------------

private class PermGroup(
    val title: String,
    val description: String,
    private val permissions: List<String>,
    val specialIntent: Intent? = null
) {
    fun granted(ctx: Context): Boolean = permissions.all {
        androidx.core.content.ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
    }
    fun missingPermissions(ctx: Context): List<String> = permissions.filter {
        androidx.core.content.ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED
    }
}

private class SpecialCard(
    val title: String,
    val description: String,
    val granted: (Context) -> Boolean,
    val intent: (Context) -> Intent,
    val actionLabel: String = "Grant"
)

private fun appDetails(ctx: Context) = Intent(
    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
    Uri.fromParts("package", ctx.packageName, null)
).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

private fun permissionGroups(): List<PermGroup> = listOf(
    PermGroup(
        "Default assistant",
        "Make Maya the phone's digital assistant (replaces Google Assistant) — long-press power / swipe from a corner opens her instantly.",
        emptyList(),
        specialIntent = Intent(Settings.ACTION_VOICE_INPUT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    ),
    PermGroup("Microphone", "So you can talk to Maya (required).", listOf(Manifest.permission.RECORD_AUDIO)),
    PermGroup("Camera", "So Maya can take your photo and scan barcodes.", listOf(Manifest.permission.CAMERA)),
    PermGroup("Phone calls", "So Maya can place calls for you.", listOf(Manifest.permission.CALL_PHONE)),
    PermGroup(
        "Location",
        "So Maya can give you location, navigation and weather.",
        listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    ),
    PermGroup(
        "Contacts",
        "So Maya can look up a contact's number when you say a name (for calls/SMS).",
        listOf(Manifest.permission.READ_CONTACTS)
    ),
    PermGroup("SMS", "So Maya can send text messages.", listOf(Manifest.permission.SEND_SMS)),
    PermGroup(
        "Gallery & files",
        "So Maya can find your photos/videos/files and send them to someone.",
        if (Build.VERSION.SDK_INT >= 33)
            listOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO
            )
        else
            listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    ),
    PermGroup(
        "Answer & manage calls",
        "So Maya can announce every incoming call and answer/reject/end it — Call Log is also needed to tell you the caller's name.",
        listOf(Manifest.permission.ANSWER_PHONE_CALLS, Manifest.permission.READ_PHONE_STATE)
    ),
    PermGroup(
        "Bluetooth",
        "So Maya's voice can play on a Bluetooth headset/speaker.",
        if (Build.VERSION.SDK_INT >= 31) listOf(Manifest.permission.BLUETOOTH_CONNECT) else emptyList()
    )
)

private fun specialCards(): List<SpecialCard> = listOf(
    SpecialCard(
        "App notifications",
        "Maya's notification, which keeps the session running.",
        granted = {
            Build.VERSION.SDK_INT < 33 || androidx.core.content.ContextCompat.checkSelfPermission(
                it, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        },
        intent = {
            if (Build.VERSION.SDK_INT >= 26)
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, it.packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            else appDetails(it)
        }
    ),
    SpecialCard(
        "Notification access",
        "To read notifications from all apps (and WhatsApp messages). Also how Maya knows the caller's name when announcing a call.",
        granted = {
            (Settings.Secure.getString(it.contentResolver, "enabled_notification_listeners") ?: "")
                .contains(it.packageName)
        },
        intent = { Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    ),
    SpecialCard(
        "Accessibility service",
        "For WhatsApp/YouTube control and screen reading (enable 'Maya' in the list).",
        granted = { c ->
            val expected = "${c.packageName}/com.amayra.maya.accessibility.MayaAccessibilityService"
            val enabled = Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
            enabled.split(':').any { svc -> svc.equals(expected, ignoreCase = true) }
        },
        intent = { Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    ),
    SpecialCard(
        "Battery — no optimization",
        "So Maya keeps running with the screen off / in the background — exempt her from battery optimization.",
        granted = {
            val pm = it.getSystemService(android.os.PowerManager::class.java)
            pm?.isIgnoringBatteryOptimizations(it.packageName) == true
        },
        intent = {
            @Suppress("BatteryLife")
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${it.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    ),
    SpecialCard(
        "Display over other apps",
        "So Maya can work on top of other apps.",
        granted = { Settings.canDrawOverlays(it) },
        intent = {
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${it.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    ),
    SpecialCard(
        "Screen capture",
        "So Maya can watch your screen live (screen share). She asks for this herself whenever she needs it. You can also test it once here.",
        granted = { com.amayra.maya.screen.ScreenShareBus.active.value },
        intent = {
            // Route through the app's own consent flow so the capture service starts.
            Intent(it, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        },
        actionLabel = "Test"
    )
)
