package com.amayra.maya.voice.guardian

import android.content.Context
import com.amayra.maya.core.MayaLog
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import android.util.Base64
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Encrypted-at-rest voiceprint profiles. An embedding is 192 floats — a mathematical
 * fingerprint, not audio; still, it is stored under the app's private prefs only
 * (never exported, never logged).
 */
@Serializable
data class GuardianProfile(
    val id: String,
    val label: String,
    val modelId: String,          // asset stem, e.g. "ecapa6s" — profiles stop matching on model change
    val createdAt: Long,
    val embedding: String         // Base64 little-endian float[192]
) {
    fun toVector(): FloatArray {
        val raw = Base64.decode(embedding, Base64.NO_WRAP)
        val buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(raw.size / 4) { buf.float }
    }

    companion object {
        fun fromVector(id: String, label: String, modelId: String, createdAt: Long, v: FloatArray): GuardianProfile {
            val buf = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            for (f in v) buf.putFloat(f)
            return GuardianProfile(id, label, modelId, createdAt, Base64.encodeToString(buf.array(), Base64.NO_WRAP))
        }
    }
}

/** CRUD over GuardianProfile JSON in private SharedPreferences. */
class VoicePrintStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("maya_guardian", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    fun all(): List<GuardianProfile> =
        prefs.getString(KEY_INDEX, null).orEmpty().split(',').filter { it.isNotBlank() }.mapNotNull { id ->
            prefs.getString("$KEY_PREFIX$id", null)?.let { raw ->
                runCatching { json.decodeFromString<GuardianProfile>(raw) }.getOrNull()
            }
        }

    fun put(profile: GuardianProfile) {
        prefs.edit()
            .putString("$KEY_PREFIX${profile.id}", json.encodeToString(profile))
            .apply()
        val index = (prefs.getString(KEY_INDEX, null)?.split(',') ?: emptyList())
            .filter { it.isNotBlank() } + profile.id
        prefs.edit().putString(KEY_INDEX, index.distinct().joinToString(",")).apply()
    }

    fun remove(id: String) {
        prefs.edit().remove("$KEY_PREFIX$id").apply()
        val index = (prefs.getString(KEY_INDEX, null)?.split(',') ?: emptyList())
            .filter { it.isNotBlank() && it != id }
        prefs.edit().putString(KEY_INDEX, index.joinToString(",")).apply()
    }

    fun clear() {
        all().forEach { remove(it.id) }
    }

    companion object {
        private const val KEY_PREFIX = "vp_"
        private const val KEY_INDEX = "vp_index"
    }
}
