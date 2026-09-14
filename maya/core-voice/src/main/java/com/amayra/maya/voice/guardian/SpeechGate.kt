package com.amayra.maya.voice.guardian

import android.content.Context
import com.amayra.maya.core.MayaLog
import org.tensorflow.lite.Interpreter
import java.nio.FloatBuffer

/**
 * Silero VAD v4 (assets/guardian/silero_vad.tflite): frame-wise "is this human speech?"
 * gate with persistent LSTM state (h/c, 2 layers x 64 hidden).
 *
 * Frame contract: 512-sample (32 ms) windows of 16 kHz mono float PCM in [-1..1].
 */
class SpeechGate private constructor(private val interpreter: Interpreter) {

    // Silero v4 state layout: h,c each [2, 1, 64]
    private val h = TFLiteSupport.floatBuf(STATE_SIZE)
    private val c = TFLiteSupport.floatBuf(STATE_SIZE)
    private val out = TFLiteSupport.floatBuf(1)
    private val ctx = FloatArray(FRAME)  // rolling 64-sample context is kept at the front

    /** Reset LSTM state between utterances / sessions. */
    fun reset() {
        h.clear(); c.clear()
        h.asFloatBuffer().put(FloatArray(STATE_SIZE))
        c.asFloatBuffer().put(FloatArray(STATE_SIZE))
        h.rewind(); c.rewind()
    }

    /**
     * Feed one 512-sample frame; returns speech probability in 0..1.
     * Note: samples [0..63] are the carried context from the previous frame,
     * exactly like the reference SpeechGate.kt drives the model.
     */
    fun speechProbability(frame: FloatArray): Float {
        if (frame.size != FRAME) return 0f
        // Silero v4 wants 576 samples: 64 context + 512 new.
        val inBuf = TFLiteSupport.floatBuf(FRAME + CONTEXT)
        val combined = FloatArray(FRAME + CONTEXT)
        System.arraycopy(ctx, 0, combined, 0, CONTEXT)
        System.arraycopy(frame, 0, combined, CONTEXT, FRAME)
        inBuf.asFloatBuffer().put(combined)
        // Indexed IO (model order): inputs [input, h, c] -> outputs [output, hn, cn]
        val hn = TFLiteSupport.floatBuf(STATE_SIZE)
        val cn = TFLiteSupport.floatBuf(STATE_SIZE)
        val inputs = arrayOf<Any>(inBuf, h, c)
        val outputs = mapOf(0 to out, 1 to hn, 2 to cn)
        return try {
            interpreter.runForMultipleInputsOutputs(inputs, outputs)
            out.rewind()
            val p = out.asFloatBuffer().get(0)
            h.clear(); h.put(hn); h.rewind()
            c.clear(); c.put(cn); c.rewind()
            // keep last 64 samples as next context
            System.arraycopy(frame, FRAME - CONTEXT, ctx, 0, CONTEXT)
            p.coerceIn(0f, 1f)
        } catch (t: Throwable) {
            MayaLog.w("GUARDIAN", "VAD frame failed: ${t.message}")
            0f
        }
    }

    fun close() { interpreter.close() }

    companion object {
        const val ASSET = "guardian/silero_vad.tflite"
        const val FRAME = 512
        const val CONTEXT = 64
        const val STATE_SIZE = 2 * 64  // [2,1,64] flattened

        private const val inputName = "input"
        private const val outputName = "output"

        fun load(context: Context): SpeechGate? {
            val buf = TFLiteSupport.loadAsset(context, ASSET) ?: return null
            return try {
                val itp = TFLiteSupport.newInterpreter(buf, threads = 1)
                val gate = SpeechGate(itp)
                gate.reset()
                MayaLog.i("GUARDIAN", "Silero VAD loaded")
                gate
            } catch (t: Throwable) {
                MayaLog.e("GUARDIAN", "VAD load failed", t)
                null
            }
        }

        /**
         * DSP fallback (energy + zero-crossing rate) used when the model asset
         * is missing — weaker, but still filters steady ambient noise.
         */
        fun isSpeechDsp(frame: FloatArray, threshold: Float = 0.004f): Boolean {
            if (frame.isEmpty()) return false
            var energy = 0.0
            var zc = 0
            for (i in frame.indices) {
                energy += frame[i].toDouble() * frame[i]
                if (i > 0 && (frame[i] >= 0) != (frame[i - 1] >= 0)) zc++
            }
            val rms = kotlin.math.sqrt(energy / frame.size).toFloat()
            val zcr = zc.toFloat() / frame.size
            return rms > threshold && zcr in 0.01f..0.45f
        }
    }
}
