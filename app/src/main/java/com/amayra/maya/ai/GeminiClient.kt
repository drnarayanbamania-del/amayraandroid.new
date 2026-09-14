package com.amayra.maya.ai

import com.amayra.maya.core.MayaLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.add
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Google Gemini provider (REST, streamGenerateContent with SSE alt).
 * Contract: v1beta/models/{model}:streamGenerateContent?alt=sse&key=API_KEY
 *
 * Full tool round-trip: declarations are sent as `tools.functionDeclarations`,
 * model function calls arrive as `parts[].functionCall` (emitted as
 * [ChatStreamEvent.ToolCallDetected]), and executed results are fed back as
 * `functionResponse` parts so the model can produce the final answer.
 */
class GeminiClient(
    private val http: OkHttpClient,
    private val keyProvider: () -> String?,
    /** Overridable for tests / Gemini-compatible proxies; must not end with '/'. */
    private val baseUrl: String = "https://generativelanguage.googleapis.com/v1beta"
) : AiProvider {

    override val id = "gemini"
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun chatStream(
        req: ChatRequest,
        onEvent: suspend (ChatStreamEvent) -> Unit
    ) {
        val key = keyProvider()
        if (key.isNullOrBlank()) {
            onEvent(ChatStreamEvent.Failed(AiError.Authentication("No Gemini API key set. Add it in Settings → AI.")))
            return
        }
        // Newer GA models live on v1; v1beta keeps older/experimental ones. If the
        // first endpoint 404s, retry the other once before reporting failure.
        val base = baseUrl.trimEnd('/')
        val endpoints = when {
            base.endsWith("/v1beta") -> listOf(base, base.removeSuffix("v1beta") + "v1")
            base.endsWith("/v1") -> listOf(base, base.removeSuffix("v1") + "v1beta")
            else -> listOf("$base/v1beta", "$base/v1")
        }
        var attempt = 0
        while (true) {
        val url = "${endpoints[attempt]}/models/${req.model}:streamGenerateContent?alt=sse&key=$key"
        val request = Request.Builder()
            .url(url)
            .post(buildBody(req).toString().toRequestBody("application/json".toMediaType()))
            .build()

        try {
            // Callers may invoke this from Main (e.g. rememberCoroutineScope in Compose);
            // OkHttp's execute() is a blocking network op and must stay off the main thread.
            withContext(Dispatchers.IO) {
            http.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val err = resp.body?.string().orEmpty()
                    MayaLog.w("AI", "Gemini HTTP ${resp.code} (${endpoints[attempt]}): ${err.take(300)}")
                    if (resp.code == 404 && attempt == 0) { attempt = 1; return@withContext }  // retry on the other API version
                    val error: AiError = when {
                        resp.code == 401 || (resp.code == 400 && err.contains("API key", ignoreCase = true)) ->
                            AiError.Authentication("Gemini rejected the key (HTTP ${resp.code}): ${serverMessage(err) ?: "check the key in Settings → AI"}")
                        resp.code == 429 -> AiError.RateLimit()
                        resp.code == 404 ->
                            AiError.Provider("Model not found: ${req.model} on either API endpoint. Check the model name in Settings.")
                        else -> AiError.Provider("Gemini HTTP ${resp.code}: ${err.take(300)}")
                    }
                    onEvent(ChatStreamEvent.Failed(error))
                    return@withContext
                }
                val reader = resp.body?.charStream()?.buffered() ?: return@withContext
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.startsWith("data:")) {
                        val payload = line.removePrefix("data:").trim()
                        if (payload == "[DONE]") break
                        parseChunk(payload, onEvent)
                    }
                }
                onEvent(ChatStreamEvent.Done("stop"))
            }
            }
            return
        } catch (e: java.net.UnknownHostException) {
            onEvent(ChatStreamEvent.Failed(AiError.Network("Can't reach Gemini — DNS lookup failed. Check the phone's internet (try loading a website), then retry.")))
        } catch (e: javax.net.ssl.SSLException) {
            onEvent(ChatStreamEvent.Failed(AiError.Network("Secure connection to Gemini failed (${e.message?.take(80)}). If a VPN/proxy is on, try turning it off.")))
        } catch (e: java.io.IOException) {
            onEvent(ChatStreamEvent.Failed(AiError.Network("Connection to Gemini dropped (${e.message?.take(80)}). Check internet and retry.")))
        } catch (t: Throwable) {
            MayaLog.w("AI", "Gemini stream failed: ${t.message ?: t.javaClass.simpleName}")
            onEvent(ChatStreamEvent.Failed(AiError.Network(cause = t)))
        }
        }
    }

    /** Extracts the human-readable `message` field from Gemini's error JSON. */
    private fun serverMessage(err: String): String? = runCatching {
        json.parseToJsonElement(err).jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
    }.getOrNull() ?: err.take(160).ifBlank { null }

    private suspend fun parseChunk(payload: String, onEvent: suspend (ChatStreamEvent) -> Unit) {
        val obj = try { json.parseToJsonElement(payload).jsonObject } catch (t: Throwable) { return }
        val candidates = obj["candidates"]?.jsonArray ?: return
        if (candidates.isEmpty()) return
        val cand = candidates[0].jsonObject
        cand["content"]?.jsonObject?.get("parts")?.jsonArray?.forEach { p ->
            val part = p.jsonObject
            // Text deltas.
            part["text"]?.jsonPrimitive?.contentOrNull?.let {
                if (it.isNotEmpty()) onEvent(ChatStreamEvent.Deltas(it))
            }
            // Function call: emit as ToolCallDetected with the raw args JSON.
            part["functionCall"]?.jsonObject?.let { fc ->
                val name = fc["name"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                val argsJson = (fc["args"] as? JsonObject)?.toString() ?: "{}"
                // Gemini 3.x requires the thought_signature replayed with the
                // functionCall on the next round; keep it alongside the args.
                val thoughtSig = part["thoughtSignature"]?.jsonPrimitive?.contentOrNull
                    ?: part["thought_signature"]?.jsonPrimitive?.contentOrNull
                onEvent(
                    ChatStreamEvent.ToolCallDetected(
                        ToolCall(
                            id = "call_${callSeq.incrementAndGet()}",
                            name = name,
                            argumentsJson = argsJson,
                            thoughtSignature = thoughtSig
                        )
                    )
                )
            }
        }
        cand["finishReason"]?.jsonPrimitive?.contentOrNull?.let {
            if (it != "FINISH_REASON_UNSPECIFIED") onEvent(ChatStreamEvent.Done(it))
        }
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
            // Gemini REST form: "tools": [ { "functionDeclarations": [ ... ] } ]
            // (tools is an ARRAY of tool objects, not an object).
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

    /** Maps one provider-neutral message to Gemini parts. */
    private fun partsFor(m: ChatMessage): List<JsonObject> = when {
        // Model's function call replay: {"functionCall":{"name":..,"args":{...}}}
        m.role == "assistant" && m.toolName != null -> listOf(
            buildJsonObject {
                putJsonObject("functionCall") {
                    put("name", m.toolName)
                    put("args", runCatching { json.parseToJsonElement(m.toolArgsJson ?: "{}") }
                        .getOrDefault(JsonObject(emptyMap())))
                }
                m.thoughtSignature?.let { put("thought_signature", it) }
            }
        )
        // Tool result: user role + functionResponse (+ optional inline image,
        // e.g. a pc_screenshot the model should visually verify).
        m.role == "tool" && m.toolName != null -> buildList {
            add(buildJsonObject {
                putJsonObject("functionResponse") {
                    put("name", m.toolName)
                    putJsonObject("response") { put("result", m.content) }
                }
            })
            m.imageUri?.let { img ->
                val idx = img.indexOf(";base64,")
                if (img.startsWith("data:") && idx > 0) {
                    add(buildJsonObject {
                        putJsonObject("inline_data") {
                            put("mime_type", img.substring(5, idx))
                            put("data", img.substring(idx + 8))
                        }
                    })
                } else if (img.endsWith(".jpg") || img.endsWith(".jpeg")) {
                    // Local file path from a tool (pc_screenshot): load and inline it.
                    runCatching {
                        val bytes = java.io.File(img).readBytes()
                        add(buildJsonObject {
                            putJsonObject("inline_data") {
                                put("mime_type", "image/jpeg")
                                put("data", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP))
                            }
                        })
                    }
                }
            }
        }
        // Plain text (+ optional inline image).
        else -> buildList {
            add(buildJsonObject { put("text", m.content) })
            m.imageUri?.let { img ->
                val idx = img.indexOf(";base64,")
                if (img.startsWith("data:") && idx > 0) {
                    add(buildJsonObject {
                        putJsonObject("inline_data") {
                            put("mime_type", img.substring(5, idx))
                            put("data", img.substring(idx + 8))
                        }
                    })
                }
            }
        }
    }

    private companion object {
        val callSeq = AtomicInteger(0)
    }
}
