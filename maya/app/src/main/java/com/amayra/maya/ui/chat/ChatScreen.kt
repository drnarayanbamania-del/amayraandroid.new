package com.amayra.maya.ui.chat

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.amayra.maya.MayaApplication
import com.amayra.maya.readImageAsDataUrl
import com.amayra.maya.ai.ChatMessage
import com.amayra.maya.avatar.MayaAvatarView
import com.amayra.maya.core.AssistantState
import com.amayra.maya.core.MayaCognitiveCore
import com.amayra.maya.core.StateBus
import kotlinx.coroutines.launch

/**
 * Chat surface: avatar, live status chip, message history with polished
 * bubbles, tool-turn rendering, image attach, mic, confirm dialogs, and a
 * pill input bar with a prominent send action.
 */
@Composable
fun ChatScreen(app: MayaApplication) {
    val core = app.core
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val turns by core.turnsState.collectAsState()
    val state by StateBus.state.collectAsState()
    val emotion by StateBus.emotion.collectAsState()
    val pendingConfirm by core.pendingConfirm.collectAsState()
    val autoReply by core.autoReplyEvent.collectAsState(initial = null)
    var input by remember { mutableStateOf("") }

    val listState = rememberLazyListState()
    LaunchedEffect(turns.size) {
        if (turns.isNotEmpty()) listState.animateScrollToItem(turns.size - 1)
    }

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            val dataUrl: String? = app.readImageAsDataUrl(uri)
            if (dataUrl != null) scope.launch { core.onUserMessage("", dataUrl) }
        }
    }

    Surface(color = com.amayra.maya.ui.theme.Bg, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // Avatar + live status chip
            Column(
                Modifier.fillMaxWidth().padding(top = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                MayaAvatarView(controller = app.avatar, settings = app.settings, onTap = {
                    (context as? com.amayra.maya.MainActivity)?.requestMic()
                })
                Spacer(Modifier.height(6.dp))
                StatusChip(state)
            }

            // AI failure banner (quota/auth/network) — actionable, not just a log.
            LastErrorBanner(core = core)

            // Message list
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp)
            ) {
                items(turns) { msg -> MessageRow(msg) }
                autoReply?.let { (who, text) ->
                    item {
                        Surface(
                            color = com.amayra.maya.ui.theme.Ok.copy(alpha = 0.10f),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp)
                                .border(1.dp, com.amayra.maya.ui.theme.Ok.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                        ) {
                            Text(
                                "Auto-reply → $who: $text",
                                style = MaterialTheme.typography.bodyMedium,
                                color = com.amayra.maya.ui.theme.Ok,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                            )
                        }
                    }
                }
            }

            // Confirm dialog (risk gate)
            pendingConfirm?.let { pc ->
                AlertDialog(
                    onDismissRequest = { pc.answer(false) },
                    title = { Text(pc.title) },
                    text = { Text(pc.details) },
                    confirmButton = {
                        TextButton(onClick = { pc.answer(true) }) { Text("Allow") }
                    },
                    dismissButton = {
                        TextButton(onClick = { pc.answer(false) }) { Text("Deny") }
                    }
                )
            }

            // Input bar: pill field + circular actions
            Surface(
                color = com.amayra.maya.ui.theme.Surface,
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 10.dp)
                    .border(1.dp, com.amayra.maya.ui.theme.Stroke, RoundedCornerShape(24.dp))
            ) {
                Row(
                    Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = {
                        imagePicker.launch(androidx.activity.result.PickVisualMediaRequest(
                            ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }) {
                        Icon(
                            Icons.Filled.Add, contentDescription = "Attach image",
                            tint = com.amayra.maya.ui.theme.TextDim
                        )
                    }

                    androidx.compose.material3.OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.weight(1f),
                        placeholder = {
                            Text("Ask Maya…", color = com.amayra.maya.ui.theme.TextDim)
                        },
                        maxLines = 4,
                        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color.Transparent,
                            unfocusedBorderColor = Color.Transparent,
                            cursorColor = com.amayra.maya.ui.theme.Primary
                        )
                    )

                    if (state is AssistantState.Speaking) {
                        IconButton(onClick = { app.voice.stopSpeaking() }) {
                            Icon(
                                Icons.Filled.Stop, contentDescription = "Stop speaking",
                                tint = com.amayra.maya.ui.theme.Danger
                            )
                        }
                    } else {
                        IconButton(onClick = {
                            (context as? com.amayra.maya.MainActivity)?.requestMic()
                            app.voice.startListening()
                        }) {
                            Icon(
                                Icons.Filled.Mic, contentDescription = "Voice input",
                                tint = com.amayra.maya.ui.theme.Accent
                            )
                        }
                    }

                    androidx.compose.material3.FilledIconButton(
                        onClick = {
                            val text = input.trim()
                            if (text.isNotEmpty()) {
                                input = ""
                                scope.launch { core.onUserMessage(text) }
                            }
                        },
                        modifier = Modifier.size(44.dp),
                        colors = androidx.compose.material3.IconButtonDefaults.filledIconButtonColors(
                            containerColor = com.amayra.maya.ui.theme.Primary,
                            contentColor = Color.White
                        )
                    ) {
                        Icon(Icons.Filled.Send, contentDescription = "Send", modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}

/** Live status pill under the avatar: colored dot + label. */
@Composable
private fun StatusChip(state: AssistantState) {
    val (label, color, pulsing) = when (state) {
        is AssistantState.Idle -> Triple("Maya · ready", com.amayra.maya.ui.theme.TextDim, false)
        is AssistantState.Listening ->
            if (state.partial.isBlank()) Triple("Listening…", com.amayra.maya.ui.theme.Accent, true)
            else Triple("Heard: ${state.partial}", com.amayra.maya.ui.theme.Accent, false)
        is AssistantState.Processing -> Triple("Thinking…", com.amayra.maya.ui.theme.Warn, true)
        is AssistantState.ToolExecution -> Triple("Running ${state.tool}…", com.amayra.maya.ui.theme.Warn, true)
        is AssistantState.Responding -> Triple("Responding…", com.amayra.maya.ui.theme.Primary, true)
        is AssistantState.Speaking -> Triple("Speaking…", com.amayra.maya.ui.theme.Primary, true)
        is AssistantState.Sleeping -> Triple("Sleeping", com.amayra.maya.ui.theme.TextDim, false)
        is AssistantState.Error -> Triple(state.message, com.amayra.maya.ui.theme.Danger, false)
    }
    Surface(
        color = com.amayra.maya.ui.theme.Surface,
        shape = RoundedCornerShape(50),
        modifier = Modifier.border(1.dp, com.amayra.maya.ui.theme.Stroke, RoundedCornerShape(50))
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                Modifier
                    .size(if (pulsing) 8.dp else 7.dp)
                    .clip(CircleShape)
                    .background(color)
            )
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = if (state is AssistantState.Error) com.amayra.maya.ui.theme.Danger
                        else com.amayra.maya.ui.theme.TextMain,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun MessageRow(msg: ChatMessage) {
    val isUser = msg.role == "user"
    val isTool = msg.role == "tool"
    if (msg.role == "assistant" && msg.content.isBlank() && msg.toolName != null) {
        ToolChip(msg.toolName!!, msg.toolArgsJson)
        return
    }
    if (isTool) {
        ToolChip(msg.toolName ?: "tool", null, result = msg.content)
        return
    }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Box(
            Modifier
                .widthIn(max = 300.dp)
                .clip(
                    // Chat-style asymmetric corners: tail side squared off.
                    RoundedCornerShape(
                        topStart = 16.dp, topEnd = 16.dp,
                        bottomEnd = if (isUser) 4.dp else 16.dp,
                        bottomStart = if (isUser) 16.dp else 4.dp
                    )
                )
                .background(
                    when {
                        isUser -> com.amayra.maya.ui.theme.PrimaryDim
                        msg.content.startsWith("⚠️") -> com.amayra.maya.ui.theme.Danger.copy(alpha = 0.15f)
                        else -> com.amayra.maya.ui.theme.SurfaceHigh
                    }
                )
                .border(
                    1.dp,
                    if (isUser) Color.Transparent else com.amayra.maya.ui.theme.Stroke,
                    RoundedCornerShape(
                        topStart = 16.dp, topEnd = 16.dp,
                        bottomEnd = if (isUser) 4.dp else 16.dp,
                        bottomStart = if (isUser) 16.dp else 4.dp
                    )
                )
                .padding(horizontal = 12.dp, vertical = 9.dp)
        ) {
            SelectionContainer {
                Column {
                    if (msg.imageUri != null) {
                        // Render the actual image when it's a local file (e.g. pc_screenshot);
                        // otherwise keep the compact "attached" label.
                        val file = java.io.File(msg.imageUri)
                        if (file.exists()) {
                            val bitmap = remember(msg.imageUri) {
                                runCatching {
                                    val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                    android.graphics.BitmapFactory.decodeFile(msg.imageUri, opts)
                                    val sample = maxOf(1, minOf(opts.outWidth, opts.outHeight) / 1024)
                                    val o2 = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
                                    android.graphics.BitmapFactory.decodeFile(msg.imageUri, o2)
                                }.getOrNull()
                            }
                            if (bitmap != null) {
                                Image(
                                    bitmap = bitmap.asImageBitmap(),
                                    contentDescription = "Attached image",
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 320.dp)
                                        .padding(bottom = 6.dp)
                                        .clip(RoundedCornerShape(10.dp)),
                                    contentScale = androidx.compose.ui.layout.ContentScale.FillWidth
                                )
                            } else {
                                Text("🖼 [image attached]", style = MaterialTheme.typography.labelMedium)
                                Spacer(Modifier.height(4.dp))
                            }
                        } else {
                            Text("🖼 [image attached]", style = MaterialTheme.typography.labelMedium)
                            Spacer(Modifier.height(4.dp))
                        }
                    }
                    Text(msg.content.ifBlank { "…" }, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

@Composable
private fun ToolChip(name: String, argsJson: String?, result: String? = null) {
    Surface(
        color = com.amayra.maya.ui.theme.Surface,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .padding(vertical = 2.dp)
            .border(1.dp, com.amayra.maya.ui.theme.Stroke, RoundedCornerShape(10.dp))
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("🔧", style = MaterialTheme.typography.labelMedium)
            Column {
                Text(
                    if (result != null) "$name →" else name,
                    style = MaterialTheme.typography.labelMedium,
                    color = com.amayra.maya.ui.theme.Accent
                )
                result?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = com.amayra.maya.ui.theme.TextDim,
                        maxLines = 4
                    )
                }
            }
        }
    }
}

private fun stateLabel(s: AssistantState): String = when (s) {
    is AssistantState.Idle -> "Maya · ready"
    is AssistantState.Listening -> if (s.partial.isBlank()) "Listening…" else "Heard: ${s.partial}"
    is AssistantState.Processing -> "Thinking…"
    is AssistantState.ToolExecution -> "Running ${s.tool}…"
    is AssistantState.Responding -> "Responding…"
    is AssistantState.Speaking -> "Speaking…"
    is AssistantState.Sleeping -> "Sleeping"
    is AssistantState.Error -> s.message
}
