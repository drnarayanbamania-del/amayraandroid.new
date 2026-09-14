package com.amayra.maya.voice.guardian

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp

/**
 * AS-Norm score normalization against a cohort of 200 stranger voiceprints
 * (assets/guardian/cohort.bin).
 *
 * Format (v2): magic "MGCB" - version:Short - dim:Short - count:Int -
 * logistic a,b (2 floats) - count x dim float vectors. Little-endian, no audio inside.
 */
class ScoreNormalizer private constructor(
    private val cohort: Array<FloatArray>,
    private val logisticA: Float,
    private val logisticB: Float
) {
    val cohortSize: Int get() = cohort.size

    /**
     * Raw cosine similarity between an enrollment/verification embedding and a
     * stranger cohort, adapted after the user's own enrollment.
     */
    fun normalize(cosine: Float, enrolledEmbedding: FloatArray): Float {
        if (cohort.isEmpty()) return cosine * RAW_FALLBACK_SCALE
        // Top-K cohort scores for this speaker's embedding (adaptive s-norm).
        val k = minOf(TOP_K, cohort.size)
        var topSum = 0.0
        val scores = FloatArray(cohort.size)
        for (i in cohort.indices) {
            val s = SpeakerEmbedder.cosine(enrolledEmbedding, cohort[i])
            scores[i] = s
        }
        scores.sortDescending()
        for (i in 0 until k) topSum += scores[i]
        val topMean = (topSum / k).toFloat()
        return (cosine - topMean).coerceIn(-1f, 1f)
    }

    /** Map an adapted score through the calibrated logistic to a probability in 0..1. */
    fun calibrated(adapted: Float): Float {
        val z = (logisticA * adapted + logisticB).toDouble()
        return (1.0 / (1.0 + exp(-z))).toFloat().coerceIn(0f, 1f)
    }

    companion object {
        private const val MAGIC = "MGCB"
        private const val TOP_K = 10
        /** When no cohort ships, raw cosines are scaled down to fit the accept threshold. */
        const val RAW_FALLBACK_SCALE = 0.75f

        fun load(bytes: ByteArray?, embedDim: Int): ScoreNormalizer? {
            if (bytes == null || bytes.size < 16) return null
            return try {
                val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                val magic = ByteArray(4) { buf.get() }.decodeToString()
                if (magic != MAGIC) return null
                val version = buf.short.toInt()
                val dim = buf.short.toInt()
                val count = buf.int
                if (dim != embedDim || count <= 0) return null
                val a = buf.float
                val b = buf.float
                val cohort = Array(count) {
                    FloatArray(dim) { idx -> buf.float }
                }
                ScoreNormalizer(cohort, a, b)
            } catch (_: Throwable) {
                null
            }
        }

        /** Empty normalizer used when cohort.bin is absent — raw cosines, scaled. */
        fun fallback(): ScoreNormalizer = ScoreNormalizer(emptyArray(), 1f, 0f)
    }
}
