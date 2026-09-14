package com.amayra.maya.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.content.ContextCompat
import com.amayra.maya.core.AssistantState
import com.amayra.maya.core.MayaLog
import com.amayra.maya.core.StateBus
import com.amayra.maya.voice.tts.GeminiTtsClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Voice pipeline: speech-to-text (SpeechRecognizer) + TTS with an arbiter so TTS
 * doesn't talk over STT and vice versa.
 *
 * TTS engines:
 *  - "gemini": the persona's configured Gemini prebuilt voice (24 kHz PCM via
 *    [GeminiTtsClient], played through an AudioTrack). Falls back to Android TTS
 *    on any failure (no key, network error, empty response) — never silent.
 *  - "android": device TextToSpeech (default engine voice).
 */
class VoiceController(
    private val context: Context,
    private val onFinalTranscript: (String) -> Unit,
    /** Optional avatar sync hooks (set after construction via [bindAvatar]). */
    private var avatarHooks: AvatarHooks? = null
) {

    /** Bridge so the avatar can mirror TTS lifecycle without a dependency cycle. */
    data class AvatarHooks(
        val onSpeechStart: (text: String) -> Unit,
        val onSpeechEnd: () -> Unit
    )

    fun bindAvatar(hooks: AvatarHooks) { avatarHooks = hooks }

    // ---- TTS engine wiring (injected post-construction) --------------------

    /** Gemini TTS engine; when null, or when engine == "android", Android TTS is used. */
    var geminiTts: GeminiTtsClient? = null

    /** Voice/style resolver for the active persona; set by MayaApplication. */
    var personaVoiceProvider: (() -> GeminiTtsClient.PersonaVoice?)? = null

    /** Which engine to use: "gemini" | "android". Read at each speak(). */
    @Volatile var enginePreference: String = "gemini"

    /** Notification mirror for speak() (set by MayaApplication, optional). */
    @Volatile var speakNotifier: ((String) -> Unit)? = null

    private val ttsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ---- Android TTS (fallback engine) --------------------------------------

    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private val speaking = AtomicBoolean(false)
    private val listening = AtomicBoolean(false)
    /** Guards the one-shot online-retry after a Soda language-pack failure. */
    @Volatile private var retriedAfterLanguagePackError = false
    var ttsReady = false; private set

    // ---- Gemini playback ----------------------------------------------------

    private var audioTrack: AudioTrack? = null
    private var speakJob: Job? = null
    /** Increments per speak(); stale synthesis/playback work self-cancels. */
    private val speakGeneration = java.util.concurrent.atomic.AtomicLong(0)

    init {
        initTts()
    }

    private fun initTts() {
        tts = TextToSpeech(context) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                tts?.language = Locale.getDefault()
                MayaLog.i("VOICE", "Android TTS ready (${Locale.getDefault()})")
            } else {
                MayaLog.w("VOICE", "Android TTS init failed: status=$status")
            }
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                speaking.set(true)
                StateBus.setState(AssistantState.Speaking(currentText ?: ""))
                avatarHooks?.onSpeechStart(currentText ?: "")
            }
            override fun onDone(utteranceId: String?) {
                speaking.set(false)
                avatarHooks?.onSpeechEnd()
                StateBus.toIdleIfActive()
            }
            override fun onError(utteranceId: String?) {
                speaking.set(false)
                avatarHooks?.onSpeechEnd()
                StateBus.toIdleIfActive()
            }
        })
    }

    fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun startListening() {
        if (!hasMicPermission()) {
            StateBus.setState(AssistantState.Error("Microphone permission needed. Enable it in Settings → Permissions."))
            return
        }
        if (recognizer == null) {
            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                StateBus.setState(AssistantState.Error("No speech recognition service on this device."))
                return
            }
            recognizer = SpeechRecognizer.createSpeechRecognizer(context)
            recognizer?.setRecognitionListener(recognitionListener)
        }
        if (speaking.get()) stopSpeaking()
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toString())
            // Prefer the online (network) recognizer: several OEM builds ship without
            // the offline Soda language pack (failing with "error 13"), while the
            // online path always works. Explicitly bias away from offline-only.
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
        }
        listening.set(true)
        StateBus.setState(AssistantState.Listening())
        recognizer?.startListening(intent)
        // Language-pack retry is one-shot per session, not per process lifetime.
        retriedAfterLanguagePackError = false
        MayaLog.i("VOICE", "Listening…")
    }

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            StateBus.setState(AssistantState.Listening())
        }
        override fun onBeginningOfSpeech() {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            StateBus.setState(AssistantState.Listening(text))
        }
        override fun onResults(results: Bundle?) {
            listening.set(false)
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            StateBus.setState(AssistantState.Processing)
            if (text.isNotBlank()) onFinalTranscript(text)
            else StateBus.toIdleIfActive()
        }
        override fun onError(error: Int) {
            listening.set(false)
            val msg = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH -> "I didn't catch that."
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Didn't hear anything."
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission denied."
                // Offline recognizer's language pack is missing (Soda error 13).
                // One automatic retry biases to the online recognizer, which does
                // not need the pack; if that also fails, tell the user honestly.
                ERROR_LANGUAGE_PACK -> {
                    // One-shot per listening session: reset on every successful
                    // startListening, so a later session can retry again instead of
                    // being permanently burned by one early failure.
                    if (retriedAfterLanguagePackError) {
                        retriedAfterLanguagePackError = false
                        StateBus.setState(AssistantState.Error("Speech service language pack missing. Check network or update the Google app."))
                        StateBus.toIdleIfActive()
                        return
                    }
                    retriedAfterLanguagePackError = true
                    MayaLog.w("VOICE", "Soda language pack error — retrying with online preference")
                    startListening()
                    return
                }
                else -> "Voice error $error"
            }
            StateBus.setState(AssistantState.Error(msg))
            StateBus.toIdleIfActive()
        }
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    @Volatile private var currentText: String = ""

    /**
     * Speak [text] with the configured engine. Non-blocking: Gemini synthesis
     * runs on the TTS scope; Android TTS queues natively.
     */
    fun speak(text: String) {
        if (text.isBlank()) return
        currentText = text
        if (listening.get()) recognizer?.stopListening()

        val useGemini = enginePreference == "gemini" &&
            geminiTts != null &&
            personaVoiceProvider != null

        if (useGemini) {
            speakViaGemini(text)
        } else {
            speakViaAndroid(text)
        }
    }

    private fun speakViaGemini(text: String) {
        val gen = speakGeneration.incrementAndGet()
        speaking.set(true)
        StateBus.setState(AssistantState.Speaking(text))
        avatarHooks?.onSpeechStart(text)
        speakJob = ttsScope.launch {
            val pv = personaVoiceProvider?.invoke()
            val client = geminiTts
            val audio = if (client != null && pv != null) {
                try {
                    client.synthesize(text, pv.voice, pv.model, pv.styleHint)
                } catch (t: Throwable) {
                    MayaLog.w("TTS", "Gemini synthesis failed: ${t.message}")
                    null
                }
            } else null

            if (gen != speakGeneration.get()) return@launch  // superseded by a newer speak()
            if (audio != null && audio.pcm.isNotEmpty()) {
                playPcm(audio, gen)
                // completion handled after playback drains
            } else {
                MayaLog.w("TTS", "Gemini TTS unavailable — falling back to Android TTS")
                speakViaAndroid(text)
            }
        }
    }

    /** Blocking PCM playback on the TTS scope; marks completion when drained. */
    private fun playPcm(audio: GeminiTtsClient.PcmAudio, gen: Long) {
        try {
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(audio.sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(
                    maxOf(
                        AudioTrack.getMinBufferSize(
                            audio.sampleRate,
                            AudioFormat.CHANNEL_OUT_MONO,
                            AudioFormat.ENCODING_PCM_16BIT
                        ),
                        audio.pcm.size
                    )
                )
                .build()
            audioTrack = track
            track.play()
            track.write(audio.pcm, 0, audio.pcm.size)
            // Wait for drain (poll — AudioTrack.setNotificationMarkerPosition
            // callbacks are unreliable across OEMs).
            val durationMs = audio.pcm.size * 500L / audio.sampleRate  // 2 bytes/sample
            var waited = 0
            while (track.playState == AudioTrack.PLAYSTATE_PLAYING && waited < durationMs + 1500 && gen == speakGeneration.get()) {
                Thread.sleep(80)
                waited += 80
            }
        } catch (t: Throwable) {
            MayaLog.w("TTS", "PCM playback failed: ${t.message}")
        } finally {
            try { audioTrack?.stop() } catch (_: Throwable) {}
            try { audioTrack?.release() } catch (_: Throwable) {}
            audioTrack = null
            if (gen == speakGeneration.get()) {
                speaking.set(false)
                avatarHooks?.onSpeechEnd()
                StateBus.toIdleIfActive()
            }
        }
    }

    /** Classic Android TTS path (also the fallback when Gemini synthesis fails). */
    private fun speakViaAndroid(text: String) {
        if (!ttsReady) {
            MayaLog.w("VOICE", "Android TTS not ready; retrying init and dropping this utterance")
            speaking.set(false)
            StateBus.setState(AssistantState.Error("🔇 Voice unavailable — TTS engine not ready. Try again in a moment."))
            initTts()  // re-attempt so the NEXT speak() has a working engine
            return
        }
        tts?.setSpeechRate(1.0f)
        tts?.setPitch(1.0f)
        // Devanagari (or heavy Hindi words) in the reply -> switch TTS to Hindi so
        // it isn't mangled by an English voice; Latin-script text keeps the default.
        val target = if (text.any { it in '\u0900'..'\u097F' }) Locale("hi", "IN") else Locale.getDefault()
        val result = tts?.setLanguage(target)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            MayaLog.w("VOICE", "Hindi TTS voice unavailable (${result}); falling back to default")
            tts?.language = Locale.getDefault()
        }
        // onStart fires async; claim the state now so nothing else overwrites it
        // between here and the callback (the avatar's SPEAKING signal depends on it).
        speaking.set(true)
        StateBus.setState(AssistantState.Speaking(text))
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "maya-${System.nanoTime()}")
    }

    /** Stop current speech and invalidate any in-flight synthesis. */
    fun stopSpeaking() {
        speakGeneration.incrementAndGet()  // invalidate pending playback loops
        speakJob?.cancel()
        speakJob = null
        try { audioTrack?.pause() } catch (_: Throwable) {}
        try { audioTrack?.flush() } catch (_: Throwable) {}
        try { audioTrack?.stop() } catch (_: Throwable) {}
        try { audioTrack?.release() } catch (_: Throwable) {}
        audioTrack = null
        tts?.stop()
        if (speaking.getAndSet(false)) {
            avatarHooks?.onSpeechEnd()
            StateBus.toIdleIfActive()
        }
    }

    fun stopAll() {
        stopSpeaking()
        recognizer?.stopListening()
        listening.set(false)
    }

    fun shutdown() {
        stopAll()
        ttsScope.cancel()
        recognizer?.destroy()
        recognizer = null
        tts?.shutdown()
        tts = null
    }

    companion object {
        /** Not present as a named constant on older SDKs: Soda offline pack missing. */
        const val ERROR_LANGUAGE_PACK = 13
    }
}
