package com.amayra.pc.relay

import com.amayra.pc.brain.AmayraBrain
import com.amayra.pc.core.PcLog
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * PC relay: JSON-lines TCP server on :18789 — the same contract the Android
 * app's PcRelay speaks, so Maya on the tablet can send commands to this PC
 * ("SEND_COMMAND") or just PING it. Each line is one JSON envelope; each gets
 * one JSON reply.
 */
class PcRelayServer(private val brain: AmayraBrain, private val port: Int) {

    @Volatile private var server: ServerSocket? = null
    @Volatile var running: Boolean = false
        private set
    /** Total connections accepted since start, for the status UI. */
    @Volatile var connectionsAccepted: Int = 0
        private set
    @Volatile var lastClientSummary: String = "(no client yet)"
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun start() {
        if (running) return
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(port))
        server = ss
        running = true
        PcLog.i("RELAY", "PC relay listening on :$port")
        scope.launch {
            while (running && isActive) {
                val client = try { ss.accept() } catch (t: Throwable) { break }
                connectionsAccepted++
                lastClientSummary = "${client.inetAddress.hostAddress}:" + client.port
                launch { handle(client) }
            }
        }
    }

    private suspend fun handle(socket: Socket) = withContext(Dispatchers.IO) {
        try {
            socket.soTimeout = 60_000
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val writer = PrintWriter(socket.getOutputStream(), true)
            while (running) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                val response = process(line)
                writer.println(response)
            }
        } catch (t: Throwable) {
            PcLog.w("RELAY", "client error: ${t.message}")
        } finally {
            runCatching { socket.close() }
        }
    }

    private suspend fun process(line: String): String {
        return try {
            val obj = json.parseToJsonElement(line).jsonObject
            val action = (obj["action"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            when {
                action.endsWith("PING") -> reply(ok = true, summary = "Amayra here. PC online.")
                action.endsWith("PC_SCREENSHOT") -> {
                    val maxEdge = ((obj["maxEdge"] as? JsonPrimitive)?.intOrNull ?: 1280).coerceIn(320, 2560)
                    val shot = captureScreenJpeg(maxEdge)
                    if (shot == null) reply(ok = false, error = "Screenshot capture failed on the PC.")
                    else reply(ok = true, summary = "Screenshot captured (${shot.second.first}x${shot.second.second}, ${shot.first.size / 1024} KB).",
                        imageBase64 = java.util.Base64.getEncoder().encodeToString(shot.first))
                }
                action.endsWith("SEND_COMMAND") -> {
                    val cmd = (obj["cmd"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                    if (cmd.isBlank()) return reply(ok = false, error = "empty cmd")
                    val response = brain.handleUserInput(cmd)
                    reply(ok = true, summary = response.take(2000))
                }
                else -> reply(ok = false, error = "Unknown action: $action")
            }
        } catch (t: Throwable) {
            reply(ok = false, error = t.message ?: "relay error")
        }
    }

    private fun reply(ok: Boolean, summary: String? = null, error: String? = null, imageBase64: String? = null): String =
        buildJsonObject {
            put("ok", ok)
            summary?.let { put("summary", it) }
            error?.let { put("error", it) }
            imageBase64?.let { put("imageBase64", it) }
        }.toString()

    /** Capture the full screen and JPEG-encode it downscaled to maxEdge px. */
    private fun captureScreenJpeg(maxEdge: Int): Pair<ByteArray, Pair<Int, Int>>? = try {
        val screen = java.awt.Toolkit.getDefaultToolkit().screenSize
        val full = java.awt.Robot().createScreenCapture(java.awt.Rectangle(screen))
        val scale = minOf(1.0, maxEdge.toDouble() / maxOf(full.width, full.height))
        val img = if (scale < 1.0) {
            val w = (full.width * scale).toInt().coerceAtLeast(1)
            val h = (full.height * scale).toInt().coerceAtLeast(1)
            val resized = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB)
            val g = resized.createGraphics()
            g.drawImage(full, 0, 0, w, h, null)
            g.dispose()
            resized
        } else full
        val baos = java.io.ByteArrayOutputStream()
        javax.imageio.ImageIO.write(img, "jpg", baos)
        baos.toByteArray() to (img.width to img.height)
    } catch (t: Throwable) {
        PcLog.e("RELAY", "captureScreenJpeg failed", t)
        null
    }

    fun stop() {
        running = false
        runCatching { server?.close() }
    }
}
