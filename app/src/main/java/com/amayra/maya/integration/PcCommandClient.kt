package com.amayra.maya.integration

import com.amayra.maya.core.MayaLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Client side of the PC bridge: sends JSON-lines envelopes to the Amayra PC
 * assistant's relay server (:18789) and reads one JSON reply per line.
 *
 * The PC assistant runs a full agent brain — natural-language commands are
 * planned and executed there with its own tools (apps, mouse, keyboard,
 * windows, files, screenshots). This client carries:
 *   {"action":"com.amayra.maya.PING"}                                  → health
 *   {"action":"com.getmaya.android.SEND_COMMAND","cmd":"open Chrome"}  → task
 *
 * Replies: {"ok":true,"summary":"..."} / {"ok":false,"error":"..."}
 */
object PcCommandClient {

    data class PcReply(val ok: Boolean, val summary: String, val error: String? = null, val imageBase64: String? = null)

    private const val CONNECT_TIMEOUT_MS = 4_000
    private const val READ_TIMEOUT_MS = 120_000  // PC brain may run multi-step tasks

    suspend fun send(host: String, port: Int, action: String, cmd: String? = null, maxEdgeOverride: Int? = null): PcReply =
        withContext(Dispatchers.IO) {
            val envelope = buildJsonObject {
                put("action", action)
                cmd?.let { put("cmd", it) }
                maxEdgeOverride?.let { put("maxEdge", it) }
            }.toString()
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                    socket.soTimeout = READ_TIMEOUT_MS
                    val writer = PrintWriter(socket.getOutputStream(), true)
                    val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                    writer.println(envelope)
                    val line = reader.readLine()
                        ?: return@use PcReply(false, "", "PC closed the connection without a reply.")
                    parseReply(line)
                }
            } catch (e: java.net.SocketTimeoutException) {
                PcReply(false, "", "PC did not answer in time (is it busy, or the firewall blocking $host:$port?).")
            } catch (e: java.net.ConnectException) {
                PcReply(false, "", "Cannot connect to $host:$port — is the PC assistant running with the relay enabled?")
            } catch (e: java.net.UnknownHostException) {
                PcReply(false, "", "Unknown host '$host' — check the PC address in Settings.")
            } catch (e: Throwable) {
                PcReply(false, "", "PC bridge error: ${e.message}")
            }
        }

    suspend fun ping(host: String, port: Int): PcReply = send(host, port, "com.amayra.maya.PING")

    suspend fun command(host: String, port: Int, cmd: String): PcReply =
        send(host, port, "com.getmaya.android.SEND_COMMAND", cmd)

    /** Ask the PC to capture its screen; reply carries a downscaled JPEG (base64). */
    suspend fun screenshot(host: String, port: Int, maxEdge: Int = 1280): PcReply =
        send(host, port, "com.amayra.maya.PC_SCREENSHOT", maxEdgeOverride = maxEdge)

    private fun parseReply(line: String): PcReply = try {
        val obj = Json { ignoreUnknownKeys = true; isLenient = true }
            .parseToJsonElement(line).let { it as kotlinx.serialization.json.JsonObject }
        PcReply(
            ok = (obj["ok"] as? kotlinx.serialization.json.JsonPrimitive)?.booleanOrNull ?: false,
            summary = (obj["summary"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
                ?: (obj["error"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull ?: "",
            error = (obj["error"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull,
            imageBase64 = (obj["imageBase64"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
        )
    } catch (t: Throwable) {
        PcReply(false, line.take(500), "Unparseable PC reply")
    }
}
