package com.amayra.maya.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amayra.maya.MayaApplication
import com.amayra.maya.core.AssistantState
import com.amayra.maya.core.Emotion
import com.amayra.maya.core.StateBus
import kotlinx.coroutines.launch

/**
 * Myra-style home tab. The avatar stage is the hero (~70% of screen height);
 * every control is compact and pro-styled so the companion dominates the view.
 */
private val Card = Color(0xFF151A26)
private val Dim = Color(0xFF8A93A8)
private val Pink = Color(0xFFFF5FA2)
private val Purple = Color(0xFFB46BFF)
private val Cyan = Color(0xFF00E5FF)

private val LANGUAGES = listOf("हिंदी" to "hindi", "Hinglish" to "hinglish", "English" to "english", "Auto" to "auto")
private val SENTIMENTS = listOf("Sweet & Happy" to "sweet", "Fierce & Loyal" to "fierce", "Calm & Wise" to "calm")

@Composable
fun MyraHomeScreen(app: MayaApplication) {
    val scope = rememberCoroutineScope()
    val state by StateBus.state.collectAsState()
    val emotion by StateBus.emotion.collectAsState()
    val prefs by app.settings.prefs.collectAsState(initial = null)
    var language by remember { mutableStateOf("auto") }
    var sentiment by remember { mutableStateOf("sweet") }
    var bond by remember { mutableStateOf(20) }   // EXP; grows with each turn
    var input by remember { mutableStateOf("") }

    val speaking = state is AssistantState.Speaking
    val greeting = when {
        emotion == Emotion.HAPPY -> "Good to see you, Boss! ✨"
        else -> "Hello Boss! Main ready hoon — bolo, kya karna hai? 🌸"
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0E14))
            .padding(horizontal = 10.dp)
    ) {
        Spacer(Modifier.height(6.dp))

        // ── Compact header: identity + language chips + bond, one strip ──
        Row(verticalAlignment = Alignment.CenterVertically) {            Box(
                Modifier
                    .size(34.dp)
                    .background(Color(0xFF0D1019), RoundedCornerShape(10.dp))
                    .border(1.dp, Pink.copy(alpha = 0.5f), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.foundation.Image(
                    painter = androidx.compose.ui.res.painterResource(com.amayra.maya.R.drawable.amayra_logo),
                    contentDescription = "Amayra logo",
                    modifier = Modifier.size(30.dp),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop
                )
            }
            Spacer(Modifier.width(8.dp))
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("AMAYRA", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(4.dp))
                    Box(Modifier.size(5.dp).background(Color(0xFF4CE0A6), CircleShape))
                }
                Text("Sweet Heroine", color = Pink, fontSize = 9.sp)
            }
            Spacer(Modifier.width(10.dp))
            // Bond mini-bar (inline, no separate card)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Favorite, null, tint = Pink, modifier = Modifier.size(10.dp))
                Spacer(Modifier.width(3.dp))
                Box(
                    Modifier
                        .width(56.dp)
                        .height(3.dp)
                        .background(Card, RoundedCornerShape(999.dp))
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(fraction = (bond % 100) / 100f)
                            .height(3.dp)
                            .background(Brush.horizontalGradient(listOf(Pink, Purple)), RoundedCornerShape(999.dp))
                    )
                }
                Spacer(Modifier.width(3.dp))
                Text("L${bond / 100 + 1}", color = Dim, fontSize = 9.sp)
            }
            Spacer(Modifier.weight(1f))
            // Language chips row (compact, right-aligned)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                LANGUAGES.forEach { (label, id) ->
                    val selected = language == id
                    Box(
                        Modifier
                            .background(if (selected) Pink.copy(alpha = 0.18f) else Card, RoundedCornerShape(999.dp))
                            .border(1.dp, if (selected) Pink else Pink.copy(alpha = 0.30f), RoundedCornerShape(999.dp))
                            .clickable { language = id }
                            .padding(horizontal = 7.dp, vertical = 3.dp)
                    ) {
                        Text(label, color = if (selected) Pink else Dim, fontSize = 9.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))

        // ── HERO: avatar stage fills ~70% of the screen ────────────────────
        Box(
            Modifier
                .fillMaxWidth()
                .weight(0.70f)
                .background(Brush.verticalGradient(listOf(Color(0xFF0D1019), Color(0xFF120B1C), Color(0xFF0D1019))), RoundedCornerShape(18.dp)),
            contentAlignment = Alignment.Center
        ) {
            com.amayra.maya.avatar.MayaAvatarView(
                controller = app.avatar,
                settings = app.settings,
                sizeOverride = 360.dp,
                onTap = {
                    bond = (bond + 5).coerceAtMost(999)
                    app.voice.speak(petReaction(sentiment))
                    StateBus.setEmotion(Emotion.HAPPY)
                }
            )
            // Status word floats at the stage bottom.
            Text(
                text = stateLabel(state),
                color = if (speaking) Cyan else Dim,
                fontSize = 10.sp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 8.dp)
                    .background(Color(0xE6121722), RoundedCornerShape(999.dp))
                    .padding(horizontal = 10.dp, vertical = 3.dp)
            )
        }
        Spacer(Modifier.height(6.dp))

        // ── Compact control strip: sentiment + pet ─────────────────────────
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .background(Card, RoundedCornerShape(999.dp))
                    .border(1.dp, Pink.copy(alpha = 0.4f), RoundedCornerShape(999.dp))
                    .clickable { sentiment = nextSentiment(sentiment) }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text("${sentimentLabel(sentiment)} ▾", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            }
            Box(
                Modifier
                    .background(Pink.copy(alpha = 0.15f), RoundedCornerShape(999.dp))
                    .border(1.dp, Pink, RoundedCornerShape(999.dp))
                    .clickable {
                        bond = (bond + 5).coerceAtMost(999)
                        app.voice.speak(petReaction(sentiment))
                        StateBus.setEmotion(Emotion.HAPPY)
                    }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text("💖 Pet Amayra", color = Pink, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.weight(1f))
            // Wake toggle (compact)
            val wakeOn = prefs?.wakeWordEnabled == true
            Box(
                Modifier
                    .background(if (wakeOn) Cyan.copy(alpha = 0.15f) else Card, RoundedCornerShape(999.dp))
                    .border(1.dp, if (wakeOn) Cyan else Dim.copy(alpha = 0.4f), RoundedCornerShape(999.dp))
                    .clickable {
                        scope.launch {
                            app.settings.update {
                                it[androidx.datastore.preferences.core.booleanPreferencesKey("wake_word_enabled")] = !wakeOn
                            }
                        }
                    }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(if (wakeOn) "⚡ Wake ON" else "Wake OFF", color = if (wakeOn) Cyan else Dim, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(Modifier.height(6.dp))

        // ── Mic permission quick-fix: appears only when RECORD_AUDIO is missing ──
        val ctx = androidx.compose.ui.platform.LocalContext.current
        var micGranted by remember {
            mutableStateOf(
                androidx.core.content.ContextCompat.checkSelfPermission(
                    ctx, android.Manifest.permission.RECORD_AUDIO
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            )
        }
        androidx.compose.runtime.LaunchedEffect(Unit) {
            if (!micGranted) {
                // Keep watching — flip the moment the grant dialog returns.
                kotlinx.coroutines.delay(500)
                micGranted = androidx.core.content.ContextCompat.checkSelfPermission(
                    ctx, android.Manifest.permission.RECORD_AUDIO
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            }
        }
        if (!micGranted) {
            val activity = ctx as? com.amayra.maya.MainActivity
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF3A1520), RoundedCornerShape(12.dp))
                    .border(1.dp, Color(0xFFFF3B5C).copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("🎤", fontSize = 14.sp)
                Spacer(Modifier.width(8.dp))
                Text(
                    "Mic permission missing — voice won't work",
                    color = Color(0xFFFFB4C0), fontSize = 10.sp,
                    modifier = Modifier.weight(1f)
                )
                Box(
                    Modifier
                        .background(Color(0xFFFF3B5C), RoundedCornerShape(999.dp))
                        .clickable {
                            activity?.requestMic()
                            micGranted = androidx.core.content.ContextCompat.checkSelfPermission(
                                ctx, android.Manifest.permission.RECORD_AUDIO
                            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                        }
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Text("Allow mic", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(Modifier.height(6.dp))
        }

        // ── Live partial-speech: what Amayra hears while listening ─────────
        val listeningPartial = (state as? AssistantState.Listening)?.partial.orEmpty()
        if (state is AssistantState.Listening) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Cyan.copy(alpha = 0.10f), RoundedCornerShape(12.dp))
                    .border(1.dp, Cyan.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (listeningPartial.isBlank()) "🎙 Listening… speak now" else "\"$listeningPartial\"",
                    color = if (listeningPartial.isBlank()) Dim else Cyan,
                    fontSize = 11.sp,
                    fontStyle = if (listeningPartial.isBlank()) androidx.compose.ui.text.font.FontStyle.Italic else androidx.compose.ui.text.font.FontStyle.Normal,
                    modifier = Modifier.weight(1f)
                )
                // Live equalizer dots while speech is coming in.
                if (listeningPartial.isNotBlank()) {
                    repeat(3) { i ->
                        Box(
                            Modifier
                                .padding(start = 3.dp)
                                .size(5.dp)
                                .background(Cyan, CircleShape)
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
        }

        // ── Talk input (always visible at bottom) ──────────────────────────
        Row(
            Modifier
                .fillMaxWidth()
                .background(Card, RoundedCornerShape(999.dp))
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(32.dp)
                    .background(Cyan, CircleShape)
                    .clickable { app.voice.startListening() },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Mic, "Voice input", tint = Color(0xFF0B0E14), modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.width(8.dp))
            BasicTextField(
                value = input,
                onValueChange = { input = it },
                textStyle = TextStyle(color = Color.White, fontSize = 12.sp),
                decorationBox = { inner ->
                    Box { if (input.isEmpty()) Text("Talk with Amayra...", color = Dim, fontSize = 12.sp); inner() }
                },
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(6.dp))
            Box(
                Modifier
                    .size(28.dp)
                    .clickable {
                        if (input.isNotBlank()) {
                            val msg = input; input = ""
                            scope.launch { app.core.onUserMessage(msg) }
                            bond = (bond + 2).coerceAtMost(999)
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Send, "Send", tint = if (input.isNotBlank()) Pink else Dim, modifier = Modifier.size(16.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

private fun stateLabel(s: AssistantState) = when (s) {
    is AssistantState.Listening -> "Listening..."
    is AssistantState.Processing -> "Thinking..."
    is AssistantState.Speaking -> "Speaking"
    is AssistantState.ToolExecution -> "Working: ${s.tool}"
    is AssistantState.Error -> "Something went wrong"
    is AssistantState.Sleeping -> "Sleeping"
    else -> "Online"
}

private fun nextSentiment(current: String) = when (current) {
    "sweet" -> "fierce"; "fierce" -> "calm"; else -> "sweet"
}

private fun sentimentLabel(id: String) = when (id) {
    "fierce" -> "Fierce & Loyal"
    "calm" -> "Calm & Wise"
    else -> "Sweet & Happy"
}

private fun petReaction(sentiment: String) = when (sentiment) {
    "fierce" -> "Hehe, aa gaya line pe? Good. Main hoon na, Boss."
    "calm" -> "Shukriya, Boss. Aapke saath sab shaant aur theek lagta hai."
    else -> "Aww, thank you Boss! Aapka khayal rakhti hoon hamesha~ 🌸"
}
