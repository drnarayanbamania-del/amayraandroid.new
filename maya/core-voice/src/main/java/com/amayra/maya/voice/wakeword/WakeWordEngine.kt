package com.amayra.maya.voice.wakeword

import android.content.Context
import com.amayra.maya.core.MayaLog
import com.amayra.maya.voice.guardian.TFLiteSupport
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer

/**
 * Offline "Hey Maya" / "Wake up Maya" detection (openWakeWord pipeline).
 *
 * Three chained TFLite models:
 *   melspectrogram.tflite  : 16 kHz PCM (int16-scale float) -> mel frames
 *   embedding_model.tflite : 76 x 32 mel window -> 96-dim speech embedding
 *   hey_maya.tflite        : 16 x 96 embedding window -> wake score 0..1
 *   wake_up_maya.tflite    : alternative phrase
 *
 * Faithful to the reference streaming implementation (openWakeWord
 * AudioFeatures._streaming_features):
 *  - mel input is int16-SCALE float (values in ~[-32768..32767]), NOT [-1..1]
 *  - mel output is transformed (x/10 + 2) before the embedding model
 *  - each 1280-sample step runs mel over the last 1760 samples (480-sample
 *    overlap) so mel frames advance in stride-8 steps, matching training
 *  - one 96-dim embedding per step; keyword scores the last 16 embeddings
 *
 * All shapes are introspected at load; if any link doesn't fit, the engine
 * reports [available] = false and the service degrades honestly (no fake
 * detection). Default phrase switching is via [useAlternative].
 */
class WakeWordEngine private constructor(
    private val mel: Interpreter,
    private val embedding: Interpreter,
    private val keyword: Interpreter,
    private val altKeyword: Interpreter?
) {
    var available: Boolean = true
        private set

    /** New 16 kHz PCM samples consumed per [feed] step (1280 = 80 ms). */
    val chunkSamples: Int

    /** Samples per mel invocation = chunk + reference's 3-frame overlap. */
    private val melInSamples: Int
    private val melFramesPerStep: Int
    private val melBins: Int
    private val embWindow: Int      // mel frames per embedding window (76)
    private val embDims: Int        // 96
    private val embFramesPerStep: Int
    private val wakeWindowFrames: Int

    private val rawRing: WakeWordMath.RawRing
    private val melHistory = ArrayDeque<FloatArray>()
    private val embHistory = ArrayDeque<FloatArray>()
    private val gate = WakeWordMath.StreakGate()

    // openWakeWord's standard threshold. On-device telemetry showed real
    // triggers peaking ~0.74 at 0.75 (near-miss); 0.5 + the 2-window
    // confirmation gate is the reference-default sensitivity.
    var threshold: Float = 0.5f
    var useAlternative: Boolean = false
    val phrase: String get() = if (useAlternative) "wake up maya" else "hey maya"

    /** Last raw keyword score (pre-confirmation-gate) — wake telemetry. */
    var lastRawScore: Float = 0f
        private set

    init {
        // ── Mel model ────────────────────────────────────────────────────
        val melIn = TFLiteSupport.inputShape(mel, 0) ?: intArrayOf(1, 1280)
        val staticIn = melIn.getOrNull(1)?.takeIf { it > 0 }
        if (staticIn != null) {
            chunkSamples = staticIn
            melInSamples = staticIn
        } else {
            // Dynamic input: pin it exactly like the reference implementation
            // (fixed window => static output shape + stride-8 frame cadence).
            chunkSamples = CHUNK_SAMPLES
            melInSamples = CHUNK_SAMPLES + OVERLAP_SAMPLES
            runCatching {
                mel.resizeInput(0, intArrayOf(1, melInSamples))
                mel.allocateTensors()
            }.onFailure { MayaLog.w("WAKE", "mel resize failed: ${it.message}") }
        }
        val melOut = TFLiteSupport.outputShape(mel, 0) ?: intArrayOf(1, 8, 32)
        rawRing = WakeWordMath.RawRing(maxSamples = melInSamples + CHUNK_SAMPLES)
        melFramesPerStep = melOut.getOrNull(melOut.size - 2)?.takeIf { it > 0 }
            ?: (melInSamples / MEL_HOP - 3)
        melBins = melOut.last()

        // ── Embedding model: input [1, 76, 32, 1] (frames, mel bins, channel) ──
        val embIn = TFLiteSupport.inputShape(embedding, 0) ?: intArrayOf(1, 76, melBins, 1)
        embWindow = embIn.getOrNull(1)?.takeIf { it > 0 } ?: 76
        val embMelBins = if (embIn.size >= 3) embIn[embIn.size - 2] else melBins
        val embOut = TFLiteSupport.outputShape(embedding, 0) ?: intArrayOf(1, 96)
        embDims = embOut.last()
        embFramesPerStep = if (embOut.size >= 2) {
            embOut[embOut.size - 2].takeIf { it > 0 } ?: 1
        } else 1

        // ── Keyword model: input [1, 16, 96] (embedding frames) ─────────
        val kwIn = TFLiteSupport.inputShape(keyword, 0) ?: intArrayOf(1, 16, embDims)
        wakeWindowFrames = kwIn.getOrNull(1)?.takeIf { it > 0 } ?: 16

        // Compare semantic dims: mel bins vs the embedding input's SECOND-to-last
        // dim (its last dim is the channel axis), and embedding dim vs keyword's.
        val shapeOk = (melBins == embMelBins) && (embDims == kwIn.last())
        if (!shapeOk) {
            available = false
            MayaLog.w("WAKE", "Model chain mismatch: mel=$melOut emb-in=$embIn kw=$kwIn")
        } else {
            MayaLog.i(
                "WAKE",
                "Wake chain ok: chunk=$chunkSamples melIn=$melInSamples " +
                    "melStep=${melFramesPerStep}x$melBins embWin=$embWindow " +
                    "emb=${embOut.joinToString("x")} wake-window=$wakeWindowFrames"
            )
        }
    }

    /**
     * Feed the next PCM chunk (16 kHz mono float in [-1..1]; the engine scales
     * it to the int16 range the mel model expects). Returns 0..1 wake score.
     */
    fun feed(chunk: FloatArray): Float {
        if (!available) return 0f
        if (chunk.isEmpty()) return 0f
        appendRaw(chunk)
        val melOut = runMel() ?: return 0f
        pushMel(melOut)
        if (!runEmbedding()) return 0f
        return runKeyword()
    }

    /** Reset streaming history + streak (call between sessions). */
    fun reset() {
        rawRing.clear()
        melHistory.clear()
        embHistory.clear()
        gate.reset()
    }

    private fun appendRaw(chunk: FloatArray) {
        // Scale to the int16 range: the mel model was trained on raw PCM.
        rawRing.append(WakeWordMath.int16Scale(chunk))
    }

    private fun runMel(): FloatArray? {
        val input = rawRing.lastSamples(melInSamples) ?: return null
        val inBuf = TFLiteSupport.floatBuf(input.size)
        inBuf.asFloatBuffer().put(input)
        val outBuf = TFLiteSupport.floatBuf(melFramesPerStep * melBins)
        return try {
            mel.run(inBuf, outBuf)
            outBuf.rewind()
            val fb = outBuf.asFloatBuffer()
            // Reference transform: spec/10 + 2 — aligns the TFLite mel model's
            // output distribution with Google's native speech_embedding input.
            FloatArray(melFramesPerStep * melBins) { fb.get(it) / 10f + 2f }
        } catch (t: Throwable) {
            MayaLog.w("WAKE", "mel failed: ${t.message}")
            null
        }
    }

    private fun pushMel(melOut: FloatArray) {
        var i = 0
        while (i + melBins <= melOut.size) {
            melHistory.addLast(melOut.copyOfRange(i, i + melBins))
            i += melBins
        }
        while (melHistory.size > MAX_MEL_HISTORY) melHistory.removeFirst()
    }

    private fun runEmbedding(): Boolean {
        if (melHistory.size < embWindow) return false
        val lastN = melHistory.takeLast(embWindow)
        val inBuf = TFLiteSupport.floatBuf(embWindow * melBins)
        val fb = inBuf.asFloatBuffer()
        for (f in lastN) fb.put(f)
        inBuf.rewind()
        val outBuf = TFLiteSupport.floatBuf(embFramesPerStep * embDims)
        return try {
            embedding.run(inBuf, outBuf)
            outBuf.rewind()
            val vfb = outBuf.asFloatBuffer()
            var i = 0
            while (i + embDims <= embFramesPerStep * embDims) {
                embHistory.addLast(FloatArray(embDims) { vfb.get(i + it) })
                i += embDims
            }
            while (embHistory.size > wakeWindowFrames * 4) embHistory.removeFirst()
            true
        } catch (t: Throwable) {
            MayaLog.w("WAKE", "embedding failed: ${t.message}")
            false
        }
    }

    private fun runKeyword(): Float {
        val kw = if (useAlternative) altKeyword ?: keyword else keyword
        return try {
            if (embHistory.size < wakeWindowFrames) return 0f
            val lastN = embHistory.takeLast(wakeWindowFrames)
            val inBuf = TFLiteSupport.floatBuf(wakeWindowFrames * embDims)
            val fb = inBuf.asFloatBuffer()
            for (f in lastN) fb.put(f)
            inBuf.rewind()
            val outBuf = TFLiteSupport.floatBuf(1)
            kw.run(inBuf, outBuf)
            outBuf.rewind()
            val score = outBuf.asFloatBuffer().get(0).coerceIn(0f, 1f)
            lastRawScore = score
            gate.score(score, threshold)
        } catch (t: Throwable) {
            MayaLog.w("WAKE", "keyword failed: ${t.message}")
            0f
        }
    }

    fun close() {
        mel.close(); embedding.close(); keyword.close(); altKeyword?.close()
    }

    companion object {
        private const val CHUNK_SAMPLES = 1280      // 80 ms @ 16 kHz
        private const val OVERLAP_SAMPLES = 480     // reference: 3 x 160 hop
        private const val MEL_HOP = 160
        private const val MAX_MEL_HISTORY = 128

        fun load(context: Context): WakeWordEngine? {
            val melBuf = TFLiteSupport.loadAsset(context, "wakeword/melspectrogram.tflite")
                ?: return null.also { MayaLog.i("WAKE", "No wake-word assets — engine unavailable") }
            val embBuf = TFLiteSupport.loadAsset(context, "wakeword/embedding_model.tflite") ?: return null
            val kwBuf = TFLiteSupport.loadAsset(context, "wakeword/hey_maya.tflite") ?: return null
            val altBuf = TFLiteSupport.loadAsset(context, "wakeword/wake_up_maya.tflite")
            return try {
                val e = WakeWordEngine(
                    mel = TFLiteSupport.newInterpreter(melBuf, 1),
                    embedding = TFLiteSupport.newInterpreter(embBuf, 1),
                    keyword = TFLiteSupport.newInterpreter(kwBuf, 1),
                    altKeyword = altBuf?.let { TFLiteSupport.newInterpreter(it, 1) }
                )
                if (!e.available) { e.close(); null } else e
            } catch (t: Throwable) {
                MayaLog.e("WAKE", "Wake engine load failed", t)
                null
            }
        }
    }
}
