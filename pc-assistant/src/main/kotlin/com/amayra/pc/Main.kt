package com.amayra.pc

import com.amayra.pc.ai.GeminiClient
import com.amayra.pc.brain.AmayraBrain
import com.amayra.pc.core.*
import com.amayra.pc.relay.PcRelayServer
import com.amayra.pc.tools.*
import com.amayra.pc.ui.StatusWindow
import com.amayra.pc.voice.VoiceController
import kotlinx.coroutines.*

/**
 * Amayra PC assistant — text-first entry point (voice milestone comes next).
 *
 * Usage: gradlew :pc-assistant:run
 * Requires GEMINI_API_KEY (env var) or config/config.json.
 */
suspend fun main(args: Array<String>) {
    PcLog.i("AMAYRA", "Amayra PC assistant starting…")
    if (System.getProperty("os.name").lowercase().contains("windows")) {
        // Robot needs a non-headless environment; keep the JVM honest about it.
        System.setProperty("java.awt.headless", "false")
    }

    val config = PcConfig.load()
    if (config.geminiApiKey.isBlank()) {
        PcLog.w("AMAYRA", "No Gemini API key found. Set GEMINI_API_KEY or create config/config.json with {\"geminiApiKey\":\"...\"}")
    }

    // Confirmation gate: terminal prompt (UI comes later).
    val registry = ToolRegistry().apply {
        AppTools().registerAll(this)
        InputTools().registerAll(this)
        VisionTools().registerAll(this)
        MemoryTools().registerAll(this)
    }
    val brain = AmayraBrain(
        client = GeminiClient(keyProvider = { config.geminiApiKey.ifBlank { null } }),
        registry = registry,
        config = config,
        confirmation = { title, details ->
            println("\n⚠ CONFIRMATION NEEDED: $title")
            println(details)
            print("Allow? [y/N] ")
            readlnOrNull()?.trim()?.lowercase() in setOf("y", "yes")
        }
    )

    // Relay server for the Android tablet (PING / SEND_COMMAND).
    val relay = PcRelayServer(brain, config.relayPort)
    try { relay.start() } catch (t: Throwable) {
        PcLog.w("RELAY", "Relay could not bind :${config.relayPort} (${t.message}) — continuing without it.")
    }

    // Always-on-top status window (state / last tool / relay connections).
    StatusWindow(brain, registry, relay, config.relayPort, config.model).show()

    // Voice pipeline (Android VoiceController pattern): STT → brain → TTS,
    // with the same speak/listen arbiter. Enabled with /voice; graceful when
    // the Vosk model or mic is missing.
    val voiceRef = java.util.concurrent.atomic.AtomicReference<VoiceController?>(null)
    val voice = VoiceController(
        onFinalTranscript = { text ->
            val reply = brain.handleUserInput(text)
            println("\nAmayra ▸ $reply")
            voiceRef.get()?.speak(reply)
        },
        geminiApiKeyProvider = { config.geminiApiKey.ifBlank { null } },
        voskModelPath = listOf("pc-assistant/models/vosk-en", "models/vosk-en")
            .firstOrNull { java.io.File(it, "am/final.mdl").exists() || java.io.File(it, "final.mdl").exists() }
            ?: "models/vosk-en"
    )
    voiceRef.set(voice)

    println("""
        ┌─────────────────────────────────────────────┐
        │  Amayra PC assistant — text console (v0.2)  │
        │  Type commands; /exit to quit, /state for   │
        │  the state machine, /memory to recall,      │
        │  /voice to toggle speech mode, /mute to     │
        │  silence TTS.                               │
        └─────────────────────────────────────────────┘
    """.trimIndent())

    while (true) {
        print("\nYou ▸ ")
        val input = readlnOrNull() ?: break
        when (input.trim()) {
            "" -> continue
            "/exit", "/quit" -> break
            "/state" -> { println("State: ${StateBus.state.value.label}"); continue }
            "/memory" -> {
                MemoryStore.recall(limit = 20).forEach { println("  [${it.category}] ${it.content}") }
                continue
            }
            "/reset" -> { brain.resetConversation(); println("Conversation cleared."); continue }
            "/voice" -> {
                if (!voice.sttAvailable()) {
                    println("STT model missing — download a Vosk small English model\n(https://alphacephei.com/vosk/models, vosk-model-small-en-us-0.15)\nand extract it to models/vosk-en/ next to the program.")
                } else {
                    println("🎙 Listening — speak now (state: ${StateBus.state.value.label}). Type anything + Enter to cancel.")
                    voice.startListening()
                }
                continue
            }
            "/mute" -> { voice.enginePreference = if (voice.enginePreference == "muted") "gemini" else "muted"
                println("TTS ${if (voice.enginePreference == "muted") "muted" else "on"}."); continue }
            else -> {}
        }
        val reply = brain.handleUserInput(input)
        println("\nAmayra ▸ $reply")
    }

    relay.stop()
    PcLog.i("AMAYRA", "Goodbye.")
}
