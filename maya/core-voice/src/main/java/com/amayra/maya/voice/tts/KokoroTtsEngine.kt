package com.amayra.maya.voice.tts

import android.content.Context
import com.amayra.maya.core.MayaLog
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import java.io.File

/**
 * Fully-offline neural TTS (Kokoro-82M int8 via sherpa-onnx): a natural,
 * sweet female Hindi voice (hf_ speaker in voices.bin) that runs ON-DEVICE —
 * human-like prosody, zero network, zero quota. Generation ahead of Piper's
 * VITS; same AudioTrack PCM path.
 *
 * Bundle ships in assets/tts/kokoro/ (model.int8.onnx + voices.bin + tokens
 * + espeak-ng-data) and is copied to filesDir on first use (sherpa needs real
 * file paths). Voice is selectable by speaker id: 47 = Hindi female default
 * (pref tts_voice), no rebuild needed to try others.
 */
class KokoroTtsEngine(private val context: Context) {

    private var tts: OfflineTts? = null
    private var loadedSid: Int = -1
    private val lock = Any()

    /** Requested speaker id; set live from prefs (47 = hf Hindi female). */
    @Volatile var requestedSid: Int = DEFAULT_SID

    fun ensureReady(): Boolean {
        synchronized(lock) {
            if (tts != null && loadedSid == requestedSid) return true
            if (tts != null && loadedSid != requestedSid) {
                // Speaker id changed: voices.bin is loaded once by the native
                // layer; a sid switch needs a clean engine recreate.
                try { tts?.release() } catch (_: Throwable) {}
                tts = null
            }
            return try {
                val dir = materializeModel()
                val kokoro = OfflineTtsKokoroModelConfig(
                    model = File(dir, "model.int8.onnx").absolutePath,
                    voices = File(dir, "voices.bin").absolutePath,
                    tokens = File(dir, "tokens.txt").absolutePath,
                    dataDir = File(dir, "espeak-ng-data").absolutePath
                )
                val modelCfg = OfflineTtsModelConfig(kokoro = kokoro, numThreads = 2)
                tts = OfflineTts(assetManager = null, config = OfflineTtsConfig(model = modelCfg))
                loadedSid = requestedSid
                MayaLog.i("TTS", "Kokoro engine ready (sid=$loadedSid, " + tts!!.sampleRate() + " Hz)")
                true
            } catch (t: Throwable) {
                MayaLog.w("TTS", "Kokoro init failed: " + t.message)
                false
            }
        }
    }

    /** Synthesize text to PCM16 mono (blocking; call off the main thread). */
    fun synthesize(text: String, speed: Float = 1.0f): GeminiTtsClient.PcmAudio? {
        val engine = tts ?: return null
        return try {
            val audio = engine.generate(text, sid = loadedSid, speed = speed)
            if (audio == null || audio.samples.isEmpty()) {
                MayaLog.w("TTS", "Kokoro produced no audio")
                null
            } else {
                GeminiTtsClient.PcmAudio(
                    pcm = toPcm16(audio.samples),
                    sampleRate = engine.sampleRate(),
                    channels = 1
                )
            }
        } catch (t: Throwable) {
            MayaLog.w("TTS", "Kokoro synthesis failed: " + t.message)
            null
        }
    }

    fun release() {
        synchronized(lock) {
            try { tts?.release() } catch (_: Throwable) {}
            tts = null
            loadedSid = -1
        }
    }

    /**
     * Memory-pressure variant: releases ONLY if an engine instance is loaded.
     * Returns true when a loaded engine was actually released (so callers can
     * log/observe the valve firing), false when there was nothing to free.
     */
    fun releaseIfLoaded(): Boolean {
        synchronized(lock) {
            if (tts == null) return false
            try { tts?.release() } catch (_: Throwable) {}
            tts = null
            loadedSid = -1
            return true
        }
    }

    private fun materializeModel(): File {
        val out = File(context.filesDir, "tts/kokoro")
        val model = File(out, "model.int8.onnx")
        val voices = File(out, "voices.bin")
        val tokens = File(out, "tokens.txt")
        val data = File(out, "espeak-ng-data")
        if (model.isFile && voices.isFile && tokens.isFile && data.isDirectory) return out
        out.deleteRecursively()
        copyTree("tts/kokoro", out)
        return out
    }

    /**
     * Copies the CONTENTS of asset [assetDir] into [targetDir]. A child with
     * no listing of its own is a file leaf (assets.list returns [] for real
     * files, including extension-less dict entries like af_dict), anything
     * with children is a subdirectory. Avoids both known traps: nesting a
     * file under a same-named directory, and mkdir-ing for file leaves.
     */
    private fun copyTree(assetDir: String, targetDir: File) {
        targetDir.mkdirs()
        for (child in context.assets.list(assetDir).orEmpty()) {
            val childPath = "$assetDir/$child"
            val grandChildren: List<String> = try {
                context.assets.list(childPath)?.toList().orEmpty()
            } catch (_: Throwable) {
                emptyList()
            }
            if (grandChildren.isEmpty()) {
                val dst = File(targetDir, child)
                try {
                    context.assets.open(childPath).use { input ->
                        dst.outputStream().use { input.copyTo(it) }
                    }
                } catch (t: Throwable) {
                    MayaLog.w("TTS", "Asset copy FAILED: $childPath :: ${t.message}")
                }
            } else {
                copyTree(childPath, File(targetDir, child))
            }
        }
    }

    private fun toPcm16(samples: FloatArray): ByteArray {
        val out = ByteArray(samples.size * 2)
        var i = 0
        for (s in samples) {
            val v = (s.coerceIn(-1f, 1f) * 32767f).toInt()
            out[i] = (v and 0xFF).toByte()
            out[i + 1] = ((v shr 8) and 0xFF).toByte()
            i += 2
        }
        return out
    }

    companion object {
        /** hf_ (Hindi Female) in kokoro multi-lang voices.bin. */
        const val DEFAULT_SID = 47
    }
}
