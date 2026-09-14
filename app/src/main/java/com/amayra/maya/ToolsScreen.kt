package com.amayra.maya

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.VolumeDown
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.CalendarMonth
import com.amayra.maya.core.StateBus
import com.amayra.maya.core.AssistantState
import com.amayra.maya.tools.Tool
import kotlinx.coroutines.launch

/**
 * Myra-style Tools screen: a neon tile grid. Every tile invokes a real
 * registered tool through the same ToolRegistry the AI uses — one execution
 * path, two entry points.
 */

// Neon tile palette (Myra reference).
private val Cyan = Color(0xFF22D3EE)
private val Pink = Color(0xFFEC4899)
private val Purple = Color(0xFFA855F7)
private val Amber = Color(0xFFF59E0B)

private data class Tile(
    val label: String,
    val subtitle: String,
    val icon: ImageVector,
    val tint: Color,
    val toolName: String,
    val argsJson: String = "{}"
)

private val COMMUNICATION_TILES = listOf(
    Tile("WhatsApp", "Chat & Status", Icons.Filled.Chat, Cyan, "whatsapp_send_message"),
    Tile("Google Mail", "Gmail Inboxes", Icons.Filled.Email, Pink, "open_app", """{"app_name":"Gmail"}"""),
    Tile("YouTube", "Videos & Music", Icons.Filled.PlayArrow, Pink, "open_app", """{"app_name":"YouTube"}"""),
    Tile("SMS", "Send Alert Prompt", Icons.Filled.NotificationsActive, Amber, "send_sms"),
    Tile("Calendar Memory", "Event & Sync", Icons.Filled.CalendarMonth, Cyan, "open_calendar"),
    Tile("Calculator", "Fast Math", Icons.Filled.Calculate, Purple, "open_app", """{"app_name":"Calculator"}""")
)

private val QUICK_ACTION_TILES = listOf(
    Tile("Turn Torch On", "Camera Flashlight", Icons.Filled.FlashlightOn, Amber, "flashlight", """{"on":true}"""),
    Tile("Raise Volume", "System Audio +", Icons.Filled.VolumeUp, Cyan, "volume_up"),
    Tile("Set 7:00 AM Alarm", "Morning Wakeup", Icons.Filled.Alarm, Pink, "set_alarm", """{"hour":7,"minute":0}"""),
    Tile("Lower Volume", "System Audio -", Icons.Filled.VolumeDown, Purple, "volume_down"),
    Tile("Open Camera", "Capture Photo", Icons.Filled.Videocam, Cyan, "open_camera"),
    Tile("Vibrate Phone", "Haptic Feedback", Icons.Filled.Vibration, Pink, "vibrate"),
    Tile("Calendar", "Schedule & Plans", Icons.Filled.CalendarMonth, Amber, "open_calendar"),
    Tile("Settings", "System Configuration", Icons.Filled.Search, Purple, "open_settings")
)

private val TIMER_TILES = listOf(
    Tile("1 Min", "", Icons.Filled.Timer, Cyan, "start_timer", """{"minutes":1}"""),
    Tile("5 Min", "", Icons.Filled.Timer, Pink, "start_timer", """{"minutes":5}"""),
    Tile("15 Min", "", Icons.Filled.Timer, Amber, "start_timer", """{"minutes":15}"""),
    Tile("30 Min", "", Icons.Filled.Timer, Purple, "start_timer", """{"minutes":30}""")
)

@Composable
fun ToolsScreen(app: MayaApplication) {
    val scope = rememberCoroutineScope()
    val prefs by app.settings.prefs.collectAsState(initial = null)
    val userName = prefs?.userName.orEmpty().ifBlank { "Boss" }

    fun runTile(tile: Tile) {
        scope.launch {
            val tool: Tool? = app.registry[tile.toolName]
            if (tool == null) {
                StateBus.setState(AssistantState.Error("Tool not available: ${tile.toolName}"))
                return@launch
            }
            StateBus.setState(AssistantState.ToolExecution(tile.toolName))
            val result = app.registry.execute(tile.toolName, tile.argsJson, conversationId = "default")
            val msg = when (result) {
                is com.amayra.maya.tools.ToolResult.Success -> result.summary
                is com.amayra.maya.tools.ToolResult.Failure -> "⚠️ ${result.error}"
                else -> "Done."
            }
            StateBus.setState(AssistantState.Idle)
            // Surface the outcome on the chat log so the user sees what happened.
            com.amayra.maya.core.MayaLog.i("TOOLS", "${tile.label}: $msg")
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        androidx.compose.foundation.layout.Spacer(Modifier.size(8.dp))
        SectionTitle("Assistant Apps & Communication")
        TileGrid(COMMUNICATION_TILES) { runTile(it) }

        SectionTitle("Quick Actions")
        TileGrid(QUICK_ACTION_TILES) { runTile(it) }

        SectionTitle("Timers & Countdowns")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            TIMER_TILES.forEach { t ->
                Box(
                    Modifier
                        .weight(1f)
                        .aspectRatio(0.95f)
                        .neonBorder(t.tint)
                        .clickable { runTile(t) },
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(t.icon, null, tint = t.tint, modifier = Modifier.size(26.dp))
                        Text(t.label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
        androidx.compose.foundation.layout.Spacer(Modifier.size(24.dp))
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
}

@Composable
private fun TileGrid(tiles: List<Tile>, onClick: (Tile) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        tiles.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { t ->
                    Column(
                        Modifier
                            .weight(1f)
                            .aspectRatio(1.55f)
                            .neonBorder(t.tint)
                            .clickable { onClick(t) }
                            .padding(14.dp)
                    ) {
                        Box(
                            Modifier
                                .size(38.dp)
                                .background(t.tint.copy(alpha = 0.18f), RoundedCornerShape(10.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(t.icon, null, tint = t.tint, modifier = Modifier.size(22.dp))
                        }
                        androidx.compose.foundation.layout.Spacer(Modifier.size(10.dp))
                        Text(t.label, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        if (t.subtitle.isNotBlank()) {
                            Text(t.subtitle, color = Color(0xFF9AA3B5), fontSize = 12.sp, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

private fun Modifier.neonBorder(tint: Color): Modifier = this
    .background(Color(0xFF141926), RoundedCornerShape(16.dp))
    .border(1.dp, tint.copy(alpha = 0.55f), RoundedCornerShape(16.dp))
