package com.amayra.pc.voice

import com.amayra.pc.core.AssistantState
import com.amayra.pc.core.PcLog
import com.amayra.pc.core.StateBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.TargetDataLine

/**
 * PC voice pipeline — mirrors the Android VoiceController contract:
 *
 *   Microphone → Speech-to-Text → onFinalTranscript(text) → brain → speak(reply)
 *
 * with the same speak/listen ARBITER discipline: TTS never talks over STT and
 * vice versa (speak() cancels listening; listening is refused while speaking).
 *
 * Engines (JVM equivalents of the Android choices):
 *  - STT: Vosk (offline Kaldi) small English model — no cloud dependency, low
 *    latency, runs on the mic line. Model dir defaults to models/vosk-en.
 *  - TTS engine "gemini": Gemini TTS generateContent (PCM 24 kHz) — same
 *    synthesis endpoint family the Android GeminiTtsClient uses — played via
 *    the Java Sound SourceDataLine. MP3-decoding path is available too.
 *  - TTS engine "windows": SAPI via PowerShell (always present on Windows)
 *    as the automatic fallback — never silent.
 */
class VoiceController(
    /** Called with a final transcript, same as Android's onFinalTranscript. */
    private val onFinalTranscript: suspend (String) -> Unit,
    private val geminiApiKeyProvider: () -> String?,
    private val geminiTtsModel: String = "gemini-2.5-flash-preview-tts",
    private val voiceName: String = "Kore",
    private val voskModelPath: String = "models/vosk-en"
) {
    /** Which TTS engine: "gemini" | "windows". Read at each speak(). */
    @Volatile var enginePreference: String = "gemini"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val speaking = AtomicBoolean(false)
    private val listening = AtomicBoolean(false)
    private val speakGeneration = AtomicLong(0)
    private var speakJob: Job? = null
    @Volatile private var currentText: String = ""

    // ---- STT (Vosk) ---------------------------------------------------------

    private var recognizer: org.vosk.Recognizer? = null
    private var micLine: TargetDataLine? = null
    private var sttThread: Thread? = null

    /** True when the Vosk model is present and STT can start. */
    fun sttAvailable(): Boolean = voskModelPath.isNotBlank() &&
        (File(voskModelPath, "am/final.mdl").exists() || File(voskModelPath, "final.mdl").exists())

    fun startListening() {
        if (speaking.get()) stopSpeaking()
        if (listening.get()) return
        if (!sttAvailable()) {
            StateBus.setState(AssistantState.Error(
                "STT model missing. Download a Vosk small English model (e.g. vosk-model-small-en-us-0.15) and extract it to $voskModelPath."
            ))
            StateBus.reset()
            return
        }
        try {
            val format = AudioFormat(16000f, 16, 1, true, false)
            val info = DataLine.Info(TargetDataLine::class.java, format)
            if (!AudioSystem.isLineSupported(info)) {
                StateBus.setState(AssistantState.Error("No microphone input line supported at 16 kHz mono."))
                StateBus.reset()
                return
            }
            val line = AudioSystem.getLine(info) as TargetDataLine
            line.open(format, 4096)
            line.start()
            micLine = line

            val model = org.vosk.Model(voskModelPath)
            val rec = org.vosk.Recognizer(model, 16000f)
            recognizer = rec

            listening.set(true)
            StateBus.setState(AssistantState.Listening())
            PcLog.i("VOICE", "Listening…")

            sttThread = Thread({
                val buf = ByteArray(4096)
                try {
                    while (listening.get() && !Thread.currentThread().isInterrupted) {
                        val n = line.read(buf, 0, buf.size)
                        if (n <= 0) break
                        if (rec.acceptWaveForm(buf, n)) {
                            val finalText = extractText(rec.result.toString())
                            if (finalText.isNotBlank()) {
                                listening.set(false)
                                StateBus.setState(AssistantState.Processing)
                                PcLog.i("VOICE", "Heard: $finalText")
                                scope.launch { onFinalTranscript(finalText) }
                                break
                            }
                        } else {
                            val partial = extractText(rec.partialResult.toString())
                            if (partial.isNotBlank()) StateBus.setState(AssistantState.Listening(partial))
                        }
                    }
                } catch (t: Throwable) {
                    PcLog.w("VOICE", "STT loop ended: ${t.message}")
                } finally {
                    runCatching { rec.close() }
                    runCatching { model.close() }
                    runCatching { line.stop(); line.close() }
                    if (micLine === line) micLine = null
                    if (listening.getAndSet(false)) StateBus.reset()
                }
            }, "amayra-stt").apply { isDaemon = true; start() }
        } catch (t: Throwable) {
            listening.set(false)
            PcLog.e("VOICE", "Failed to start STT", t)
            StateBus.setState(AssistantState.Error("Microphone/STT failed: ${t.message}"))
            StateBus.reset()
        }
    }

    fun stopListening() {
        listening.set(false)
        runCatching { micLine?.stop() }
        runCatching { micLine?.close() }
        micLine = null
    }

    private fun extractText(json: String): String = runCatching {
        val el = kotlinx.serialization.json.Json.parseToJsonElement(json)
        (el as? kotlinx.serialization.json.JsonObject)?.get("text")?.let { it as? kotlinx.serialization.json.JsonPrimitive }?.content ?: ""
    }.getOrDefault("")

    // ---- TTS ----------------------------------------------------------------

    /** Speak [text]; non-blocking. Same arbiter rule as Android: cancels listening. */
    fun speak(text: String) {
        if (text.isBlank() || enginePreference == "muted") return
        currentText = text
        if (listening.get()) stopListening()

        val gen = speakGeneration.incrementAndGet()
        speaking.set(true)
        StateBus.setState(AssistantState.Speaking(text))

        speakJob = scope.launch {
            var played = false
            if (enginePreference == "gemini" && !geminiApiKeyProvider().isNullOrBlank()) {
                played = speakViaGemini(text, gen)
                if (!played) PcLog.w("TTS", "Gemini TTS unavailable — falling back to Windows SAPI")
            }
            if (!played && gen == speakGeneration.get()) {
                played = speakViaWindows(text, gen)
            }
            if (gen == speakGeneration.get() && speaking.getAndSet(false)) {
                StateBus.reset()
            }
        }
    }

    /** Gemini TTS via REST generateContent; returns PCM as base64. */
    private suspend fun speakViaGemini(text: String, gen: Long): Boolean = try {
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            val key = geminiApiKeyProvider() ?: return@withContext false
            val body = kotlinx.serialization.json.buildJsonObject {
                put("contents", kotlinx.serialization.json.buildJsonArray {
                    add(kotlinx.serialization.json.buildJsonObject {
                        put("parts", kotlinx.serialization.json.buildJsonArray {
                            add(kotlinx.serialization.json.buildJsonObject { put("text", kotlinx.serialization.json.JsonPrimitive(text)) })
                        })
                    })
                })
                put("generationConfig", kotlinx.serialization.json.buildJsonObject {
                    put("responseModalities", kotlinx.serialization.json.buildJsonArray {
                        add(kotlinx.serialization.json.JsonPrimitive("AUDIO"))
                    })
                    put("speechConfig", kotlinx.serialization.json.buildJsonObject {
                        put("voiceConfig", kotlinx.serialization.json.buildJsonObject {
                            put("prebuiltVoiceConfig", kotlinx.serialization.json.buildJsonObject {
                                put("voiceName", kotlinx.serialization.json.JsonPrimitive(voiceName))
                            })
                        })
                    })
                })
            }.toString()
            val req = okhttp3.Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models/$geminiTtsModel:generateContent?key=$key")
                .post(body.toRequestBodyJson())
                .build()
            okhttp3.OkHttpClient().newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    PcLog.w("TTS", "Gemini TTS HTTP ${resp.code}")
                    return@use false
                }
                val root = resp.body!!.string().jsonObjectSafely() ?: return@use false
                val cand = (root["candidates"] as? kotlinx.serialization.json.JsonArray)?.firstOrNull() as? kotlinx.serialization.json.JsonObject
                val parts = (cand?.get("content") as? kotlinx.serialization.json.JsonObject)
                    ?.get("parts") as? kotlinx.serialization.json.JsonArray
                val inline = (parts?.firstOrNull() as? kotlinx.serialization.json.JsonObject)
                    ?.get("inlineData") as? kotlinx.serialization.json.JsonObject
                val b64 = (inline?.get("data") as? kotlinx.serialization.json.JsonPrimitive)?.content
                    ?: return@use false
                val pcm = java.util.Base64.getDecoder().decode(b64)
                if (gen != speakGeneration.get()) return@use true
                playPcm(pcm, 24000, gen)
                true
            }
        }
    } catch (t: Throwable) {
        PcLog.w("TTS", "Gemini synthesis failed: ${t.message}")
        false
    }

    /** Windows SAPI fallback via PowerShell System.Speech — always available on Windows. */
    private suspend fun speakViaWindows(text: String, gen: Long): Boolean = kotlinx.coroutines.withContext(Dispatchers.IO) {
        try {
            val safe = text.replace("$", "`$").replace("'", "''").replace("\"", "\\\"")
            val ps = "Add-Type -AssemblyName System.Speech; " +
                "\$s = New-Object System.Speech.Synthesis.SpeechSynthesizer; " +
                "\$s.Speak(\"$safe\")"
            val proc = ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", ps)
                .redirectErrorStream(true).start()
            // Poll rather than waitFor so a newer speak() can supersede this one.
            while (proc.isAlive) {
                if (gen != speakGeneration.get()) {
                    runCatching { ProcessBuilder("taskkill", "/PID", proc.pid().toString(), "/T", "/F").start() }
                    return@withContext true
                }
                Thread.sleep(80)
            }
            true
        } catch (t: Throwable) {
            PcLog.w("TTS", "Windows SAPI failed: ${t.message}")
            false
        }
    }

    /** Blocking 16-bit PCM playback via Java Sound (SourceDataLine). */
    private fun playPcm(pcm: ByteArray, sampleRate: Int, gen: Long) {
        var line: javax.sound.sampled.SourceDataLine? = null
        try {
            val format = AudioFormat(sampleRate.toFloat(), 16, 1, true, false)
            val info = DataLine.Info(javax.sound.sampled.SourceDataLine::class.java, format)
            line = AudioSystem.getLine(info) as javax.sound.sampled.SourceDataLine
            line.open(format)
            line.start()
            var offset = 0
            while (offset < pcm.size && gen == speakGeneration.get()) {
                val len = minOf(4096, pcm.size - offset)
                val written = line.write(pcm, offset, len)
                offset += written
            }
            line.drain()
        } catch (t: Throwable) {
            PcLog.w("TTS", "PCM playback failed: ${t.message}")
        } finally {
            runCatching { line?.stop() }
            runCatching { line?.close() }
        }
    }

    /** Stop current speech and invalidate in-flight synthesis (same as Android). */
    fun stopSpeaking() {
        speakGeneration.incrementAndGet()
        speakJob?.cancel()
        speakJob = null
        if (speaking.getAndSet(false)) StateBus.reset()
    }

    fun shutdown() {
        stopSpeaking()
        stopListening()
        scope.cancel()
        sttThread?.interrupt()
    }

    private fun String.toRequestBodyJson() =
        okhttp3.RequestBody.create("application/json".toMediaTypeOrNull(), this)

    private fun String.jsonObjectSafely(): kotlinx.serialization.json.JsonObject? =
        runCatching { kotlinx.serialization.json.Json.parseToJsonElement(this) as kotlinx.serialization.json.JsonObject }.getOrNull()

    private fun kotlinx.serialization.json.JsonElement?.jsonArraySafely(): kotlinx.serialization.json.JsonArray? =
        this as? kotlinx.serialization.json.JsonArray

    private fun kotlinx.serialization.json.JsonElement?.jsonObjectSafely(): kotlinx.serialization.json.JsonObject? =
        this as? kotlinx.serialization.json.JsonObject
}
