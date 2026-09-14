package com.amayra.maya.ai

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Verifies the multimodal request mapping used by AiClient.visionOnce:
 *  - Gemini: parts[].inline_data {mime_type, data}
 *  - OpenAI-compat: content[] with image_url {url}
 * Responses are SSE streams with a single text delta (provider-neutral parse).
 */
class VisionRequestTest {

    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { server.shutdown() }

    private fun sse(text: String) = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody("data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"$text\"}]}}]}\n\n")

    private val dataUrl = "data:image/jpeg;base64,QUJD" // "ABC"

    @Test
    fun `gemini request carries inline_data image part`() = runTest {
        server.enqueue(sse("a red button"))
        val client = GeminiClient(
            OkHttpClient(), keyProvider = { "k" }, baseUrl = server.url("/").toString().trimEnd('/')
        )
        val sb = StringBuilder()
        client.chatStream(
            ChatRequest(
                model = "m",
                messages = listOf(
                    ChatMessage(role = "system", content = "describe"),
                    ChatMessage(role = "user", content = "what is this?", imageUri = dataUrl)
                ),
                temperature = 0.3f,
                maxTokens = 100
            )
        ) { ev -> if (ev is ChatStreamEvent.Deltas) sb.append(ev.text) }
        assertEquals("a red button", sb.toString())

        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        val parts = body["contents"]!!.jsonArray[0].jsonObject["parts"]!!.jsonArray
        assertEquals(2, parts.size) // text + image
        val img = parts[1].jsonObject["inline_data"]!!.jsonObject
        assertEquals("image/jpeg", img["mime_type"]!!.jsonPrimitive.content)
        assertEquals("QUJD", img["data"]!!.jsonPrimitive.content)
    }

    @Test
    fun `openai-compat request carries image_url content part`() = runTest {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"blue link\"}}]}\n\ndata: [DONE]\n\n")
        )
        val client = OpenAiCompatClient(
            OkHttpClient(),
            baseUrlProvider = { server.url("/v1").toString().trimEnd('/') },
            keyProvider = { "k" }
        )
        val sb = StringBuilder()
        client.chatStream(
            ChatRequest(
                model = "m",
                messages = listOf(ChatMessage(role = "user", content = "look", imageUri = dataUrl)),
                temperature = 0.3f,
                maxTokens = 100
            )
        ) { ev -> if (ev is ChatStreamEvent.Deltas) sb.append(ev.text) }
        assertEquals("blue link", sb.toString())

        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        val msg = body["messages"]!!.jsonArray[0].jsonObject
        val content = msg["content"]!!.jsonArray
        val imgPart = content.first { it.jsonObject["type"]?.jsonPrimitive?.content == "image_url" }.jsonObject
        assertEquals(dataUrl, imgPart["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content)
        assertTrue(content.any { it.jsonObject["type"]?.jsonPrimitive?.content == "text" })
    }
}
