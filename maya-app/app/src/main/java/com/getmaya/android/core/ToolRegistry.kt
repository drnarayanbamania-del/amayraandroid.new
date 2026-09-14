package com.getmaya.android.core

/**
 * find_tool / run_tool architecture. The original app exposed dozens of tools
 * (whatsapp_send_message, github_clone_repo, termux_run, make_call, ...).
 * Each tool is registered here with a description and a permission gate; the
 * assistant asks the model which tool fits and executes it after confirmation.
 */
data class MayaTool(
    val name: String,
    val description: String,
    val requiresConfirmation: Boolean = true,
    val handler: (Map<String, String>) -> String
)

object ToolRegistry {
    private val tools = linkedMapOf<String, MayaTool>()

    fun register(tool: MayaTool) {
        tools[tool.name] = tool
    }

    fun findTool(query: String): List<MayaTool> =
        tools.values.filter { it.name.contains(query, true) || it.description.contains(query, true) }

    fun runTool(name: String, args: Map<String, String>, confirmed: Boolean = false): Result<String> {
        val tool = tools[name] ?: return Result.failure(
            NoSuchElementException("No tool named '$name'. Use find_tool to search.")
        )
        if (tool.requiresConfirmation && !confirmed) {
            return Result.success("CONFIRM_REQUIRED: run $name with $args? Ask the user first.")
        }
        return runCatching { tool.handler(args) }
    }

    fun catalog(): String = tools.values.joinToString("\n") { "${it.name}: ${it.description}" }
}
