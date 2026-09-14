package com.amayra.maya.voice.guardian

import android.content.Context
import com.amayra.maya.core.MayaLog
import org.tensorflow.lite.Interpreter
import java.nio.FloatBuffer

/**
 * ECAPA-TDNN speaker embedder (assets/guardian/ecapa6s.tflite).
 *
 * Accepts a raw 16 kHz mono waveform window of exactly 96 000 samples (6 s).
 * Auto-detects raw-waveform vs fbank input modes; the shipped model is raw.
 * Output: 192-dim L2-normalised embedding. Floats live only in memory —
 * never persisted, never logged.
 */
class SpeakerEmbedder private constructor(
    private val interpreter: Interpreter,
    private val inputShape: IntArray,
    private val outputDim: Int
) {
    val windowSamples: Int =
        if (inputShape.size >= 2 && inputShape[1] > 1000) inputShape[1] else 96_000

    /** true when the model consumes raw waveform instead of log-mel features. */
    val rawWaveformMode: Boolean = inputShape.size == 2 || (inputShape.size == 3 && inputShape[2] == 1)

    /**
     * Embed a 6 s window of 16 kHz mono PCM. Content rules that drive accuracy
     * (concatenated voiced audio, not zero-padded silence) are enforced by the
     * caller — [VoiceGuardian]; this class only runs the model.
     */
    fun embed(window: FloatArray): FloatArray? {
        if (window.size != windowSamples) {
            MayaLog.w("GUARDIAN", "Window size mismatch: ${window.size} != $windowSamples")
            return null
        }
        val input = TFLiteSupport.floatBuf(windowSamples)
        input.asFloatBuffer().put(window)
        val outBuf = TFLiteSupport.floatBuf(outputDim)
        return try {
            if (rawWaveformMode) {
                interpreter.run(input, outBuf)
            } else {
                // fbank fallback: [1, T, 80] log-mel — computed by FBank features.
                val frames = inputShape.getOrNull(1) ?: 0
                if (frames <= 0) return null
                val fbank = FBank.logMel(window, frames)
                val fbuf = TFLiteSupport.floatBuf(fbank.size)
                fbuf.asFloatBuffer().put(fbank)
                interpreter.run(fbuf, outBuf)
            }
            val out = FloatArray(outputDim)
            outBuf.rewind()
            outBuf.asFloatBuffer().get(out)
            l2Normalize(out)
        } catch (t: Throwable) {
            MayaLog.e("GUARDIAN", "embed() failed", t)
            null
        }
    }

    fun close() { interpreter.close() }

    companion object {
        const val ASSET = "guardian/ecapa6s.tflite"
        const val EMBED_DIM = 192

        fun load(context: Context): SpeakerEmbedder? {
            val buf = TFLiteSupport.loadAsset(context, ASSET) ?: return null
            return try {
                val itp = TFLiteSupport.newInterpreter(buf, threads = 2)
                val shape = TFLiteSupport.inputShape(itp, 0) ?: intArrayOf(1, 96_000)
                val outShape = TFLiteSupport.outputShape(itp, 0) ?: intArrayOf(1, EMBED_DIM)
                val dim = if (outShape.size >= 2) outShape[1] else EMBED_DIM
                MayaLog.i("GUARDIAN", "ECAPA loaded: in=${shape.joinToString("x")} out dim=$dim raw=${shape.last() == 1 || shape.size == 2}")
                SpeakerEmbedder(itp, shape, dim)
            } catch (t: Throwable) {
                MayaLog.e("GUARDIAN", "ECAPA load failed", t)
                null
            }
        }

        fun l2Normalize(v: FloatArray): FloatArray {
            var sum = 0.0
            for (x in v) sum += x.toDouble() * x
            val n = kotlin.math.sqrt(sum).toFloat()
            if (n > 1e-9f) for (i in v.indices) v[i] = v[i] / n
            return v
        }

        fun cosine(a: FloatArray, b: FloatArray): Float {
            if (a.size != b.size) return 0f
            var dot = 0.0
            for (i in a.indices) dot += a[i].toDouble() * b[i].toDouble()
            // both are L2-normalised -> dot == cosine
            return dot.toFloat().coerceIn(-1f, 1f)
        }
    }
}

/**
 * Minimal 80-band log-mel front-end for fbank-mode models (fallback only).
 * Pure Kotlin; 25 ms window / 10 ms hop at 16 kHz.
 */
internal object FBank {
    private const val SAMPLE_RATE = 16_000
    private const val FRAME = 400          // 25 ms
    private const val HOP = 160            // 10 ms
    private const val FFT = 512
    private const val N_MELS = 80

    fun logMel(window: FloatArray, maxFrames: Int): FloatArray {
        val frames = minOf(maxFrames, (window.size - FRAME) / HOP + 1).coerceAtLeast(1)
        val melBank = melFilterbank()
        val out = FloatArray(frames * N_MELS)
        val re = FloatArray(FFT)
        val im = FloatArray(FFT)
        val hann = FloatArray(FRAME) { i ->
            (0.5 - 0.5 * Math.cos(2 * Math.PI * i / (FRAME - 1))).toFloat()
        }
        for (f in 0 until frames) {
            val off = f * HOP
            for (i in 0 until FRAME) re[i] = window[off + i] * hann[i]
            for (i in FRAME until FFT) re[i] = 0f
            im.fill(0f)
            // simple iterative radix-2 FFT
            fft(re, im)
            val mel = FloatArray(N_MELS)
            for (b in 0 until N_MELS) {
                var e = 0.0
                for (k in melBank[b].first..melBank[b].second) {
                    val w = melWeight(b, k)
                    val p = re[k] * re[k] + im[k] * im[k]
                    e += w * p
                }
                mel[b] = Math.log(e + 1e-10).toFloat()
            }
            // global CMVN-lite: per-frame mean removal
            var mean = 0f
            for (m in mel) mean += m
            mean /= N_MELS
            for (b in 0 until N_MELS) out[f * N_MELS + b] = mel[b] - mean
        }
        return out
    }

    private val hz2mel = { hz: Double -> 2595 * Math.log10(1 + hz / 700) }
    private val mel2hz = { m: Double -> 700 * (Math.pow(10.0, m / 2595) - 1) }

    private fun melFilterbank(): List<Pair<Int, Int>> {
        val nFft = FFT / 2 + 1
        val melPoints = (0..N_MELS + 1).map { mel2hz(hz2mel(0.0) + (hz2mel(SAMPLE_RATE / 2.0) - hz2mel(0.0)) * it / (N_MELS + 1)) }
        val bin = melPoints.map { (it * FFT / SAMPLE_RATE).toInt().coerceIn(0, nFft - 1) }
        return (0 until N_MELS).map { b -> bin[b] to bin[b + 2].coerceAtLeast(bin[b] + 1) }
    }

    private fun melWeight(b: Int, k: Int): Float {
        val melPoints = (0..N_MELS + 1).map { mel2hz(hz2mel(0.0) + (hz2mel(SAMPLE_RATE / 2.0) - hz2mel(0.0)) * it / (N_MELS + 1)) }
        val left = melPoints[b]; val center = melPoints[b + 1]; val right = melPoints[b + 2]
        val hz = k * SAMPLE_RATE.toDouble() / FFT
        return when {
            hz < left || hz > right -> 0f
            hz <= center -> ((hz - left) / (center - left)).toFloat()
            else -> ((right - hz) / (right - center)).toFloat()
        }
    }

    private fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size
        // bit reversal
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j or bit
            if (i < j) { re[i] = re[j].also { re[j] = re[i] }; im[i] = im[j].also { im[j] = im[i] } }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * Math.PI / len
            val wRe = Math.cos(ang).toFloat(); val wIm = Math.sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var curRe = 1f; var curIm = 0f
                for (k in 0 until len / 2) {
                    val uRe = re[i + k]; val uIm = im[i + k]
                    val vRe = re[i + k + len / 2] * curRe - im[i + k + len / 2] * curIm
                    val vIm = re[i + k + len / 2] * curIm + im[i + k + len / 2] * curRe
                    re[i + k] = uRe + vRe; im[i + k] = uIm + vIm
                    re[i + k + len / 2] = uRe - vRe; im[i + k + len / 2] = uIm - vIm
                    val nRe = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe; curRe = nRe
                }
                i += len
            }
            len = len shl 1
        }
    }
}
