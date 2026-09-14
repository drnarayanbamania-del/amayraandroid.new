package com.amayra.maya.voice.guardian

import android.content.Context
import com.amayra.maya.core.MayaLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Voice Guardian — clean-room speaker verification pipeline.
 *
 * Pipeline: 16 kHz mono mic audio -> Silero VAD keeps only voiced frames ->
 * frames are CONCATENATED (never zero-padded) into a 96 000-sample (6 s) window,
 * repeating short speech to fill it (1.5 s speech repeated -> 1.43% EER vs 30%
 * zero-padded) -> ECAPA-TDNN 192-dim embedding -> AS-Norm against the stranger
 * cohort -> calibrated probability -> accept/reject.
 *
 * Each asset degrades gracefully on its own (missing model = inert Guardian,
 * missing VAD = DSP gate, missing cohort = scaled raw cosine).
 */
class VoiceGuardian private constructor(
    private val context: Context,
    val embedder: SpeakerEmbedder?,
    val vad: SpeechGate?
) {
    val store = VoicePrintStore(context)
    val normalizer: ScoreNormalizer =
        ScoreNormalizer.load(readAsset("guardian/cohort.bin"), SpeakerEmbedder.EMBED_DIM)
            ?: ScoreNormalizer.fallback()

    val isUsable: Boolean get() = embedder != null
    val modelId: String = modelStem(SpeakerEmbedder.ASSET)

    /**
     * Buffered voiced-audio collector. Feed raw mic frames; call [drainWindow]
     * to extract a 6 s window of concatenated speech.
     */
    class VoicedBuffer(private val maxSamples: Int = 96_000) {
        private val pool = ArrayList<FloatArray>(4096)
        private var total = 0

        val samples: Int get() = total

        fun addVoiced(frame: FloatArray) {
            if (total >= maxSamples) return
            pool.add(frame.copyOf())
            total += frame.size
        }

        fun isFull(): Boolean = total >= maxSamples

        /**
         * Concatenate collected voiced audio into exactly [maxSamples]:
         * repeat from the start if short (never zero-pad).
         */
        fun drainWindow(): FloatArray {
            val out = FloatArray(maxSamples)
            var written = 0
            var idx = 0
            while (written < maxSamples && pool.isNotEmpty()) {
                val chunk = pool[idx % pool.size]
                val toCopy = minOf(chunk.size, maxSamples - written)
                System.arraycopy(chunk, 0, out, written, toCopy)
                written += toCopy
                idx++
                if (idx >= pool.size) idx = 0
                if (idx == 0 && written < maxSamples && pool.size * pool[0].size < maxSamples / 8) {
                    // extremely short capture: repeat the whole pool once more at most
                }
            }
            pool.clear()
            total = 0
            return out
        }

        fun clear() { pool.clear(); total = 0 }
    }

    /**
     * VAD-driven capture: feed every mic frame here. Returns a full 6 s window
     * when enough voiced audio has accumulated, else null.
     */
    fun feedFrame(pcm: FloatArray, buffer: VoicedBuffer): FloatArray? {
        val voiced = if (vad != null) {
            val p = vad.speechProbability(pcm)
            p >= SPEECH_THRESHOLD
        } else {
            SpeechGate.isSpeechDsp(pcm)
        }
        if (voiced) buffer.addVoiced(pcm)
        return if (buffer.isFull()) buffer.drainWindow() else null
    }

    /** Enroll a new voice profile from a full 6 s window. */
    suspend fun enroll(label: String, window: FloatArray): EnrollResult = withContext(Dispatchers.Default) {
        val emb = embedder ?: return@withContext EnrollResult.ModelMissing
        val vec = emb.embed(window) ?: return@withContext EnrollResult.Error("Embedding failed — try again in a quieter spot.")
        val id = "vp_${System.currentTimeMillis()}"
        store.put(GuardianProfile.fromVector(id, label, modelId, System.currentTimeMillis(), vec))
        MayaLog.i("GUARDIAN", "Enrolled profile '$label' ($id)")
        EnrollResult.Ok(id)
    }

    /** Verify a 6 s window against enrolled profiles. Returns the best match. */
    suspend fun verify(window: FloatArray): VerifyResult = withContext(Dispatchers.Default) {
        val emb = embedder ?: return@withContext VerifyResult.GuardianInert
        val profiles = store.all().filter { it.modelId == modelId }
        if (profiles.isEmpty()) return@withContext VerifyResult.NoProfiles
        val vec = emb.embed(window) ?: return@withContext VerifyResult.Error("Embedding failed.")
        var bestId: String? = null
        var bestLabel = ""
        var bestScore = -1f
        for (p in profiles) {
            val ref = p.toVector()
            val cos = SpeakerEmbedder.cosine(vec, ref)
            val adapted = normalizer.normalize(cos, vec)
            val prob = if (normalizer.cohortSize > 0) normalizer.calibrated(adapted) else adapted * RAW_ACCEPT_PROXY
            if (prob > bestScore) { bestScore = prob; bestId = p.id; bestLabel = p.label }
        }
        val accept = bestScore >= ACCEPT_PROBABILITY
        MayaLog.i("GUARDIAN", "verify: best=$bestLabel score=%.3f accept=$accept".format(bestScore))
        VerifyResult.Scored(bestLabel, bestScore, accept)
    }

    sealed class EnrollResult {
        data object ModelMissing : EnrollResult()
        data class Ok(val id: String) : EnrollResult()
        data class Error(val message: String) : EnrollResult()
    }

    sealed class VerifyResult {
        object GuardianInert : VerifyResult()      // no model: Maya runs exactly as before
        object NoProfiles : VerifyResult()          // no enrollment yet
        data class Scored(val label: String, val probability: Float, val accepted: Boolean) : VerifyResult()
        data class Error(val message: String) : VerifyResult()
    }

    private fun readAsset(path: String): ByteArray? = try {
        context.assets.open(path).use { it.readBytes() }
    } catch (_: Throwable) { null }

    companion object {
        const val SPEECH_THRESHOLD = 0.5f
        const val ACCEPT_PROBABILITY = 0.55f
        const val RAW_ACCEPT_PROXY = 3.0f // raw cosine * proxy must exceed ACCEPT_PROBABILITY

        private fun modelStem(path: String) = path.substringAfterLast('/').substringBeforeLast('.')

        @Volatile private var instance: VoiceGuardian? = null

        fun init(context: Context): VoiceGuardian {
            instance?.let { return it }
            synchronized(this) {
                instance?.let { return it }
                val emb = SpeakerEmbedder.load(context)
                val vad = SpeechGate.load(context)
                val g = VoiceGuardian(context.applicationContext, emb, vad)
                instance = g
                MayaLog.i("GUARDIAN", "VoiceGuardian ready (model=${emb != null}, vad=${vad != null}, cohort=${g.normalizer.cohortSize})")
                return g
            }
        }

        fun get(): VoiceGuardian? = instance
    }
}
