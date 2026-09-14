package com.getmaya.android.core

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Gemini client. The APK evidence: mic audio streams directly to the Google
 * Gemini API; the key is stored in Keystore-backed EncryptedSharedPreferences
 * and is never sent anywhere but Google. Blank key = "Maya's own key" behavior
 * is NOT reproduced (we never had that key) — users must supply their own.
 */
class GeminiLiveClient(private val apiKey: String) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    /** Simple text turn; voice/Live streaming (WebSocket) is the next phase. */
    fun chat(prompt: String): Result<String> = runCatching {
        val body = JSONObject().apply {
            put("contents", JSONArray().put(
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))
            ))
        }.toString()

        val req = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=$apiKey")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            check(resp.isSuccessful) { "Gemini error ${resp.code}: $text" }
            JSONObject(text)
                .getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts")
                .getJSONObject(0).getString("text")
        }
    }
}
