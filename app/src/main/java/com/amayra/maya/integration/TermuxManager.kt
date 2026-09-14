package com.amayra.maya.integration

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.amayra.maya.core.MayaLog
import com.amayra.maya.tools.RiskLevel
import com.amayra.maya.tools.Tool
import com.amayra.maya.tools.ToolContext
import com.amayra.maya.tools.ToolResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Termux integration via the supported RUN_COMMAND / RUN_COMMAND external-app API.
 * Requires: Termux installed + "Allow external apps" enabled in Termux settings +
 * com.termux.permission.RUN_COMMAND granted to Maya.
 */
object TermuxManager {
    const val TERMUX_PACKAGE = "com.termux"
    const val RUN_COMMAND_ACTION = "com.termux.RUN_COMMAND"
    const val RUN_COMMAND_PATH = "com.termux.path"
    const val RUN_COMMAND_ARGUMENTS = "com.termux.arguments"
    const val RUN_COMMAND_WORKDIR = "com.termux.working_dir"
    const val RUN_COMMAND_BACKGROUND = "com.termux.background"
    const val SERVICE_ACTION = "com.termux.service"
    /** Broadcast extras Termux uses to report command results (result files). */
    const val EXTRA_RESULT_DIR = "com.termux.RUN_COMMAND_RESULT_DIRECTORY"
    const val EXTRA_RESULT_FILE = "com.termux.RUN_COMMAND_RESULT_FILE_PATH"
    const val EXTRA_RESULT_ERROR = "com.termux.RUN_COMMAND_RESULT_ERROR"

    data class TermuxOutput(val exitCode: Int?, val stdout: String, val stderr: String, val timedOut: Boolean)

    fun isInstalled(ctx: Context): Boolean = try {
        ctx.packageManager.getPackageInfo(TERMUX_PACKAGE, 0); true
    } catch (t: Throwable) { false }

    fun hasRunCommandPermission(ctx: Context): Boolean =
        ctx.checkCallingOrSelfPermission("com.termux.permission.RUN_COMMAND") ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    fun externalAppsEnabledHint(): String =
        "In Termux: open Termux:API settings → 'Allow external apps' (or run: termux-setup-storage), then grant Maya the RUN_COMMAND permission."

    /** Executes a command in Termux and waits for TermuxResult broadcast back. */
    suspend fun execute(
        ctx: Context,
        command: List<String>,
        workDir: String? = null,
        background: Boolean = true,
        timeoutMs: Long = 30_000,
        executionId: String = "maya-" + System.currentTimeMillis()
    ): TermuxOutput {
        if (!isInstalled(ctx)) return TermuxOutput(null, "", "Termux is not installed.", false)
        if (!hasRunCommandPermission(ctx)) {
            return TermuxOutput(null, "", "Missing com.termux.permission.RUN_COMMAND. Grant it in app settings.", false)
        }
        // Termux writes the command result as files into resultDir when done.
        val resultDir = ctx.cacheDir.resolve("termux_results").apply { mkdirs() }
        val base = "exec_" + System.currentTimeMillis()
        val intent = Intent(RUN_COMMAND_ACTION).apply {
            setClassName(TERMUX_PACKAGE, "$TERMUX_PACKAGE.app.RunCommandService")
            putExtra(RUN_COMMAND_PATH, "/data/data/com.termux/files/usr/bin/bash")
            putExtra(RUN_COMMAND_ARGUMENTS, command.toTypedArray())
            workDir?.let { putExtra(RUN_COMMAND_WORKDIR, it) }
            putExtra(RUN_COMMAND_BACKGROUND, background)
            putExtra(EXTRA_RESULT_DIR, resultDir.absolutePath)
            putExtra(EXTRA_RESULT_FILE, base)
            putExtra(EXTRA_RESULT_ERROR, base + "_err")
        }
        return try {
            ctx.startService(intent)
            MayaLog.i("TERMUX", "Sent execId=$executionId cmd=${command.joinToString(" ").take(120)}")
            waitForResult(resultDir, base, timeoutMs)
        } catch (t: Throwable) {
            TermuxOutput(null, "", "Termux execution failed: ${t.message}", false)
        }
    }

    /** Polls for the result files Termux writes when the command completes. */
    private suspend fun waitForResult(dir: java.io.File, base: String, timeoutMs: Long): TermuxOutput {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val matches = dir.listFiles { f -> f.name.startsWith(base) } ?: emptyArray()
            val resultFile = matches.firstOrNull { !it.name.endsWith("_err") }
            val errFile = matches.firstOrNull { it.name.endsWith("_err") }
            if (resultFile != null || errFile != null) {
                return parseResult((resultFile ?: errFile)?.readText().orEmpty())
            }
            kotlinx.coroutines.delay(400)
        }
        return TermuxOutput(null, "", "Timed out after ${timeoutMs / 1000}s with no Termux result (command may still be running).", true)
    }

    private fun parseResult(jsonText: String): TermuxOutput {
        if (jsonText.isBlank()) return TermuxOutput(null, "", "", false)
        return try {
            val el = Json.parseToJsonElement(jsonText).jsonObject
            val stdout = el["stdout"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val stderr = el["stderr"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val exit = el["exitCode"]?.jsonPrimitive?.intOrNull
            TermuxOutput(exit, stdout, stderr, false)
        } catch (t: Throwable) {
            TermuxOutput(null, "", jsonText.take(500), false)
        }
    }
}

/** termux_execute tool. Dangerous by default; guarded by confirmation. */
class TermuxExecuteTool : Tool {
    override val name = "termux_execute"
    override val description =
        "Run a shell command inside Termux (if installed). Use for files, scripts, packages. " +
            "Destructive commands require confirmation. Args: command, workdir (optional), timeout_seconds (optional)."
    override val risk = RiskLevel.DANGEROUS
    override fun parameters() = JsonObject(mapOf(
        "type" to JsonPrimitive("object"),
        "properties" to JsonObject(mapOf(
            "command" to JsonObject(mapOf("type" to JsonPrimitive("string"), "description" to JsonPrimitive("The shell command line to run inside Termux bash"))),
            "workdir" to JsonObject(mapOf("type" to JsonPrimitive("string"), "description" to JsonPrimitive("Optional working directory inside Termux home"))),
            "timeout_seconds" to JsonObject(mapOf("type" to JsonPrimitive("integer"), "description" to JsonPrimitive("Optional timeout in seconds (default 30)")))
        )),
        "required" to JsonArray(listOf(JsonPrimitive("command")))
    ))

    private val destructiveHints = listOf(
        "rm -rf", "rm -r ", "mkfs", "dd if=", "> /dev/", "chmod -R 777", "apt remove", "apt purge",
        "pkg uninstall", "uninstall", "shutdown", "reboot", "curl | sh", "wget | sh", "| bash", "| sh"
    )

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val command = args["command"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult.Failure("Missing command.")
        val workDir = args["workdir"]?.jsonPrimitive?.contentOrNull
        val timeout = (args["timeout_seconds"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 30)
            .coerceIn(5, 300)

        // Defense-in-depth: refuse obviously destructive commands even after confirmation.
        val lower = command.lowercase()
        if (destructiveHints.any { lower.contains(it) }) {
            val ok = ctx.requestConfirmation(
                "Destructive Termux command",
                "Maya will run: $command\nThis can delete data or modify your system. Proceed?"
            )
            if (!ok) return ToolResult.Failure("User declined the destructive command.")
        }

        val result = TermuxManager.execute(
            ctx.appContext,
            listOf("-lc", command),
            workDir = workDir,
            background = false,
            timeoutMs = timeout * 1000L
        )
        return when {
            result.timedOut -> ToolResult.Failure("Termux command timed out after ${timeout}s (no result received).")
            result.exitCode == null && result.stderr.startsWith("Termux is not installed") ->
                ToolResult.Failure(result.stderr)
            result.exitCode == null && result.stderr.contains("RUN_COMMAND") ->
                ToolResult.Failure(result.stderr)
            else -> {
                val out = buildString {
                    if (result.stdout.isNotBlank()) append(result.stdout.take(1500))
                    if (result.stderr.isNotBlank()) append("\n[stderr] ${result.stderr.take(500)}")
                    append("\n[exit=${result.exitCode ?: "unknown"}]")
                }
                ToolResult.Success("Termux output:\n$out")
            }
        }
    }
}

/** termux_check tool: diagnostics. */
class TermuxCheckTool : Tool {
    override val name = "termux_check"
    override val description = "Check whether Termux is installed, permitted and reachable."
    override val risk = RiskLevel.SAFE
    override fun parameters() = JsonObject(emptyMap())
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val c = ctx.appContext
        if (!TermuxManager.isInstalled(c)) return ToolResult.Failure("Termux is NOT installed. Install it from F-Droid or GitHub.")
        val perm = TermuxManager.hasRunCommandPermission(c)
        return ToolResult.Success(
            "Termux installed. RUN_COMMAND permission: ${if (perm) "granted" else "NOT granted"}. " + TermuxManager.externalAppsEnabledHint()
        )
    }
}
