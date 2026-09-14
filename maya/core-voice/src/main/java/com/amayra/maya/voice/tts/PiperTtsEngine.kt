package com.amayra.maya.voice.tts

import android.content.Context
import com.amayra.maya.core.MayaLog
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File

/**
 * Fully-offline neural TTS (Piper VITS via sherpa-onnx): a young female Hindi
 * voice (priyamvada) that runs ON-DEVICE — natural prosody, zero network,
 * zero quota, works even in airplane mode.
 *
 * Model ships in assets/tts/priyamvada/ and is copied to filesDir on first
 * use (sherpa needs real file paths). Output feeds the same AudioTrack PCM
 * path as Gemini TTS.
 */
class PiperTtsEngine(private val context: Context) {

    private var tts: OfflineTts? = null
    private val lock = Any()

    fun ensureReady(): Boolean {
        synchronized(lock) {
            if (tts != null) return true
            return try {
                val dir = materializeModel()
                val modelPath = File(dir, "hi_IN-priyamvada-medium.onnx").absolutePath
                val tokensPath = File(dir, "tokens.txt").absolutePath
                val dataDir = File(dir, "espeak-ng-data").absolutePath
                val vits = OfflineTtsVitsModelConfig(
                    model = modelPath,
                    tokens = tokensPath,
                    dataDir = dataDir
                )
                val modelCfg = OfflineTtsModelConfig(vits = vits, numThreads = 2)
                tts = OfflineTts(assetManager = null, config = OfflineTtsConfig(model = modelCfg))
                MayaLog.i("TTS", "Piper engine ready (priyamvada, " + tts!!.sampleRate() + " Hz)")
                true
            } catch (t: Throwable) {
                MayaLog.w("TTS", "Piper init failed: " + t.message)
                false
            }
        }
    }

    /** Synthesize text to PCM16 mono (blocking; call off the main thread). */
    fun synthesize(text: String, speed: Float = 1.0f): GeminiTtsClient.PcmAudio? {
        val engine = tts ?: return null
        return try {
            val audio = engine.generate(text, speed = speed)
            if (audio == null || audio.samples.isEmpty()) {
                MayaLog.w("TTS", "Piper produced no audio")
                null
            } else {
                GeminiTtsClient.PcmAudio(
                    pcm = toPcm16(audio.samples),
                    sampleRate = engine.sampleRate(),
                    channels = 1
                )
            }
        } catch (t: Throwable) {
            MayaLog.w("TTS", "Piper synthesis failed: " + t.message)
            null
        }
    }

    fun release() {
        synchronized(lock) {
            try { tts?.release() } catch (_: Throwable) {}
            tts = null
        }
    }

    private fun materializeModel(): File {
        val out = File(context.filesDir, "tts/priyamvada")
        val model = File(out, "hi_IN-priyamvada-medium.onnx")
        val tokens = File(out, "tokens.txt")
        val data = File(out, "espeak-ng-data")
        if (model.isFile && tokens.isFile && data.isDirectory) return out
        out.deleteRecursively()
        copyTree("tts/priyamvada", out)
        return out
    }

    /**
     * Copies the CONTENTS of asset [assetDir] into [targetDir]. A child with
     * no listing of its own is a file leaf (assets.list returns [] for real
     * files, including extension-less dict entries like af_dict), anything
     * with children is a subdirectory. This avoids both traps: nesting a file
     * under a same-named directory, and mkdir-ing for file leaves.
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
}
