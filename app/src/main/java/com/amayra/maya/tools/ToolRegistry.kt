package com.amayra.maya.tools

import com.amayra.maya.data.MayaDatabaseHolder
import com.amayra.maya.data.ToolLogEntity
import com.amayra.maya.core.MayaLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** Danger tiers; higher tiers require explicit user confirmation. */
enum class RiskLevel { SAFE, DEFAULT, CONFIRM, DANGEROUS }

/** Tool execution outcome — never fake success. */
sealed class ToolResult {
    data class Success(val summary: String, val dataJson: String? = null) : ToolResult()
    data class Failure(val error: String, val retryable: Boolean = false) : ToolResult()
    data class ConfirmRequired(val reason: String, val detailsJson: String? = null) : ToolResult()
}

/** Tool interface every capability implements. */
interface Tool {
    val name: String
    val description: String
    val risk: RiskLevel get() = RiskLevel.DEFAULT
    /** JSON schema for parameters (OpenAI function-calling style). */
    fun parameters(): JsonObject
    suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult
}

/** Execution context handed to tools. */
class ToolContext(
    val appContext: android.content.Context,
    val database: MayaDatabaseHolder,
    val requestConfirmation: suspend (title: String, details: String) -> Boolean,
    val activeConversationId: String = "default"
)

/** Registry + executor with permission/safety gate and audit logging. */
class ToolRegistry(
    private val context: android.content.Context,
    private val db: MayaDatabaseHolder,
    private val confirmationFlow: suspend (title: String, details: String) -> Boolean
) {
    private val tools = linkedMapOf<String, Tool>()
    private val json = Json { ignoreUnknownKeys = true }

    operator fun get(name: String): Tool? = tools[name]

    fun register(tool: Tool) {
        tools[tool.name] = tool
        MayaLog.i("TOOLS", "Registered tool: ${tool.name} (risk=${tool.risk})")
    }

    fun all(): List<Tool> = tools.values.toList()

    fun specs(): List<com.amayra.maya.ai.ToolSpec> = tools.values.map { t ->
        com.amayra.maya.ai.ToolSpec(
            name = t.name,
            description = "${t.description} [risk=${t.risk}]",
            parameters = t.parameters()
        )
    }

    /** LLM -> structured tool request -> safety layer -> executor -> result -> LLM. */
    suspend fun execute(name: String, argumentsJson: String, conversationId: String = "default"): ToolResult {
        val tool = tools[name]
            ?: return ToolResult.Failure("Unknown tool: $name")
        val args: JsonObject = try {
            if (argumentsJson.isBlank()) JsonObject(emptyMap()) else json.parseToJsonElement(argumentsJson).jsonObject
    } catch (t: Throwable) {
            return ToolResult.Failure("Malformed tool arguments: ${t.message}")
        }

        MayaLog.i("TOOLS", "Execute ${tool.name} args=${MayaLog.args("args" to argumentsJson.take(200))}")

        // Safety gate: CONFIRM/DANGEROUS tools must pass an explicit user confirmation.
        if (tool.risk == RiskLevel.CONFIRM || tool.risk == RiskLevel.DANGEROUS) {
            val details = buildString {
                append(tool.description)
                args.forEach { (k, v) -> append("\n• $k: ${v.toString().take(120)}") }
            }
            val ok = confirmationFlow("Maya wants to run: ${tool.name}", details)
            if (!ok) {
                MayaLog.i("TOOLS", "User declined tool ${tool.name}")
                return ToolResult.Failure("User declined to run ${tool.name}.")
            }
        }

        return try {
            val ctx = ToolContext(context, db, confirmationFlow, conversationId)
            val result = withContext(Dispatchers.IO) { tool.execute(args, ctx) }
            val (ok, summary) = when (result) {
                is ToolResult.Success -> true to result.summary
                is ToolResult.Failure -> false to result.error
                is ToolResult.ConfirmRequired -> false to result.reason
            }
            db.toolLogDao.insert(
                ToolLogEntity(
                    toolName = tool.name,
                    argsJson = argumentsJson.take(2000),
                    resultSummary = summary.take(500),
                    success = ok,
                    confirmed = tool.risk == RiskLevel.CONFIRM || tool.risk == RiskLevel.DANGEROUS
                )
            )
            result
        } catch (t: Throwable) {
            MayaLog.e("TOOLS", "Tool ${tool.name} crashed", t)
            ToolResult.Failure("Tool ${tool.name} failed: ${t.message}")
        }
    }
}
