package com.amayra.maya.core

import android.content.Context
import com.amayra.maya.ai.AiClient
import com.amayra.maya.ai.AiError
import com.amayra.maya.ai.ChatMessage
import com.amayra.maya.data.MayaDatabaseHolder
import com.amayra.maya.data.MemoryEntity
import com.amayra.maya.data.MessageEntity
import com.amayra.maya.events.EventBus
import com.amayra.maya.events.EventSource
import com.amayra.maya.events.EventType
import com.amayra.maya.events.MayaaEvent
import com.amayra.maya.voice.VoiceController
import com.amayra.maya.integration.WhatsAppAutoReplyEngine
import com.amayra.maya.integration.WhatsAppManager
import com.amayra.maya.memory.SecureStore
import com.amayra.maya.settings.SettingsRepository
import com.amayra.maya.settings.UserPreferences
import com.amayra.maya.tools.ToolRegistry
import com.amayra.maya.tools.ToolResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * The brain. Owns the assistant state machine and the turn loop:
 * user input -> memory context -> AI stream -> tool loop -> response -> voice/avatar.
 */
class MayaCognitiveCore(
    val appContext: Context,
    val settings: SettingsRepository,
    val db: MayaDatabaseHolder,
    val secure: SecureStore,
    val bus: EventBus,
    val registry: ToolRegistry,
    val ai: AiClient,
    val voice: VoiceController,
    val avatar: com.amayra.maya.avatar.AvatarController
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }

    /** Conversation turns kept in memory; persisted on each turn. */
    private val turns = MutableStateFlow<List<ChatMessage>>(emptyList())
    val turnsState = turns

    /** Pending tool confirmations surfaced to the UI. */
    data class PendingConfirm(val id: Int, val title: String, val details: String, val answer: (Boolean) -> Unit)
    val pendingConfirm = MutableStateFlow<PendingConfirm?>(null)

    /** Emitted when auto-reply generated a reply (for chat display / logs). */
    val autoReplyEvent = MutableSharedFlow<Pair<String, String>>(extraBufferCapacity = 8)

    var conversationId: String = "default"
    private var turnSeq = 0

    /** Wire the WhatsApp auto-reply engine (constructed after DI wiring). */
    lateinit var autoReplyEngine: WhatsAppAutoReplyEngine

    fun start() {
        settings.startCaching(scope)
        scope.launch {
            bus.events.collect { ev -> onEvent(ev) }
        }
        scope.launch {
            // Wake-word signal: start listening immediately.
            com.amayra.maya.core.StateBus.wakeSignal.collect {
                if (settings.prefsCache?.wakeWordEnabled != false) {
                    StateBus.setState(AssistantState.Listening())
                    voice.startListening()
                }
            }
        }
        scope.launch {
            // Guardian acceptance announces presence (Diagnostics shows history).
            com.amayra.maya.core.StateBus.guardianSignal.collect { sig ->
                if (sig.accepted) MayaLog.i("CORE", "Guardian: ${sig.label} verified (%.2f)".format(sig.probability))
            }
        }
        scope.launch {
            val last = db.messageDao.latest(1).firstOrNull()
            if (last != null) {
                conversationId = last.conversationId
                turns.value = db.messageDao.forConversation(conversationId).map {
                    ChatMessage(
                        role = it.role,
                        content = it.content,
                        imageUri = it.attachmentUri,
                        toolCallId = it.toolCallId,
                        toolName = it.toolName,
                        toolArgsJson = it.toolArgsJson,
                        thoughtSignature = it.thoughtSignature
                    )
                }
            }
        }
        scope.launch {
            val p = settings.prefs.first()
            // First-run gate: never talk over the setup screen.
            if (p.setupComplete && p.proactiveGreeting) speakProactive(greetingFor(p))
        }
        MayaLog.i("CORE", "Cognitive core started")
    }

    private suspend fun greetingFor(p: UserPreferences): String {
        val name = p.userName.ifBlank { "there" }
        return "Hey $name! Amayra here. What are we doing today?"
    }

    /** Handle events from notification listener, automation, SOS, etc. */
    private suspend fun onEvent(ev: MayaaEvent) {
        when (ev.type) {
            EventType.NOTIFICATION -> handleNotification(ev)
            EventType.SOS -> handleSos(ev)
            EventType.MACRO -> {}
            EventType.STANDBY -> {}
            else -> {}
        }
    }

    private suspend fun handleNotification(ev: MayaaEvent) {
        // WhatsApp auto-reply path (allowlist/quiet-hours/cap enforced inside the engine).
        if (ev.packageName?.startsWith("com.whatsapp") == true && this::autoReplyEngine.isInitialized) {
            val contact = ev.title ?: return
            val text = ev.text ?: return
            val reply = autoReplyEngine.onIncoming(appContext, contact, text)
            if (reply != null) {
                autoReplyEvent.emit("WA:$contact" to reply)
            }
        }
    }

    private suspend fun handleSos(ev: MayaaEvent) {
        val number = settings.prefsCache?.sosContactNumber
        if (number.isNullOrBlank()) {
            MayaLog.w("CORE", "SOS triggered but no emergency contact configured")
            return
        }
        // Open the dialer pre-filled — never silently place calls.
        try {
            val intent = android.content.Intent(android.content.Intent.ACTION_DIAL, android.net.Uri.parse("tel:$number"))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            appContext.startActivity(intent)
            MayaLog.i("CORE", "SOS: opened dialer to emergency contact")
        } catch (t: Throwable) {
            MayaLog.e("CORE", "SOS dialer failed", t)
        }
        // Siren via TTS at max volume
        voice.speak("Emergency! Sending alert to your emergency contact.")
    }

    /** Entry point for all user messages (text or transcript). */
    suspend fun onUserMessage(text: String, imageUri: String? = null) {
        if (text.isBlank() && imageUri == null) return
        // Deterministic pre-model shortcut: "open X" commands execute the
        // open_app tool directly — the model (esp. Groq) sometimes answers in
        // text without emitting the tool call. Other intents are untouched.
        OpenAppIntent.match(text)?.let { m ->
            StateBus.setState(AssistantState.Processing)
            val result = registry.execute("open_app", """{"app_name":"${m.appName}"}""", conversationId)
            val spoken = when (result) {
                is com.amayra.maya.tools.ToolResult.Success -> result.summary
                else -> "${m.appName} nahi khul paaya."
            }
            turns.value += ChatMessage(role = "user", content = text)
            turns.value += ChatMessage(role = "assistant", content = spoken)
            voice.speak(spoken)
            StateBus.setState(AssistantState.Idle)
            return
        }
        StateBus.setState(AssistantState.Processing)
        StateBus.setEmotion(Emotion.NEUTRAL)
        // Await the real settings flow: prefsCache may be unset right after process
        // death (collector not resumed yet), and a cache read here hard-failed turns.
        val p = settings.prefs.first()

        // Persist user turn
        db.messageDao.insert(
            MessageEntity(conversationId = conversationId, role = "user", content = text, attachmentUri = imageUri)
        )
        turns.value += ChatMessage(role = "user", content = text, imageUri = imageUri)

        // Memory context (top pinned + recent, bounded)
        val memoryBlock = buildMemoryBlock()

        // Persona overlay: active persona's prompt replaces the default intro
        val persona = com.amayra.maya.AppGraph.personas.active.value
        val personaBlock = "\n\n[Active persona: ${persona.displayName}. ${persona.systemPrompt}]"

        try {
            var finalText = ""
            var guard = 0
            var needsAnotherRound = true
            var toolRounds = 0
            while (needsAnotherRound && guard < 3) {
                guard++
                needsAnotherRound = false

                val msgs = buildList {
                    add(ChatMessage(role = "system", content = p.systemPrompt + personaBlock + memoryBlock))
                    addAll(turns.value.takeLast(20))
                }

                val streamBuf = StringBuilder() // fresh per round: text of THIS round only
                val toolCalls = mutableListOf<com.amayra.maya.ai.ToolCall>()

                val error = ai.streamChat(
                    messages = msgs,
                    tools = registry.specs(),
                    onDeltas = { d ->
                        streamBuf.append(d)
                        StateBus.setState(AssistantState.Responding)
                    },
                    onToolCall = { call -> toolCalls.add(call) }
                )

                if (error != null) {
                    StateBus.setState(AssistantState.Error(humanMessage(error)))
                    StateBus.setEmotion(Emotion.SAD)
                    // Persist an assistant error turn so the user sees it in history.
                    db.messageDao.insert(MessageEntity(conversationId = conversationId, role = "user", content = "⚠️ ${error.message}"))
                    turns.value += ChatMessage(role = "assistant", content = "⚠️ ${error.message}")
                    return
                }

                if (toolCalls.isNotEmpty()) {
                    needsAnotherRound = true
                    toolRounds++
                    // Preserve any text the model produced alongside its tool calls
                    // exactly once per round (non-blank only: Gemini rejects empty parts).
                    if (streamBuf.isNotBlank()) {
                        // UI only: persisting this intermediate text would replay it
                        // as a model turn between user and functionCall — Gemini 400s.
                        val roundText = streamBuf.toString()
                        turns.value += ChatMessage(role = "assistant", content = roundText)
                    }
                    // Replay each tool call + result so providers can continue the
                    // conversation (Gemini functionCall/functionResponse round-trip).
                    for (call in toolCalls) {
                        StateBus.setState(AssistantState.ToolExecution(call.name))
                        turns.value += ChatMessage(
                            role = "assistant",
                            content = "",
                            toolCallId = call.id,
                            toolName = call.name,
                            toolArgsJson = call.argumentsJson,
                            thoughtSignature = call.thoughtSignature
                        )
                        // Persist the tool-call turn too, so the functionCall is
                        // replayable after process death (its result is persisted below).
                        db.messageDao.insert(
                            MessageEntity(
                                conversationId = conversationId,
                                role = "assistant",
                                content = "",
                                toolName = call.name,
                                toolCallId = call.id,
                                toolArgsJson = call.argumentsJson,
                                thoughtSignature = call.thoughtSignature
                            )
                        )
                        val result = registry.execute(call.name, call.argumentsJson, conversationId)
                        var resultText = when (result) {
                            is ToolResult.Success -> result.summary
                            is ToolResult.Failure -> "Error: ${result.error}"
                            is ToolResult.ConfirmRequired -> "Confirmation needed: ${result.reason}"
                        }
                        // Image-bearing tool results (e.g. pc_screenshot): attach the
                        // saved image to the tool turn so the model can SEE it and the
                        // chat UI renders it inline. The marker lives in dataJson.
                        var toolImageUri: String? = null
                        if (result is ToolResult.Success && result.dataJson?.startsWith("PC_SCREENSHOT:") == true) {
                            val path = result.dataJson.removePrefix("PC_SCREENSHOT:")
                            toolImageUri = path
                            resultText += "\n[Screenshot attached below.]"
                        }
                        turns.value += ChatMessage(
                            role = "tool",
                            content = resultText,
                            toolCallId = call.id,
                            toolName = call.name,
                            imageUri = toolImageUri
                        )
                        db.messageDao.insert(MessageEntity(conversationId = conversationId, role = "tool", content = resultText.take(2000), attachmentUri = toolImageUri))
                    }
                } else {
                    finalText = streamBuf.toString()
                }
            }

            if (finalText.isBlank()) {
                // A tool round legitimately ended without prose (e.g. the tool's
                // result WAS the answer). Don't fabricate "Done." — say what happened.
                finalText = if (toolRounds > 0) "Ho gaya ✅"
                            else "Done."
            }
            turns.value += ChatMessage(role = "assistant", content = finalText)
            db.messageDao.insert(MessageEntity(conversationId = conversationId, role = "assistant", content = finalText))
            StateBus.setEmotion(Emotion.HAPPY)
            val p2 = settings.prefsCache
            if (p2?.autoSpeak == true) {
                // VoiceController owns the SPEAKING -> IDLE transition (TTS lifecycle);
                // setting it here too raced the avatar's speaking animation.
                voice.speak(stripMarkdown(finalText))
            } else {
                StateBus.setState(AssistantState.Idle)
            }
        } catch (t: Throwable) {
            MayaLog.e("CORE", "Turn failed", t)
            StateBus.setState(AssistantState.Error("Something went wrong: ${t.message}"))
        }
    }

    private suspend fun buildMemoryBlock(): String {
        val memories = runCatching { db.memoryDao.all() }.getOrNull().orEmpty()
        if (memories.isEmpty()) return ""
        val top = memories.sortedWith(compareByDescending<MemoryEntity> { it.pinned }.thenByDescending { it.importance })
            .take(12)
            .joinToString("\n") { "- (${it.kind}) ${it.content}" }
        return "\n\n[Long-term memory]\n$top"
    }

    private fun humanMessage(e: AiError): String = when (e) {
        is AiError.Authentication -> e.message ?: "Invalid or missing API key."
        is AiError.RateLimit -> "Rate limit reached. Try again in a moment."
        is AiError.Network -> "Network unreachable — check your connection."
        is AiError.Timeout -> "The AI took too long to respond."
        is AiError.Provider -> e.message ?: "AI provider error."
        is AiError.Unsupported -> "This model doesn't support that request."
    }


    fun stripMarkdown(s: String): String =
        s.replace(Regex("```[\\s\\S]*?```"), " code block ").replace(Regex("[*_#`>]"), "").take(400)

    private fun speakProactive(line: String) {
        scope.launch {
            voice.speak(line)
            bus.publish(MayaaEvent(type = EventType.SYSTEM, source = EventSource.AUTOMATION, text = line))
        }
    }

    /** Stop everything (SLEEP action). */
    fun sleep() {
        voice.stopAll()
        StateBus.setState(AssistantState.Sleeping)
    }

    fun wake() {
        StateBus.setState(AssistantState.Idle)
    }

    companion object {
        private const val TAG = "CognitiveCore"
    }
}
