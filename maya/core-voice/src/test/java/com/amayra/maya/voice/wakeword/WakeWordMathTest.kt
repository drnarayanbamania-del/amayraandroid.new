package com.amayra.maya.voice.wakeword

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the streaming math behind [WakeWordEngine]: int16-scale input for the
 * mel model, chronological raw-sample ring with sample-based bounding, and
 * the two-window confirmation gate.
 */
class WakeWordMathTest {

    // ── int16Scale ───────────────────────────────────────────────────────

    @Test
    fun `int16Scale maps unit range to int16 range`() {
        val out = WakeWordMath.int16Scale(floatArrayOf(-1f, 0f, 0.5f, 1f))
        assertEquals(-32768f, out[0], 0.01f)
        assertEquals(0f, out[1], 0f)
        assertEquals(16384f, out[2], 0.01f)
        assertEquals(32768f, out[3], 0.01f)
    }

    // ── RawRing ──────────────────────────────────────────────────────────

    @Test
    fun `ring holds everything under capacity`() {
        val ring = WakeWordMath.RawRing(maxSamples = 10)
        ring.append(floatArrayOf(1f, 2f, 3f))
        assertEquals(3, ring.size())
        assertTrue(ring.lastSamples(3)!!.contentEquals(floatArrayOf(1f, 2f, 3f)))
    }

    @Test
    fun `ring drops oldest when over capacity`() {
        val ring = WakeWordMath.RawRing(maxSamples = 4)
        ring.append(floatArrayOf(1f, 2f, 3f))
        ring.append(floatArrayOf(4f, 5f, 6f)) // total 6 > 4 -> keep last 4
        assertEquals(4, ring.size())
        assertTrue(ring.lastSamples(4)!!.contentEquals(floatArrayOf(3f, 4f, 5f, 6f)))
    }

    @Test
    fun `ring splits overflowing oldest chunk keeping its tail`() {
        val ring = WakeWordMath.RawRing(maxSamples = 2500)
        val c1 = FloatArray(1280) { it.toFloat() }
        val c2 = FloatArray(1280) { (1280 + it).toFloat() }
        val c3 = FloatArray(1280) { (2560 + it).toFloat() }
        ring.append(c1); ring.append(c2); ring.append(c3)
        assertEquals(2500, ring.size())
        val last = ring.lastSamples(2240)!!
        // Last 2240 of samples 0..3839 => indices 1600..3839.
        assertEquals(1600f, last[0], 0f)
        assertEquals(3839f, last[2239], 0f)
    }

    @Test
    fun `lastSamples assembles chronological order across chunks`() {
        val ring = WakeWordMath.RawRing(maxSamples = 100)
        ring.append(floatArrayOf(0f, 1f, 2f))
        ring.append(floatArrayOf(3f, 4f))
        assertTrue(ring.lastSamples(5)!!.contentEquals(floatArrayOf(0f, 1f, 2f, 3f, 4f)))
    }

    @Test
    fun `lastSamples returns null when insufficient data`() {
        val ring = WakeWordMath.RawRing(maxSamples = 100)
        ring.append(floatArrayOf(1f, 2f))
        assertNull(ring.lastSamples(3))
    }

    @Test
    fun `empty append is ignored and clear resets`() {
        val ring = WakeWordMath.RawRing(maxSamples = 10)
        ring.append(FloatArray(0))
        assertEquals(0, ring.size())
        ring.append(floatArrayOf(7f, 8f))
        ring.clear()
        assertEquals(0, ring.size())
        assertNull(ring.lastSamples(1))
    }

    // ── StreakGate ───────────────────────────────────────────────────────

    @Test
    fun `single hot window does not fire`() {
        val gate = WakeWordMath.StreakGate()
        val s = gate.score(0.9f, threshold = 0.75f)
        assertFalse(s >= 1f)
    }

    @Test
    fun `two consecutive hot windows fire once then require re-accumulation`() {
        val gate = WakeWordMath.StreakGate()
        gate.score(0.9f, threshold = 0.75f)
        val fire = gate.score(0.9f, threshold = 0.75f)
        assertTrue(fire >= 1f)
        // Immediately after firing, one more hot window must NOT re-fire.
        assertFalse(gate.score(0.9f, threshold = 0.75f) >= 1f)
    }

    @Test
    fun `cold window resets the streak`() {
        val gate = WakeWordMath.StreakGate()
        gate.score(0.9f, threshold = 0.75f)
        gate.score(0.1f, threshold = 0.75f) // cold
        assertFalse(gate.score(0.9f, threshold = 0.75f) >= 1f)
    }

    @Test
    fun `threshold applies per call so runtime tuning takes effect`() {
        val gate = WakeWordMath.StreakGate()
        gate.score(0.8f, threshold = 0.9f)  // not hot for 0.9
        assertFalse(gate.score(0.8f, threshold = 0.75f) >= 1f) // previous window was cold
    }
}
