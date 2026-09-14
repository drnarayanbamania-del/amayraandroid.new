package com.amayra.maya.voice.tts

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.Base64

class GeminiTtsClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: GeminiTtsClient

    private val pcmBytes = ByteArray(32) { (it % 7).toByte() }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = GeminiTtsClient(
            http = OkHttpClient(),
            keyProvider = { "test-key" },
            baseUrl = server.url("/").toString().trimEnd('/')
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)

    @Test
    fun `generateContent primary format parses inline audio`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"candidates":[{"content":{"parts":[{"inline_data":{"mime_type":"audio/pcm;rate=24000","data":"${b64(pcmBytes)}"}}]}}]}"""
            )
        )
        val audio = client.synthesize("hello", "Kore")
        assertNotNull(audio)
        assertTrue(audio!!.pcm.contentEquals(pcmBytes))
        assertEquals(24_000, audio.sampleRate)
        val req = server.takeRequest()
        // v1beta only — the v1 endpoint rejects speechConfig; we never probe it.
        assertTrue(req.path!!.startsWith("/v1beta/models/gemini-3.1-flash-tts-preview:generateContent"))
        val body = req.body.readUtf8()
        assertTrue(body.contains("\"voiceName\":\"Kore\""))
        assertTrue(body.contains("responseModalities"))
    }

    @Test
    fun `single v1beta request - no wasted endpoint probing`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"candidates":[{"content":{"parts":[{"inline_data":{"mime_type":"audio/pcm;rate=24000","data":"${b64(pcmBytes)}"}}]}}]}"""
            )
        )
        val audio = client.synthesize("hello", "Aoede")
        assertNotNull(audio)
        assertTrue(audio!!.pcm.contentEquals(pcmBytes))
        // Exactly one request was made, and it hit v1beta.
        assertTrue(server.takeRequest().path!!.startsWith("/v1beta/models/"))
        assertEquals("no endpoint probing — exactly one request total", 1, server.requestCount)
    }

    @Test
    fun `returns null on total failure - honest error`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
        val audio = client.synthesize("hello", "Kore")
        assertNull(audio)
    }
    @Test
    fun `returns null when no key configured`() = runTest {
        val noKey = GeminiTtsClient(OkHttpClient(), keyProvider = { null }, baseUrl = server.url("/").toString())
        assertNull(noKey.synthesize("hello", "Kore"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `style hint is prepended to the prompt`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"candidates":[{"content":{"parts":[{"inline_data":{"data":"${b64(pcmBytes)}"}}]}}]}"""
            )
        )
        client.synthesize("hello", "Kore", styleHint = "warm and upbeat")
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("Say in a warm and upbeat tone: hello"))
    }

    @Test
    fun `PersonaVoice defaults model`() {
        val pv = GeminiTtsClient.PersonaVoice("Fenrir")
        assertEquals("Fenrir", pv.voice)
        assertEquals(GeminiTtsClient.DEFAULT_MODEL, pv.model)
    }
}
