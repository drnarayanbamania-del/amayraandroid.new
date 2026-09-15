package com.amayra.maya.voice.wakeword

/**
 * Pure streaming math for [WakeWordEngine], split out for unit testing.
 * No TFLite/Android dependencies here.
 */
internal object WakeWordMath {

    /** Reference pipeline: the mel model expects int16-SCALE float PCM. */
    fun int16Scale(chunk: FloatArray): FloatArray =
        FloatArray(chunk.size) { chunk[it] * 32768f }

    /**
     * Ring of recent int16-scale audio bounded by total SAMPLES (not chunk
     * count — callers may feed variable-size chunks); serves the mel model's
     * fixed window (chunk + overlap) assembled in chronological order.
     */
    internal class RawRing(private val maxSamples: Int) {
        private val chunks = ArrayDeque<FloatArray>()
        private var total = 0

        fun append(chunk: FloatArray) {
            if (chunk.isEmpty()) return
            chunks.addLast(chunk.copyOf())
            total += chunk.size
            while (total > maxSamples && chunks.isNotEmpty()) {
                val first = chunks.first()
                val overflow = total - maxSamples
                if (overflow >= first.size) {
                    chunks.removeFirst()
                    total -= first.size
                } else {
                    // Split the oldest chunk, keeping only its fresh tail.
                    chunks.removeFirst()
                    chunks.addFirst(first.copyOfRange(overflow, first.size))
                    total -= overflow
                }
            }
        }

        /** Total samples currently held. */
        fun size(): Int = total

        /**
         * Last [n] samples in chronological order, or null if fewer are held.
         * Copies so the mel input buffer can be filled safely.
         */
        fun lastSamples(n: Int): FloatArray? {
            if (size() < n) return null
            val out = FloatArray(n)
            var dst = n
            for (buf in chunks.asReversed()) {
                if (dst == 0) break
                val take = minOf(dst, buf.size)
                buf.copyInto(out, dst - take, buf.size - take, buf.size)
                dst -= take
            }
            return out
        }

        fun clear() {
            chunks.clear()
            total = 0
        }
    }

    /**
     * Wake confirmation gate: a trigger only fires after [windowsNeeded]
     * consecutive raw scores at/above the passed threshold; the fire resets
     * the streak and returns 1f once. Threshold is per-call so runtime tuning
     * (engine.threshold) takes effect immediately.
     */
    internal class StreakGate(private val windowsNeeded: Int = 2) {
        private var streak = 0

        /** Feed a raw score; returns 1f exactly when a trigger fires, else the raw score. */
        fun score(raw: Float, threshold: Float): Float {
            if (raw >= threshold) streak++ else streak = 0
            if (streak >= windowsNeeded) {
                streak = 0
                return 1f
            }
            return raw
        }

        fun reset() { streak = 0 }
    }
}
