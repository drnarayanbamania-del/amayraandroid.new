package com.amayra.maya.ai

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Exercises the real GeminiClient HTTP surface with a scripted server:
 * 1) tool declarations are included in the request body,
 * 2) functionCall parts are parsed into ToolCallDetected,
 * 3) the follow-up request replays functionCall + functionResponse correctly,
 * 4) final text is streamed, and error mapping works.
 */
class GeminiClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: GeminiClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = GeminiClient(
            http = OkHttpClient(),
            keyProvider = { "test-key" },
            baseUrl = server.url("/").toString().trimEnd('/')
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    /** Builds a realistic Gemini SSE chunk: finishReason lives at candidate level. */
    private fun sseChunk(vararg parts: String, finish: String? = null): String {
        val partsJson = parts.joinToString(",") { """{"text":$it}""" }
        val finishJson = finish?.let { ",\"finishReason\":\"$it\"" } ?: ""
        return "data: {\"candidates\":[{\"content\":{\"parts\":[$partsJson]}$finishJson}]}\n\n"
    }

    @Test
    fun `tool round trip - declarations sent, functionCall parsed, functionResponse replayed`() = runTest {
        // Round 1: model answers with a functionCall (no text).
        server.enqueue(
            MockResponse().setBody(
                "data: {\"candidates\":[{\"content\":{\"parts\":[{\"functionCall\":{\"name\":\"get_time\",\"args\":{\"tz\":\"IST\"}}}]},\"finishReason\":null}]}\n\n" +
                "data: {\"candidates\":[{\"content\":{\"parts\":[{}]},\"finishReason\":\"STOP\"}]}\n\n"
            )
        )
        // Round 2: model answers with text after receiving the functionResponse.
        server.enqueue(MockResponse().setBody(sseChunk("\"In India it is 10am\"", finish = "STOP")))

        val tools = listOf(
            ToolSpec(
                name = "get_time",
                description = "Get current time",
                parameters = JsonObject(emptyMap())
            )
        )
        val collected = mutableListOf<ChatStreamEvent>()

        // ── Round 1 ──
        client.chatStream(
            ChatRequest(
                model = "gemini-2.0-flash",
                messages = listOf(ChatMessage(role = "user", content = "what time is it?")),
                tools = tools,
                temperature = 0.5f,
                maxTokens = 256
            )
        ) { collected.add(it) }

        val r1 = server.takeRequest()
        val body1 = r1.body.readUtf8()
        val json1 = Json.parseToJsonElement(body1).jsonObject

        // 1) Tool declarations included in the request body.
        val decl = json1["tools"]!!
            .jsonArray[0].jsonObject["functionDeclarations"]!!
            .jsonArray[0].jsonObject
        assertEquals("get_time", decl["name"]!!.jsonPrimitive.content)
        assertTrue(body1.contains("\"functionDeclarations\""))

        // 2) functionCall parsed into a ToolCallDetected event.
        val call = collected.filterIsInstance<ChatStreamEvent.ToolCallDetected>().single()
        assertEquals("get_time", call.call.name)
        assertEquals("{\"tz\":\"IST\"}", call.call.argumentsJson)

        // ── Round 2: replay the tool result ──
        val collected2 = mutableListOf<ChatStreamEvent>()
        client.chatStream(
            ChatRequest(
                model = "gemini-2.0-flash",
                messages = listOf(
                    ChatMessage(role = "user", content = "what time is it?"),
                    ChatMessage(role = "assistant", content = "", toolName = "get_time", toolArgsJson = call.call.argumentsJson),
                    ChatMessage(role = "tool", content = "10:00 AM IST", toolCallId = call.call.id, toolName = "get_time")
                ),
                tools = tools,
                temperature = 0.5f,
                maxTokens = 256
            )
        ) { collected2.add(it) }

        val body2 = server.takeRequest().body.readUtf8()
        val contents = Json.parseToJsonElement(body2).jsonObject["contents"]!!.jsonArray
        // [user, model(functionCall), user(functionResponse)]
        assertEquals(3, contents.size)

        val modelTurn = contents[1].jsonObject
        assertEquals("model", modelTurn["role"]!!.jsonPrimitive.content)
        val fcPart = modelTurn["parts"]!!.jsonArray[0].jsonObject["functionCall"]!!.jsonObject
        assertEquals("get_time", fcPart["name"]!!.jsonPrimitive.content)
        assertEquals("{\"tz\":\"IST\"}", fcPart["args"]!!.jsonObject.toString())

        val toolTurn = contents[2].jsonObject
        assertEquals("user", toolTurn["role"]!!.jsonPrimitive.content)
        val frPart = toolTurn["parts"]!!.jsonArray[0].jsonObject["functionResponse"]!!.jsonObject
        assertEquals("get_time", frPart["name"]!!.jsonPrimitive.content)
        assertEquals("10:00 AM IST", frPart["response"]!!.jsonObject["result"]!!.jsonPrimitive.content)

        // 4) Final text streamed.
        val text2 = collected2.filterIsInstance<ChatStreamEvent.Deltas>().joinToString("") { it.text }
        assertEquals("In India it is 10am", text2)
    }

    @Test
    fun `text only request contains no tools key`() = runTest {
        server.enqueue(MockResponse().setBody(sseChunk("\"hi\"", finish = "STOP")))
        val out = mutableListOf<ChatStreamEvent>()
        client.chatStream(
            ChatRequest(
                model = "gemini-2.0-flash",
                messages = listOf(ChatMessage(role = "user", content = "hello")),
                temperature = 0.5f,
                maxTokens = 64
            )
        ) { out.add(it) }
        val body = server.takeRequest().body.readUtf8()
        assertTrue(!body.contains("\"tools\""))
        val text = out.filterIsInstance<ChatStreamEvent.Deltas>().joinToString("") { it.text }
        assertEquals("hi", text)
    }

    @Test
    fun `system message goes to systemInstruction not contents`() = runTest {
        server.enqueue(MockResponse().setBody(sseChunk("\"ok\"", finish = "STOP")))
        client.chatStream(
            ChatRequest(
                model = "gemini-2.0-flash",
                messages = listOf(
                    ChatMessage(role = "system", content = "You are Maya"),
                    ChatMessage(role = "user", content = "hi")
                ),
                temperature = 0.5f,
                maxTokens = 64
            )
        ) { }
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"systemInstruction\""))
        assertTrue(!body.contains("You are Maya\\\", \\\"role\\\""))
        val contents = Json.parseToJsonElement(body).jsonObject["contents"]!!.jsonArray
        assertEquals(1, contents.size) // only the user turn
    }

    @Test
    fun `http 429 maps to RateLimit and 401 maps to Authentication`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429))
        val e1 = mutableListOf<ChatStreamEvent>()
        client.chatStream(req(user = "x")) { e1.add(it) }
        assertTrue(e1.filterIsInstance<ChatStreamEvent.Failed>().single().error is AiError.RateLimit)

        server.enqueue(MockResponse().setResponseCode(401))
        val e2 = mutableListOf<ChatStreamEvent>()
        client.chatStream(req(user = "x")) { e2.add(it) }
        assertTrue(e2.filterIsInstance<ChatStreamEvent.Failed>().single().error is AiError.Authentication)
    }

    @Test
    fun `missing api key fails fast with Authentication without hitting the server`() = runTest {
        val noKey = GeminiClient(OkHttpClient(), keyProvider = { null }, baseUrl = server.url("/").toString().trimEnd('/'))
        val events = mutableListOf<ChatStreamEvent>()
        noKey.chatStream(req(user = "hi")) { events.add(it) }
        assertTrue(events.filterIsInstance<ChatStreamEvent.Failed>().single().error is AiError.Authentication)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `malformed sse payload is skipped without crashing`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                "data: {not-json}\n\n" +
                "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"resilient\"}]},\"finishReason\":\"STOP\"}]}\n\n"
            )
        )
        val out = mutableListOf<ChatStreamEvent>()
        client.chatStream(req(user = "hi")) { out.add(it) }
        assertEquals("resilient", out.filterIsInstance<ChatStreamEvent.Deltas>().joinToString("") { it.text })
    }

    @Test
    fun `image attachment becomes inline_data part`() = runTest {
        server.enqueue(MockResponse().setBody(sseChunk("\"seen\"", finish = "STOP")))
        client.chatStream(
            req(user = "what is this").copy(
                messages = listOf(
                    ChatMessage(role = "user", content = "what is this", imageUri = "data:image/jpeg;base64,QUJD")
                )
            )
        ) { }
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"inline_data\""))
        assertTrue(body.contains("image/jpeg"))
        assertTrue(body.contains("QUJD"))
    }

    private fun req(user: String) = ChatRequest(
        model = "gemini-2.0-flash",
        messages = listOf(ChatMessage(role = "user", content = user)),
        temperature = 0.5f,
        maxTokens = 64
    )
}
