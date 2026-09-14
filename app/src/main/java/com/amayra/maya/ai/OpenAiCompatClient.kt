package com.amayra.maya.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * OpenAI-compatible chat-completions provider. Works with OpenAI, Groq,
 * OpenRouter, LM Studio / Ollama and any endpoint implementing
 * POST {baseUrl}/chat/completions with SSE streaming.
 */
class OpenAiCompatClient(
    private val http: OkHttpClient,
    private val baseUrlProvider: () -> String,
    private val keyProvider: () -> String?
) : AiProvider {

    override val id = "openai_compat"
    private val json = Json { ignoreUnknownKeys = true }

    private class ToolAccum(var id: String, val name: StringBuilder, val args: StringBuilder)

    override suspend fun chatStream(
        req: ChatRequest,
        onEvent: suspend (ChatStreamEvent) -> Unit
    ) {
        val key = keyProvider()
        if (key.isNullOrBlank()) {
            onEvent(ChatStreamEvent.Failed(AiError.Authentication("No API key set. Add it in Settings → AI.")))
            return
        }
        val base = baseUrlProvider().trimEnd('/')
        val url = "$base/chat/completions"
        val body = buildJsonObject {
            put("model", req.model)
            put("stream", true)
            put("temperature", req.temperature.toDouble())
            put("max_tokens", req.maxTokens)
            put("messages", JsonArray(req.messages.map { m ->
                buildJsonObject {
                    put("role", m.role)
                    if (m.imageUri != null) {
                        // OpenAI vision form: content as array with image_url part.
                        // Local file paths (pc_screenshot tool output) are inlined as data URLs.
                        put("content", buildImageContent(m.copy(imageUri = asDataUrl(m.imageUri))))
                    } else {
                        put("content", m.content)
                    }
                }
            }))
            if (req.tools.isNotEmpty()) {
                put("tools", JsonArray(req.tools.map { t ->
                    buildJsonObject {
                        put("type", "function")
                        put("function", buildJsonObject {
                            put("name", t.name)
                            put("description", t.description)
                            put("parameters", JsonObject(t.parameters))
                        })
                    }
                }))
            }
        }

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $key")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        try {
            // Blocking network op — must stay off the main thread regardless of caller.
            withContext(Dispatchers.IO) {
            http.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val err = resp.body?.string().orEmpty()
                    val error: AiError = when (resp.code) {
                        401, 403 -> AiError.Authentication()
                        429 -> AiError.RateLimit()
                        404 -> AiError.Provider("Endpoint or model not found at $base (HTTP 404). Check Base URL / model.")
                        else -> AiError.Provider("HTTP ${resp.code}: ${err.take(300)}")
                    }
                    onEvent(ChatStreamEvent.Failed(error))
                    return@withContext
                }
                val reader = resp.body?.charStream()?.buffered() ?: return@withContext
                val toolAccumulator = linkedMapOf<String, ToolAccum>()
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.startsWith("data:")) {
                        val payload = line.removePrefix("data:").trim()
                        if (payload == "[DONE]") break
                        parseChunk(payload, toolAccumulator, onEvent)
                    }
                }
                onEvent(ChatStreamEvent.Done("stop"))
            }
            }
        } catch (e: java.net.UnknownHostException) {
            onEvent(ChatStreamEvent.Failed(AiError.Network("Can't reach the provider — DNS lookup failed. Check the phone's internet, then retry.")))
        } catch (e: java.io.IOException) {
            onEvent(ChatStreamEvent.Failed(AiError.Network("Connection dropped (${e.message?.take(80)}). Check internet and retry.")))
        } catch (t: Throwable) {
            onEvent(ChatStreamEvent.Failed(AiError.Network(cause = t)))
        }
    }

    private suspend fun parseChunk(
        payload: String,
        toolAccumulator: LinkedHashMap<String, ToolAccum>,
        onEvent: suspend (ChatStreamEvent) -> Unit
    ) {
        val obj = try { json.parseToJsonElement(payload).jsonObject } catch (t: Throwable) { return }
        val choices = obj["choices"]?.jsonArray ?: return
        if (choices.isEmpty()) return
        val choice = choices[0].jsonObject
        (choice["delta"]?.jsonObject ?: choice["message"]?.jsonObject)?.let { delta ->
            delta["content"]?.jsonPrimitive?.contentOrNull?.let {
                if (it.isNotEmpty()) onEvent(ChatStreamEvent.Deltas(it))
            }
            // Accumulate streamed tool_call fragments (OpenAI deltas by index).
            delta["tool_calls"]?.jsonArray?.forEach { tc ->
                val entry = tc.jsonObject
                val index = (entry["index"]?.jsonPrimitive?.contentOrNull ?: "0")
                val fn = entry["function"]?.jsonObject
                val nameDelta = fn?.get("name")?.jsonPrimitive?.contentOrNull.orEmpty()
                val argsDelta = fn?.get("arguments")?.jsonPrimitive?.contentOrNull.orEmpty()
                val id = entry["id"]?.jsonPrimitive?.contentOrNull ?: "call_$index"
                val acc = toolAccumulator.getOrPut(index) {
                    ToolAccum(id = id, name = StringBuilder(), args = StringBuilder())
                }
                if (id != acc.id) acc.id = id
                acc.name.append(nameDelta)
                acc.args.append(argsDelta)
            }
        }
        choice["finish_reason"]?.jsonPrimitive?.contentOrNull?.let {
            // Flush accumulated tool calls at finish.
            toolAccumulator.values
                .filter { acc -> acc.name.isNotBlank() }
                .forEach { acc ->
                    onEvent(
                        ChatStreamEvent.ToolCallDetected(
                            ToolCall(
                                id = acc.id.ifBlank { "call_${acc.name}" },
                                name = acc.name.toString(),
                                argumentsJson = acc.args.toString().ifBlank { "{}" }
                            )
                        )
                    )
                }
            toolAccumulator.clear()
            onEvent(ChatStreamEvent.Done(it))
        }
    }

    /** Convert a local file path image reference into a JPEG data URL. */
    private fun asDataUrl(uri: String?): String? {
        if (uri == null || uri.startsWith("data:") || uri.startsWith("http")) return uri
        return runCatching {
            val bytes = java.io.File(uri).readBytes()
            "data:image/jpeg;base64," + android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        }.getOrNull() ?: uri
    }

    private fun buildImageContent(m: ChatMessage): JsonArray = JsonArray(listOf(
        buildJsonObject {
            put("type", "text")
            put("text", m.content)
        },
        buildJsonObject {
            put("type", "image_url")
            put("image_url", buildJsonObject { put("url", m.imageUri ?: "") })
        }
    ))

    companion object {
        /** Curated base URLs offered in Settings (user-editable, not hard-coded into calls). */
        val PRESET_BASE_URLS = listOf(
            "https://api.groq.com/openai/v1",
            "https://openrouter.ai/api/v1",
            "https://api.openai.com/v1"
        )
    }
}
