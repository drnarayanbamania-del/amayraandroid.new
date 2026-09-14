package com.amayra.pc.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Google Gemini REST client (non-streaming generateContent is enough for the
 * agent loop; streaming adds latency complexity without benefit for tools).
 * Mirrors the Android GeminiClient contract: functionDeclarations out,
 * functionCall in, functionResponse back.
 */
class GeminiClient(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build(),
    private val keyProvider: () -> String?,
    private val baseUrl: String = "https://generativelanguage.googleapis.com/v1beta"
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun chat(req: ChatRequest): ChatResponse = withContext(Dispatchers.IO) {
        val key = keyProvider()?.takeIf { it.isNotBlank() }
            ?: throw AiError.Authentication("No Gemini API key configured (env GEMINI_API_KEY or config/config.json).")
        val url = "${baseUrl.trimEnd('/')}/models/${req.model}:generateContent?key=$key"
        val request = Request.Builder()
            .url(url)
            .post(buildBody(req).toString().toRequestBody("application/json".toMediaType()))
            .build()

        try {
            http.newCall(request).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val msg = runCatching {
                        json.parseToJsonElement(body).jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content
                    }.getOrNull() ?: body.take(200)
                    throw when {
                        resp.code == 401 || resp.code == 400 && msg.contains("API key", true) -> AiError.Authentication(msg)
                        resp.code == 429 -> AiError.RateLimit(msg)
                        else -> AiError.Provider("Gemini HTTP ${resp.code}: $msg")
                    }
                }
                parse(json.parseToJsonElement(body).jsonObject)
            }
        } catch (e: AiError) {
            throw e
        } catch (e: java.net.UnknownHostException) {
            throw AiError.Network("Cannot reach Gemini — check internet connection.", e)
        } catch (e: java.io.IOException) {
            throw AiError.Network("Connection to Gemini failed: ${e.message}", e)
        }
    }

    private fun parse(obj: JsonObject): ChatResponse {
        val cand = obj["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: throw AiError.Provider("Gemini returned no candidates: ${obj.toString().take(200)}")
        val parts = cand["content"]?.jsonObject?.get("parts")?.jsonArray ?: JsonArray(emptyList())
        var text: String? = null
        val calls = mutableListOf<ToolCall>()
        parts.forEach { p ->
            val part = p.jsonObject
            part["text"]?.jsonPrimitive?.contentOrNull?.let { t -> text = (text ?: "") + t }
            part["functionCall"]?.jsonObject?.let { fc ->
                val name = fc["name"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                val args = (fc["args"] as? JsonObject)?.toString() ?: "{}"
                val sig = part["thoughtSignature"]?.jsonPrimitive?.contentOrNull
                    ?: part["thought_signature"]?.jsonPrimitive?.contentOrNull
                calls += ToolCall(id = "call_${System.nanoTime()}", name = name, argumentsJson = args, thoughtSignature = sig)
            }
        }
        return ChatResponse(text = text, toolCalls = calls)
    }

    private fun buildBody(req: ChatRequest) = buildJsonObject {
        put("contents", JsonArray(req.messages.filter { it.role != "system" }.map { m ->
            buildJsonObject {
                put("role", if (m.role == "assistant") "model" else "user")
                put("parts", JsonArray(partsFor(m)))
            }
        }))
        req.messages.firstOrNull { it.role == "system" }?.let {
            putJsonObject("systemInstruction") {
                put("parts", JsonArray(listOf(buildJsonObject { put("text", it.content) })))
            }
        }
        if (req.tools.isNotEmpty()) {
            put("tools", JsonArray(listOf(buildJsonObject {
                put("functionDeclarations", JsonArray(req.tools.map { t ->
                    buildJsonObject {
                        put("name", t.name)
                        put("description", t.description)
                        put("parameters", t.parameters)
                    }
                }))
            })))
        }
        putJsonObject("generationConfig") {
            put("temperature", req.temperature.toDouble())
            put("maxOutputTokens", req.maxTokens)
        }
    }

    private fun partsFor(m: ChatMessage): List<JsonObject> = when {
        m.role == "assistant" && m.toolName != null -> listOf(buildJsonObject {
            putJsonObject("functionCall") {
                put("name", m.toolName)
                put("args", runCatching { json.parseToJsonElement(m.toolArgsJson ?: "{}") }.getOrDefault(JsonObject(emptyMap())))
            }
            // Gemini 3.x: the signature must be replayed with the functionCall.
            m.thoughtSignature?.let { put("thought_signature", it) }
        })
        m.role == "tool" && m.toolName != null -> buildList {
            add(buildJsonObject {
                putJsonObject("functionResponse") {
                    put("name", m.toolName)
                    putJsonObject("response") { put("result", m.content) }
                }
            })
            // Multimodal observe: the screenshot itself rides alongside the
            // functionResponse so the model can visually verify the screen.
            m.imageBase64?.let { b64 ->
                add(buildJsonObject {
                    putJsonObject("inline_data") {
                        put("mime_type", m.imageMime)
                        put("data", b64)
                    }
                })
            }
        }
        else -> buildList {
            add(buildJsonObject { put("text", m.content) })
            m.imageBase64?.let { b64 ->
                add(buildJsonObject {
                    putJsonObject("inline_data") {
                        put("mime_type", m.imageMime)
                        put("data", b64)
                    }
                })
            }
        }
    }
}
