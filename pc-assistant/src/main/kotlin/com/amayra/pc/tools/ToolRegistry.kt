package com.amayra.pc.tools

import com.amayra.pc.core.PcLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Danger tiers; higher tiers require explicit user confirmation. */
enum class RiskLevel { SAFE, DEFAULT, CONFIRM, DANGEROUS }

/** Tool execution outcome — never fake success. */
sealed class ToolResult {
    data class Success(val summary: String, val dataJson: String? = null) : ToolResult()
    data class Failure(val error: String, val retryable: Boolean = false) : ToolResult()
    data class ConfirmRequired(val reason: String) : ToolResult()

    val ok: Boolean get() = this is Success
    fun toStructuredJson(tool: String): String {
        val obj = buildJsonObject {
            put("success", ok)
            put("action", tool)
            when (this@ToolResult) {
                is Success -> { put("summary", summary); dataJson?.let { put("data", it) } }
                is Failure -> { put("error", error); put("retryable", retryable) }
                is ConfirmRequired -> put("error", reason)
            }
        }
        return obj.toString()
    }
}

interface Tool {
    val name: String
    val description: String
    val risk: RiskLevel get() = RiskLevel.DEFAULT
    fun parameters(): JsonObject
    suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult
}

class ToolContext(
    val requestConfirmation: suspend (title: String, details: String) -> Boolean
)

/** Registry + executor with confirmation gate and JSONL audit log. */
class ToolRegistry(private val auditDir: Path = Path.of("data", "audit")) {
    /** Recent tool calls for the status UI (newest first). */
    data class ToolCallEntry(val timestamp: String, val call: String, val ok: Boolean)

    private val history = ArrayDeque<ToolCallEntry>(6)
    private val historyLock = Any()

    /** Snapshot of the last [limit] tool calls, newest first. */
    fun recentCalls(limit: Int = 5): List<ToolCallEntry> = synchronized(historyLock) {
        history.take(limit)
    }

    private val tools = linkedMapOf<String, Tool>()
    private val json = Json { ignoreUnknownKeys = true }
    private val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm:ss")

    operator fun get(name: String): Tool? = tools[name]
    fun register(tool: Tool) {
        tools[tool.name] = tool
        PcLog.i("TOOLS", "Registered tool: ${tool.name} (risk=${tool.risk})")
    }
    fun all(): List<Tool> = tools.values.toList()
    fun specs(): List<com.amayra.pc.ai.ToolSpec> = tools.values.map { t ->
        com.amayra.pc.ai.ToolSpec(
            name = t.name,
            description = "${t.description} [risk=${t.risk}]",
            parameters = t.parameters()
        )
    }

    suspend fun execute(name: String, argumentsJson: String, ctx: ToolContext): ToolResult {
        val tool = tools[name] ?: return ToolResult.Failure("Unknown tool: $name")
        val args: JsonObject = try {
            if (argumentsJson.isBlank()) JsonObject(emptyMap()) else json.parseToJsonElement(argumentsJson).jsonObject
        } catch (t: Throwable) {
            return ToolResult.Failure("Malformed tool arguments: ${t.message}")
        }

        if (tool.risk == RiskLevel.CONFIRM || tool.risk == RiskLevel.DANGEROUS) {
            val details = buildString {
                append(tool.description)
                args.forEach { (k, v) -> append("\n• $k: ${v.toString().take(120)}") }
            }
            val ok = ctx.requestConfirmation("Amayra wants to run: ${tool.name}", details)
            if (!ok) {
                PcLog.i("TOOLS", "User declined tool $name")
                return ToolResult.Failure("User declined to run ${tool.name}.")
            }
        }

        val result = try {
            withContext(Dispatchers.IO) { tool.execute(args, ctx) }
        } catch (t: Throwable) {
            PcLog.e("TOOLS", "Tool $name crashed", t)
            ToolResult.Failure("Tool $name failed: ${t.message}", retryable = true)
        }
        val callText = buildString {
            append(tool.name)
            if (argumentsJson.length > 2) append(" ${argumentsJson.take(60)}")
        }
        synchronized(historyLock) {
            history.addFirst(ToolCallEntry(LocalDateTime.now().format(timeFmt), callText, result.ok))
            if (history.size > 6) history.removeLast()
        }
        audit(tool.name, argumentsJson, result)
        return result
    }

    private fun audit(toolName: String, argsJson: String, result: ToolResult) {
        try {
            Files.createDirectories(auditDir)
            val line = buildJsonObject {
                put("ts", LocalDateTime.now().format(fmt))
                put("tool", toolName)
                put("args", argsJson.take(2000))
                put("success", result.ok)
                put("summary", when (result) {
                    is ToolResult.Success -> result.summary
                    is ToolResult.Failure -> result.error
                    is ToolResult.ConfirmRequired -> result.reason
                }.take(500))
            }.toString()
            Files.writeString(
                auditDir.resolve("tool-log.jsonl"), line + System.lineSeparator(),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND
            )
        } catch (t: Throwable) {
            PcLog.w("AUDIT", "Failed to write audit entry: ${t.message}")
        }
    }
}
