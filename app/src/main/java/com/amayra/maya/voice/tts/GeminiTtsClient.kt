package com.amayra.maya.voice.tts

import com.amayra.maya.core.MayaLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Base64

/**
 * Gemini text-to-speech over the Gemini API.
 *
 * Two wire formats are attempted in order, because the TTS endpoint family has
 * evolved:
 *  1. `POST v1beta/interactions` — "input" + `response_format{type:audio}` +
 *     `speech_config:[{voice:...}]`; audio in `interaction.output_audio.data`.
 *  2. `POST v1beta/models/{model}:generateContent` — classic form with
 *     `responseModalities:["AUDIO"]` + `speechConfig.voiceConfig.prebuiltVoiceConfig.voiceName`;
 *     audio in `candidates[0].content.parts[0].inlineData.data`.
 *
 * Output is raw little-endian 16-bit PCM at 24 kHz, mono (base64-wire).
 * Failures are honest: null + MayaLog, never synthesized audio.
 */
class GeminiTtsClient(
    private val http: OkHttpClient,
    private val keyProvider: () -> String?,
    private val baseUrl: String = "https://generativelanguage.googleapis.com"
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Speak [text] with [voice]; returns PCM16 24 kHz mono or null. */
    suspend fun synthesize(
        text: String,
        voice: String,
        model: String = DEFAULT_MODEL,
        styleHint: String? = null
    ): PcmAudio? = withContext(Dispatchers.IO) {
        val key = keyProvider()
        if (key.isNullOrBlank()) {
            MayaLog.w("TTS", "No Gemini API key — Gemini TTS unavailable")
            return@withContext null
        }
        val prompt = if (styleHint.isNullOrBlank()) text else "Say in a $styleHint tone: $text"

        synthesizeViaGenerateContent(prompt, voice, model, key)
            ?: synthesizeViaInteractions(prompt, voice, model, key)
    }

    /** Wire format 1: interactions API. */
    private fun synthesizeViaInteractions(
        prompt: String, voice: String, model: String, key: String
    ): PcmAudio? = try {
        val body = buildJsonObject {
            put("model", model)
            put("input", prompt)
            put("response_format", JsonObject(mapOf("type" to kotlinx.serialization.json.JsonPrimitive("audio"))))
            put("generation_config", JsonObject(mapOf(
                "speech_config" to kotlinx.serialization.json.JsonArray(listOf(
                    JsonObject(mapOf("voice" to kotlinx.serialization.json.JsonPrimitive(voice)))
                ))
            )))
        }
        val req = Request.Builder()
            .url("$baseUrl/v1beta/interactions")
            .header("x-goog-api-key", key)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                MayaLog.w("TTS", "interactions HTTP ${resp.code}: ${resp.body?.string()?.take(200)}")
                return@use null
            }
            val obj = json.parseToJsonElement(resp.body?.string().orEmpty()).let { it as? JsonObject }
            // interaction.output_audio.data (rest shorthand) or nested candidates
            val b64 = obj?.get("output_audio")?.jsonObject?.get("data")?.jsonPrimitive?.contentOrNull
                ?: obj?.get("interaction")?.jsonObject
                    ?.get("output_audio")?.jsonObject?.get("data")?.jsonPrimitive?.contentOrNull
            if (b64.isNullOrBlank()) {
                MayaLog.w("TTS", "interactions: no output_audio.data in response")
                null
            } else {
                decodePcm(b64)
            }
        }
    } catch (t: Throwable) {
        MayaLog.w("TTS", "interactions call failed: ${t.message}")
        null
    }

    /** Wire format 2: classic generateContent with responseModalities AUDIO. */
    private fun synthesizeViaGenerateContent(
        prompt: String, voice: String, model: String, key: String
    ): PcmAudio? {
        return try {
        val body = buildJsonObject {
            put("contents", kotlinx.serialization.json.JsonArray(listOf(
                JsonObject(mapOf(
                    "parts" to kotlinx.serialization.json.JsonArray(listOf(
                        JsonObject(mapOf("text" to kotlinx.serialization.json.JsonPrimitive(prompt)))
                    ))
                ))
            )))
            put("generationConfig", JsonObject(mapOf(
                "responseModalities" to kotlinx.serialization.json.JsonArray(
                    listOf(kotlinx.serialization.json.JsonPrimitive("AUDIO"))
                ),
                "speechConfig" to JsonObject(mapOf(
                    "voiceConfig" to JsonObject(mapOf(
                        "prebuiltVoiceConfig" to JsonObject(mapOf(
                            "voiceName" to kotlinx.serialization.json.JsonPrimitive(voice)
                        ))
                    ))
                ))
            )))
        }
        // v1beta only: the GA v1 endpoint rejects speechConfig with a guaranteed
        // 400 — probing it first burned one wasted (quota-metered) call per TTS
        // request. Preview TTS models are served on v1beta.
        val ep = baseUrl.trimEnd('/') + "/v1beta"
        run {
        val req = Request.Builder()
            .url("$ep/models/$model:generateContent?key=$key")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { resp ->
            MayaLog.d("TTS", "generateContent $ep -> HTTP ${resp.code}")
            if (!resp.isSuccessful) {
                MayaLog.w("TTS", "generateContent $ep HTTP ${resp.code}: ${resp.body?.string()?.take(200)}")
                return@use null
            }
            val rawBody = resp.body?.string().orEmpty()
            val obj = json.parseToJsonElement(rawBody).let { it as? JsonObject }
            val b64 = obj?.get("candidates")?.jsonArray?.firstOrNull()?.jsonObject
                ?.get("content")?.jsonObject
                ?.get("parts")?.jsonArray?.firstOrNull()?.jsonObject
                ?.get("inlineData")?.jsonObject?.get("data")?.jsonPrimitive?.contentOrNull
                // some wire revisions use snake_case inline_data
                ?: obj?.get("candidates")?.jsonArray?.firstOrNull()?.jsonObject
                    ?.get("content")?.jsonObject
                    ?.get("parts")?.jsonArray?.firstOrNull()?.jsonObject
                    ?.get("inline_data")?.jsonObject?.get("data")?.jsonPrimitive?.contentOrNull
            if (b64.isNullOrBlank()) {
                MayaLog.w("TTS", "generateContent $ep: no inline audio in response: ${rawBody.take(200)}")
                null
                } else {
                    decodePcm(b64)
                }
            }.also { r -> if (r != null) return r }
        }
        return null
        } catch (t: Throwable) {
            MayaLog.w("TTS", "generateContent call failed: ${t.message}")
            null
        }
    }

    private fun decodePcm(b64: String): PcmAudio? = try {
        val bytes = Base64.getDecoder().decode(b64)
        if (bytes.isEmpty()) null else PcmAudio(bytes, SAMPLE_RATE, channels = 1)
    } catch (t: Throwable) {
        MayaLog.w("TTS", "audio decode failed: ${t.message}")
        null
    }

    data class PcmAudio(val pcm: ByteArray, val sampleRate: Int, val channels: Int)

    /** Voice selection for one utterance (resolved from the active persona). */
    data class PersonaVoice(val voice: String, val model: String = DEFAULT_MODEL, val styleHint: String? = null)

    companion object {
        const val DEFAULT_MODEL = "gemini-3.1-flash-tts-preview"
        const val SAMPLE_RATE = 24_000
    }
}
