package com.amayra.maya.voice.guardian

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ScoreNormalizerTest {

    private fun cohortBytes(dim: Int, vectors: List<FloatArray>, a: Float = 12f, b: Float = -2.4f): ByteArray {
        val buf = ByteBuffer.allocate(4 + 2 + 2 + 4 + 8 + vectors.size * dim * 4)
            .order(ByteOrder.LITTLE_ENDIAN)
        buf.put("MGCB".toByteArray())
        buf.putShort(2)             // version
        buf.putShort(dim.toShort()) // dim
        buf.putInt(vectors.size)    // count
        buf.putFloat(a); buf.putFloat(b)
        vectors.forEach { v -> require(v.size == dim); v.forEach { buf.putFloat(it) } }
        return buf.array()
    }

    @Test
    fun `parses valid cohort file`() {
        val cohort = listOf(FloatArray(4) { 0.1f }, FloatArray(4) { 0.2f }, FloatArray(4) { 0.3f })
        val n = ScoreNormalizer.load(cohortBytes(4, cohort), embedDim = 4)
        assertNotNull(n)
        assertEquals(3, n!!.cohortSize)
    }

    @Test
    fun `rejects wrong magic`() {
        val bad = cohortBytes(4, listOf(FloatArray(4))).also { it[0] = 'X'.code.toByte() }
        assertNull(ScoreNormalizer.load(bad, 4))
    }

    @Test
    fun `rejects dim mismatch`() {
        val n = ScoreNormalizer.load(cohortBytes(8, listOf(FloatArray(8))), embedDim = 192)
        assertNull(n)
    }

    @Test
    fun `normalize subtracts top-K cohort mean`() {
        val cohort = List(20) { i -> FloatArray(8) { j -> if (j == 0) i / 20f else 0f } }
        val n = ScoreNormalizer.load(cohortBytes(8, cohort), 8)!!
        val me = FloatArray(8); me[0] = 0.9f
        val raw = SpeakerEmbedder.cosine(me, me) // 1.0
        val adapted = n.normalize(raw, me)
        // Adapted must be lower than raw (cohort pulls it down) and in -1..1.
        assertTrue(adapted < raw)
        assertTrue(adapted in -1f..1f)
    }

    @Test
    fun `calibrated is monotonic and bounded`() {
        val n = ScoreNormalizer.load(cohortBytes(4, listOf(FloatArray(4))), 4)!!
        val low = n.calibrated(-0.5f)
        val mid = n.calibrated(0f)
        val high = n.calibrated(0.5f)
        assertTrue(low < mid && mid < high)
        assertTrue(low in 0f..1f && high in 0f..1f)
    }

    @Test
    fun `fallback scales raw cosine`() {
        val n = ScoreNormalizer.fallback()
        assertEquals(0, n.cohortSize)
        val adapted = n.normalize(0.8f, FloatArray(4))
        assertEquals(0.8f * ScoreNormalizer.RAW_FALLBACK_SCALE, adapted, 1e-5f)
    }
}

class SpeakerEmbedderMathTest {

    @Test
    fun `l2 normalize makes unit vector`() {
        val v = floatArrayOf(3f, 4f)
        SpeakerEmbedder.l2Normalize(v)
        assertEquals(1f, kotlin.math.sqrt(v[0] * v[0] + v[1] * v[1]), 1e-5f)
    }

    @Test
    fun `cosine of identical vectors is 1`() {
        val a = FloatArray(16) { (it % 7) / 7f }
        SpeakerEmbedder.l2Normalize(a)
        assertEquals(1f, SpeakerEmbedder.cosine(a, a), 1e-5f)
    }

    @Test
    fun `cosine of orthogonal vectors is 0`() {
        val a = floatArrayOf(1f, 0f)
        val b = floatArrayOf(0f, 1f)
        assertEquals(0f, SpeakerEmbedder.cosine(a, b), 1e-5f)
    }

    @Test
    fun `cosine handles size mismatch safely`() {
        assertEquals(0f, SpeakerEmbedder.cosine(floatArrayOf(1f), floatArrayOf(1f, 2f)))
    }
}
