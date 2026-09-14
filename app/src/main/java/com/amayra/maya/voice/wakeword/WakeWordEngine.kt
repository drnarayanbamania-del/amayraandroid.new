package com.amayra.maya.voice.wakeword

import android.content.Context
import com.amayra.maya.core.MayaLog
import com.amayra.maya.voice.guardian.TFLiteSupport
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer

/**
 * Offline "Hey Maya" / "Wake up Maya" detection.
 *
 * Three chained TFLite models (mirrors the reference app's asset layout):
 *   melspectrogram.tflite  : raw 16 kHz PCM chunk -> mel frames
 *   embedding_model.tflite : mel frames -> speech embedding frames
 *   hey_maya.tflite        : embedding window -> wake score 0..1
 *   wake_up_maya.tflite    : alternative phrase
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

    /** Samples of 16 kHz audio per mel chunk (from the mel model's input shape). */
    val chunkSamples: Int
    private val melFramesPerChunk: Int
    private val melDims: Int
    private val embDims: Int
    private val wakeWindowFrames: Int

    private val melHistory = ArrayDeque<FloatArray>()
    private val embHistory = ArrayDeque<FloatArray>()

    /** Smoothed score + confirmation counter to cut false triggers. */
    private var hotStreak = 0

    var threshold: Float = 0.75f
    var useAlternative: Boolean = false
    val phrase: String get() = if (useAlternative) "wake up maya" else "hey maya"

    init {
        val melIn = TFLiteSupport.inputShape(mel, 0) ?: intArrayOf(1, 1280)
        chunkSamples = if (melIn.size >= 2 && melIn[1] in 160..8192) melIn[1] else 1280
        val melOut = TFLiteSupport.outputShape(mel, 0) ?: intArrayOf(1, 8, 32)
        melFramesPerChunk = if (melOut.size >= 2) melOut[melOut.size - 2] else 8
        melDims = melOut.last()

        val embIn = TFLiteSupport.inputShape(embedding, 0) ?: intArrayOf(1, 8, 32)
        val embOut = TFLiteSupport.outputShape(embedding, 0) ?: intArrayOf(1, 8, 96)
        embDims = embOut.last()

        val kwIn = TFLiteSupport.inputShape(keyword, 0) ?: intArrayOf(1, 16, embDims)
        wakeWindowFrames = if (kwIn.size >= 2) kwIn[1] else 16

        val shapeOk = (melOut.size == 3 && melOut.last() == embIn.last()) &&
            (embOut.last() == kwIn.last())
        if (!shapeOk) {
            available = false
            MayaLog.w("WAKE", "Model chain mismatch: mel=$melOut emb-in=$embIn kw=$kwIn")
        } else {
            MayaLog.i(
                "WAKE",
                "Wake chain ok: chunk=$chunkSamples mel=${melOut.joinToString("x")} " +
                    "emb=${embOut.joinToString("x")} wake-window=$wakeWindowFrames"
            )
        }
    }

    /** Feed the next PCM chunk (16 kHz mono float). Returns 0..1 wake score for this step. */
    fun feed(chunk: FloatArray): Float {
        if (!available) return 0f
        if (chunk.size != chunkSamples) return 0f
        val melOut = runMel(chunk) ?: return 0f
        pushMel(melOut)
        val emb = runEmbedding() ?: return 0f
        pushEmb(emb)
        return runKeyword()
    }

    /** Reset streaming history + streak (call between sessions). */
    fun reset() {
        melHistory.clear()
        embHistory.clear()
        hotStreak = 0
    }

    private fun runMel(chunk: FloatArray): FloatArray? = try {
        val inBuf = TFLiteSupport.floatBuf(chunk.size)
        inBuf.asFloatBuffer().put(chunk)
        val frames = melFramesPerChunk
        val outBuf = TFLiteSupport.floatBuf(frames * melDims)
        mel.run(inBuf, outBuf)
        outBuf.rewind()
        FloatArray(frames * melDims) { outBuf.asFloatBuffer().get(it) }
    } catch (t: Throwable) {
        MayaLog.w("WAKE", "mel failed: ${t.message}")
        null
    }

    private fun pushMel(melOut: FloatArray) {
        // split into individual frames
        var i = 0
        while (i + melDims <= melOut.size) {
            melHistory.addLast(melOut.copyOfRange(i, i + melDims))
            i += melDims
        }
        while (melHistory.size > MAX_MEL_HISTORY) melHistory.removeFirst()
    }

    private fun runEmbedding(): FloatArray? {
        val embInFrames = TFLiteSupport.inputShape(embedding, 0)
            ?.getOrNull(1)?.coerceAtLeast(1) ?: melFramesPerChunk
        if (melHistory.size < embInFrames) return null
        return try {
        val lastN = melHistory.takeLast(embInFrames)
        val inBuf = TFLiteSupport.floatBuf(embInFrames * melDims)
        val fb = inBuf.asFloatBuffer()
        for (f in lastN) fb.put(f)
        inBuf.rewind()
        val embOut = TFLiteSupport.outputShape(embedding, 0) ?: intArrayOf(1, embInFrames, embDims)
        val outFrames = if (embOut.size >= 2) embOut[embOut.size - 2] else embInFrames
        val outBuf = TFLiteSupport.floatBuf(outFrames * embDims)
        embedding.run(inBuf, outBuf)
        outBuf.rewind()
        FloatArray(outFrames * embDims) { outBuf.asFloatBuffer().get(it) }
        } catch (t: Throwable) {
            MayaLog.w("WAKE", "embedding failed: ${t.message}")
            null
        }
    }

    private fun pushEmb(embOut: FloatArray) {
        var i = 0
        while (i + embDims <= embOut.size) {
            embHistory.addLast(embOut.copyOfRange(i, i + embDims))
            i += embDims
        }
        while (embHistory.size > wakeWindowFrames * 4) embHistory.removeFirst()
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
            smoothed(score)
        } catch (t: Throwable) {
            MayaLog.w("WAKE", "keyword failed: ${t.message}")
            0f
        }
    }

    private fun smoothed(raw: Float): Float {
        // Trigger when the model stays hot for 2 consecutive scored windows.
        if (raw >= threshold) hotStreak++ else hotStreak = 0
        return if (hotStreak >= 2) {
            hotStreak = 0
            1f
        } else raw
    }

    fun close() {
        mel.close(); embedding.close(); keyword.close(); altKeyword?.close()
    }

    companion object {
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
