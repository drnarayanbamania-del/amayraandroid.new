package com.amayra.maya.integration

import android.content.Context
import com.amayra.maya.core.MayaLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * PC relay: a tiny JSON-lines TCP bridge (default port 18789, mirroring the
 * OpenClaw gateway contract) so the user's PC agent can push commands to Maya
 * ("call_my_pc" pattern: Maya relays a command to Termux on this phone).
 *
 * Envelope: {"action":"com.getmaya.android.SEND_COMMAND","cmd":"..."} or
 *           {"action":"com.amayra.maya.PING"}
 * Replies:  {"ok":true,"summary":"..."} / {"ok":false,"error":"..."}
 */
class PcRelay(private val context: Context) {

    @Volatile private var server: ServerSocket? = null
    @Volatile private var thread: Thread? = null
    @Volatile var running: Boolean = false
        private set

    fun start(port: Int = DEFAULT_PORT) {
        if (running) return
        thread = Thread({
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(port))
                server = ss
                running = true
                MayaLog.i("RELAY", "PC relay listening on :$port")
                while (running) {
                    val client = ss.accept()
                    handle(client)
                }
            } catch (t: Throwable) {
                if (running) MayaLog.e("RELAY", "Relay crashed", t)
            } finally {
                running = false
            }
        }, "maya-relay").apply { isDaemon = true; start() }
    }

    private fun handle(socket: Socket) {
        Thread({
            try {
                socket.soTimeout = 10_000
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val writer = PrintWriter(socket.getOutputStream(), true)
                val line = reader.readLine() ?: return@Thread
                val response = kotlinx.coroutines.runBlocking { process(line) }
                writer.println(response)
            } catch (t: Throwable) {
                MayaLog.w("RELAY", "client error: ${t.message}")
            } finally {
                try { socket.close() } catch (_: Throwable) {}
            }
        }, "maya-relay-client").start()
    }

    private suspend fun process(line: String): String = withContext(Dispatchers.Default) {
        val json = Json { ignoreUnknownKeys = true; isLenient = true }
        try {
            val obj = json.parseToJsonElement(line).let { it as kotlinx.serialization.json.JsonObject }
            val action = (obj["action"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
            when {
                action.endsWith("PING") -> json.encodeToString(
                    kotlinx.serialization.json.JsonObject.serializer(),
                    kotlinx.serialization.json.buildJsonObject {
                        put("ok", kotlinx.serialization.json.JsonPrimitive(true))
                        put("summary", kotlinx.serialization.json.JsonPrimitive("Maya here. Phone online."))
                    }
                )
                action.endsWith("SEND_COMMAND") -> {
                    val cmd = (obj["cmd"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
                    if (cmd.isBlank()) error("empty cmd")
                    // Route via Termux when available; else honest failure.
                    val out = TermuxManager.execute(context, listOf("bash", "-lc", cmd), timeoutMs = 30_000)
                    val ok = out.exitCode == 0 && !out.timedOut
                    json.encodeToString(
                        kotlinx.serialization.json.JsonObject.serializer(),
                        kotlinx.serialization.json.buildJsonObject {
                            put("ok", kotlinx.serialization.json.JsonPrimitive(ok))
                            put("summary", kotlinx.serialization.json.JsonPrimitive(
                                (out.stdout + out.stderr).take(2000)))
                        }
                    )
                }
                else -> json.encodeToString(
                    kotlinx.serialization.json.JsonObject.serializer(),
                    kotlinx.serialization.json.buildJsonObject {
                        put("ok", kotlinx.serialization.json.JsonPrimitive(false))
                        put("error", kotlinx.serialization.json.JsonPrimitive("Unknown action: $action"))
                    }
                )
            }
        } catch (t: Throwable) {
            "{\"ok\":false,\"error\":\"${t.message?.replace("\"", "'")}\"}"
        }
    }

    fun stop() {
        running = false
        try { server?.close() } catch (_: Throwable) {}
        server = null
    }

    companion object {
        const val DEFAULT_PORT = 18789
    }
}
