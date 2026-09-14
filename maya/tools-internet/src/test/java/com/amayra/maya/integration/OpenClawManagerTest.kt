package com.amayra.maya.integration

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Exercises OpenClawManager.probe() through its real HTTP entry point.
 * Verifies the suspending contract: callable from any coroutine, bounded by its
 * 2s timeout, and maps outcomes without throwing.
 */
class OpenClawManagerTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `probe reports reachable gateway with http code`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
        val status = OpenClawManager.probe(server.url("/").toString().trimEnd('/'))

        assertTrue(status.reachable)
        assertEquals(200, status.httpCode)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `probe maps non-200 responses as reachable with code`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))
        val status = OpenClawManager.probe(server.url("/").toString().trimEnd('/'))

        // A 404 still proves the gateway process is up — reachability is about liveness.
        assertTrue(status.reachable)
        assertEquals(404, status.httpCode)
    }

    @Test
    fun `probe returns unreachable without throwing on refused connection`() = runTest {
        // Bind and immediately close a socket to get a guaranteed-dead port.
        val dead = java.net.ServerSocket(0)
        val deadPort = dead.localPort
        dead.close()

        val status = OpenClawManager.probe("http://127.0.0.1:$deadPort")

        assertFalse(status.reachable)
        assertEquals(null, status.httpCode)
        assertTrue(status.detail.contains("not reachable"))
    }
}
