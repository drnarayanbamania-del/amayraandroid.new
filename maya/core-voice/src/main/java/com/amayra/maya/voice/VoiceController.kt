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
import android.os.SystemClock
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
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
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

    /** Which engine to use: "gemini" | "android" | "piper". Read at each speak(). */
    @Volatile var enginePreference: String = "gemini"

    /**
     * Hands-free mode: after speaking a reply, automatically start listening
     * again so a conversation continues without tapping the mic each time
     * (blueprint §4: follow-ups without repeating the wake word). A turn ends
     * -> speak() -> playback drains -> relisten, unless the pref is off, the
     * user stopped speech manually, or we just declined to listen.
     */
    @Volatile var handsFree: Boolean = false

    /**
     * Set while the user deliberately stopped speech/turn flow (stop button,
     * stopAll) — suppresses the post-reply relisten for the CURRENT utterance
     * only, so one manual stop doesn't disable hands-free for the session.
     */
    @Volatile private var suppressRelistenOnce: Boolean = false

    /** Pending idle auto-release job (Kokoro ~200 MB back to the system). */
    private var idleReleaseJob: Job? = null

    /**
     * Normalized tail of the last spoken reply — echo guard. Hands-free means
     * the mic re-opens right after playback; the speaker or room echo can leak
     * back in and the recognizer can transcribe MAYA'S OWN WORDS as a user
     * turn (self-talk loop). A heard transcript matching this tail is dropped.
     */
    @Volatile private var lastSpokenTail: String = ""
    @Volatile private var lastSpokenAtMs: Long = 0

    private fun normalizeEcho(s: String): String =
        s.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim().takeLast(120)
    /** Offline neural voice (Kokoro-82M, hf Hindi female) — lazy on first turn. */
    private val kokoro by lazy { com.amayra.maya.voice.tts.KokoroTtsEngine(context) }

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
                maybeRelisten()
            }
            override fun onError(utteranceId: String?) {
                speaking.set(false)
                avatarHooks?.onSpeechEnd()
                StateBus.toIdleIfActive()
                maybeRelisten()
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
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            StateBus.setState(AssistantState.Error("No speech recognition service on this device."))
            return
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
            // Tighter silence windows so finalization doesn't trail the user by
            // seconds (best-effort: not all recognition services honor these).
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, COMPLETE_SILENCE_MS)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, POSSIBLY_SILENCE_MS)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, MIN_UTTERANCE_MS)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        listening.set(true)
        StateBus.setState(AssistantState.Listening())
        // createSpeechRecognizer/startListening are MAIN-THREAD-ONLY. Hands-free
        // relisten calls this from the TTS scope (Dispatchers.IO) — observed FATAL
        // "SpeechRecognizer should be used only from the application's main
        // thread" — so the recognizer interaction always hops to the main looper.
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try {
                if (recognizer == null) {
                    recognizer = SpeechRecognizer.createSpeechRecognizer(context)
                    recognizer?.setRecognitionListener(recognitionListener)
                }
                recognizer?.startListening(intent)
                // Language-pack retry is one-shot per session, not per process lifetime.
                retriedAfterLanguagePackError = false
                MayaLog.i("VOICE", "Listening…")
            } catch (t: Throwable) {
                MayaLog.w("VOICE", "startListening failed: ${t.message}")
                listening.set(false)
                StateBus.toIdleIfActive()
            }
        }
    }

    /**
     * Hands-free continuation: called from every TTS completion point. Listens
     * again after a short settle gap (locks re-open, TTS tail echo fades) so
     * the next utterance flows without touching the mic. Skipped when hands-
     * free is off, the user just stopped speech manually, or the mic guard
     * failed (its own error state already shows).
     */
    private fun maybeRelisten() {
        // Every completed reply (any engine, any mode) re-arms idle auto-release.
        scheduleIdleRelease()
        if (!handsFree || suppressRelistenOnce) {
            suppressRelistenOnce = false
            return
        }
        ttsScope.launch {
            kotlinx.coroutines.delay(RELISTEN_DELAY_MS)
            if (!speaking.get() && handsFree && !suppressRelistenOnce) {
                MayaLog.i("VOICE", "Hands-free: relistening")
                startListening()
            }
            suppressRelistenOnce = false
        }
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
            // Echo guard: hands-free relisten can transcribe Maya's own just-
            // spoken reply (speaker/room echo). Matching the transcript against
            // the last spoken tail drops it BEFORE it becomes a phantom turn.
            if (text.isNotBlank()) {
                val norm = normalizeEcho(text)
                val tail = lastSpokenTail
                val echoWindow = SystemClock.elapsedRealtime() - lastSpokenAtMs < ECHO_GUARD_WINDOW_MS
                if (echoWindow && tail.isNotBlank() && (norm.contains(tail) || tail.contains(norm))) {
                    MayaLog.w("VOICE", "Echo guard: dropped self-heard utterance (${text.take(60)})")
                    StateBus.toIdleIfActive()
                    return
                }
                StateBus.setState(AssistantState.Processing)
                lastFinalAt = SystemClock.elapsedRealtime()
                onFinalTranscript(text)
            }
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
    /** Timestamp of the last finalized transcript — for reply-latency logging. */
    @Volatile private var lastFinalAt: Long = 0

    /**
     * Speak [text] with the configured engine. Non-blocking: Gemini synthesis
     * runs on the TTS scope; Android TTS queues natively.
     */
    fun speak(text: String) {
        if (text.isBlank()) return
        currentText = text
        lastSpokenTail = normalizeEcho(text)
        lastSpokenAtMs = SystemClock.elapsedRealtime()
        // Measure the perceived-latency contributor: STT finalize -> speech start.
        lastFinalAt.takeIf { it > 0 }?.let { mark ->
            val gap = SystemClock.elapsedRealtime() - mark
            if (gap in 0..60_000) MayaLog.i("VOICE", "reply latency: ${gap}ms after speech end")
        }
        // SpeechRecognizer is main-thread-only; speak() can be invoked from any
        // coroutine context (tool results, harness turns) — hop if needed.
        // Idle-release timer resets: engine stays hot during active conversation.
        idleReleaseJob?.cancel()
        if (listening.get()) {
            android.os.Handler(android.os.Looper.getMainLooper()).post { runCatching { recognizer?.stopListening() } }
            // The stopSpeaking() inside startListening's barge-in path set the
            // suppress flag; THIS utterance is a fresh turn whose completion must
            // still re-arm hands-free, so clear it here.
            suppressRelistenOnce = false
        }

        val useGemini = enginePreference == "gemini" &&
            geminiTts != null &&
            personaVoiceProvider != null

        when {
            // "piper" prefs migrate transparently to the better Kokoro engine
            // (same offline slot; the stored value keeps working).
            (enginePreference == "kokoro" || enginePreference == "piper") && kokoro.ensureReady() -> speakViaKokoro(text)
            useGemini -> speakViaGemini(text)
            else -> speakViaAndroid(text)
        }
    }

    /** Offline neural voice: sentence-chunked Kokoro synthesis + same PCM path. */
    private fun speakViaKokoro(text: String) {
        val gen = speakGeneration.incrementAndGet()
        speaking.set(true)
        StateBus.setState(AssistantState.Speaking(text))
        avatarHooks?.onSpeechStart(text)
        speakJob = ttsScope.launch {
            val chunks = splitForSpeech(text)
            MayaLog.d("TTS", "Kokoro: ${chunks.size} chunk(s)")
            for (chunk in chunks) {
                if (gen != speakGeneration.get()) return@launch
                val audio = kokoro.synthesize(chunk)
                if (audio == null) {
                    // Engine failed mid-reply — degrade honestly to Android TTS.
                    MayaLog.w("TTS", "Kokoro chunk failed — falling back to Android TTS")
                    speakViaAndroid(text)
                    return@launch
                }
                playPcm(audio, gen)
            }
            MayaLog.i("TTS", "Kokoro speaking done (offline, quota-free)")
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
            if (client == null || pv == null) {
                MayaLog.w("TTS", "Gemini TTS unavailable — falling back to Android TTS")
                speakViaAndroid(text)
                return@launch
            }
            // Pipeline: sentence-chunk the reply, synthesize chunk N+1 WHILE chunk N
            // plays. First audio starts after the FIRST sentence is synthesized,
            // not after the whole reply — cuts time-to-first-word by roughly the
            // tail of the synthesis (observed: whole-clip latency scaled with reply
            // length; now it scales with the first sentence).
            val chunks = splitForSpeech(text)
            MayaLog.d("TTS", "Pipelined TTS: ${chunks.size} chunk(s)")
            var pending: Deferred<GeminiTtsClient.PcmAudio?>? = ttsScope.async {
                try { client.synthesize(chunks[0], pv.voice, pv.model, pv.styleHint) }
                catch (t: Throwable) { MayaLog.w("TTS", "Gemini synthesis failed: ${t.message}"); null }
            }
            for (index in chunks.indices) {
                if (gen != speakGeneration.get()) return@launch
                val audio = pending?.await()
                pending = if (index + 1 < chunks.size) {
                    val next = index + 1
                    ttsScope.async {
                        try { client.synthesize(chunks[next], pv.voice, pv.model, pv.styleHint) }
                        catch (t: Throwable) { MayaLog.w("TTS", "chunk ${next + 1} synthesis failed: ${t.message}"); null }
                    }
                } else null
                if (gen != speakGeneration.get()) return@launch
                if (audio != null && audio.pcm.isNotEmpty()) {
                    playPcm(audio, gen)  // synthesizes the next chunk while this plays
                } else if (index == 0) {
                    // First chunk failed -> whole reply falls back, never silent.
                    MayaLog.w("TTS", "Gemini TTS unavailable — falling back to Android TTS")
                    speakViaAndroid(text)
                    return@launch
                } else {
                    MayaLog.w("TTS", "chunk ${index + 1}/${chunks.size} failed — skipping")
                }
            }
        }
    }

    /**
     * Split a reply into TTS-sized sentence chunks so synthesis can pipeline.
     * Splits on sentence enders (including Hindi danda) and merges tiny
     * fragments so we don't fire one request per short sentence.
     */
    private fun splitForSpeech(text: String): List<String> {
        val trimmed = text.trim()
        val sentences = Regex("(?<=[.!?।\\n])\\s+").split(trimmed)
            .map { it.trim() }.filter { it.isNotEmpty() }
        if (sentences.size <= 1) return listOf(trimmed)
        val out = ArrayList<String>()
        val sb = StringBuilder()
        for (s in sentences) {
            if (sb.isNotEmpty() && sb.length + s.length + 1 > MAX_TTS_CHUNK) {
                out.add(sb.toString()); sb.clear()
            }
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(s)
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return out
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
                maybeRelisten()
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
        MayaLog.i("TTS", "Android TTS speaking (${target}) — quota-free path")
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "maya-${System.nanoTime()}")
    }

    /** Stop current speech and invalidate any in-flight synthesis. */
    fun stopSpeaking() {
        suppressRelistenOnce = true  // deliberate stop: don't auto-relisten after this
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
        suppressRelistenOnce = true
        stopSpeaking()
        // stopListening() is main-thread-only like the rest of the recognizer API.
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try { recognizer?.stopListening() } catch (_: Throwable) {}
        }
        listening.set(false)
    }

    /**
     * Memory-pressure valve (called from Application.onTrimMemory): releases
     * the offline neural engine (~200 MB native once loaded). Skipped while an
     * utterance is in flight; the engine lazily reloads on the next speak().
     * Without this, LMK kills the whole process under the device's chronic
     * memory pressure — the "app band ho raha baar baar" failure.
     */
    fun releaseHeavyEngines() {
        if (speaking.get()) return
        if (kokoro.releaseIfLoaded()) {
            MayaLog.i("VOICE", "Memory valve: Kokoro engine released (system memory pressure) — reloads on next reply")
        }
    }

    /** Cancels + re-arms after every completed reply; fires only if still idle. */
    private fun scheduleIdleRelease() {
        idleReleaseJob?.cancel()
        idleReleaseJob = ttsScope.launch {
            kotlinx.coroutines.delay(IDLE_RELEASE_MS)
            if (!speaking.get() && kokoro.releaseIfLoaded()) {
                MayaLog.i("VOICE", "Idle release: Kokoro engine unloaded after ${IDLE_RELEASE_MS / 1000}s idle (reloads on next reply)")
            }
        }
    }

    fun shutdown() {
        stopAll()
        idleReleaseJob?.cancel()
        ttsScope.cancel()
        // destroy() is main-thread-only like every other recognizer call.
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try { recognizer?.destroy() } catch (_: Throwable) {}
            recognizer = null
        }
        tts?.shutdown()
        tts = null
    }

    companion object {
        /** Not present as a named constant on older SDKs: Soda offline pack missing. */
        const val ERROR_LANGUAGE_PACK = 13

        /** Max chars per pipelined TTS chunk (~one long sentence). */
        private const val MAX_TTS_CHUNK = 220
        private const val COMPLETE_SILENCE_MS = 700L
        private const val POSSIBLY_SILENCE_MS = 700L
        private const val MIN_UTTERANCE_MS = 5000L

        /** Settle gap between a reply's last audio and re-arming the mic. */
        private const val RELISTEN_DELAY_MS = 600L

        /** Echo guard: how long after speaking a self-heard match is dropped. */
        private const val ECHO_GUARD_WINDOW_MS = 8_000L

        /** Idle auto-release: unload the offline engine after this much quiet. */
        // 45s: shrink the Kokoro whale-window fast — long idle = LMK/cleaner bait
        private const val IDLE_RELEASE_MS = 45_000L
    }
}
