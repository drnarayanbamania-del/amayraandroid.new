package com.amayra.maya.integration

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.amayra.maya.tools.RiskLevel
import com.amayra.maya.tools.Tool
import com.amayra.maya.tools.ToolContext
import com.amayra.maya.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * OpenClaw integration. The agent runs locally (usually under Termux) and exposes a
 * local gateway dashboard. Maya never assumes it is running: it probes first, and
 * only reports what it actually observes.
 */
object OpenClawManager {
    const val DEFAULT_GATEWAY = "http://localhost:18789"

    data class GatewayStatus(val reachable: Boolean, val httpCode: Int?, val detail: String)

    /** Probe the local gateway. Suspending — network runs on IO, never the caller's thread. */
    suspend fun probe(baseUrl: String = DEFAULT_GATEWAY): GatewayStatus {
        val client = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .build()
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val resp = client.newCall(Request.Builder().url(baseUrl).build()).execute()
                resp.use { GatewayStatus(true, it.code, "Gateway answered HTTP ${it.code}") }
            } catch (t: Throwable) {
                GatewayStatus(false, null, "Gateway not reachable at $baseUrl (${t.message})")
            }
        }
    }
}

/** openclaw_status tool. */
class OpenClawStatusTool : Tool {
    override val name = "openclaw_status"
    override val description = "Check whether the local OpenClaw gateway is running and reachable."
    override val risk = RiskLevel.SAFE
    override fun parameters() = JsonObject(emptyMap())
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val status = OpenClawManager.probe()
        return if (status.reachable) {
            ToolResult.Success("OpenClaw gateway is UP (${status.detail}).")
        } else {
            ToolResult.Failure(
                "${status.detail}. Start it with 'openclaw gateway' (in Termux), or run 'openclaw onboard --install-daemon' to set it up."
            )
        }
    }
}

/** openclaw_start tool — opens onboarding instructions / dashboard. */
class OpenClawStartTool : Tool {
    override val name = "openclaw_start"
    override val description = "Open the OpenClaw gateway dashboard in the browser, or guide setup if not running."
    override val risk = RiskLevel.DEFAULT
    override fun parameters() = JsonObject(emptyMap())
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val status = OpenClawManager.probe()
        return try {
            val url = if (status.reachable) OpenClawManager.DEFAULT_GATEWAY else "https://github.com/openclaw"
            ctx.appContext.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            if (status.reachable) ToolResult.Success("Opened the OpenClaw dashboard at ${OpenClawManager.DEFAULT_GATEWAY}.")
            else ToolResult.Failure(
                "Gateway is not running; opened setup docs instead. Install: openclaw onboard --install-daemon, then: openclaw gateway"
            )
        } catch (t: Throwable) {
            ToolResult.Failure("Could not open browser: ${t.message}")
        }
    }
}
