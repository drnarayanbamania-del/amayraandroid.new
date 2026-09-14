package com.amayra.pc.tools

import com.amayra.pc.core.PcLog
import kotlinx.serialization.json.*
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.event.KeyEvent
import java.io.File
import java.net.URI

/** Shared Robot handle for all hands-on tools. */
object Hands {
    val robot: java.awt.Robot by lazy { java.awt.Robot() }
    val screenSize get() = Toolkit.getDefaultToolkit().screenSize
}

/**
 * Application + window + keyboard/mouse tools. Windows-first (tasklist /
 * PowerShell) with graceful honest failures elsewhere.
 */
class AppTools(private val isWindows: Boolean = System.getProperty("os.name").lowercase().contains("windows")) {

    fun registerAll(registry: ToolRegistry) {
        registry.register(OpenApplication())
        registry.register(CloseApplication())
        registry.register(ListApplications())
        registry.register(SwitchWindow())
        registry.register(ListWindows())
        registry.register(ActiveWindow())
        registry.register(MoveWindowTool())
        registry.register(ResizeWindowTool())
        registry.register(WindowStateTool())
        registry.register(CloseWindowTool())
        registry.register(ShellCommand())
        registry.register(OpenUrl())
        registry.register(ClipboardSet())
    }

    private fun str(args: JsonObject, key: String): String? =
        (args[key] as? JsonPrimitive)?.contentOrNull

    /** Resolve a friendly app name (e.g. "chrome") to a launchable handle. */
    internal fun resolveApp(nameRaw: String): String? {
        val name = nameRaw.trim().lowercase()
        if (File(nameRaw).exists()) return nameRaw
        val known = mapOf(
            "chrome" to listOf("chrome.exe"),
            "notepad" to listOf("notepad.exe"),
            "calculator" to listOf("calc.exe"),
            "explorer" to listOf("explorer.exe"),
            "file explorer" to listOf("explorer.exe"),
            "paint" to listOf("mspaint.exe"),
            "terminal" to listOf("cmd.exe"),
            "cmd" to listOf("cmd.exe"),
            "powershell" to listOf("powershell.exe"),
            "task manager" to listOf("taskmgr.exe"),
            "settings" to listOf("ms-settings:"),  // handled as URI
            "steam" to listOf("steam.exe"),
        )
        known[name]?.firstOrNull { it.endsWith(".exe") }?.let { exe ->
            // Try PATH first
            runCatching {
                val proc = ProcessBuilder("where", exe).start()
                if (proc.waitFor() == 0 && proc.inputStream.bufferedReader().readText().isNotBlank())
                    return exe
            }
            // Common install locations
            val candidates = listOf(
                File(System.getenv("PROGRAMFILES") ?: "", "Google/Chrome/Application/chrome.exe"),
                File(System.getenv("PROGRAMFILES(X86)") ?: "", "Google/Chrome/Application/chrome.exe"),
                File(System.getenv("LOCALAPPDATA") ?: "", "Google/Chrome/Application/chrome.exe"),
                File(System.getenv("PROGRAMFILES(X86)") ?: "", "Steam/steam.exe"),
                File(System.getenv("PROGRAMFILES") ?: "", "Steam/steam.exe"),
            ).filter { it.name.equals(exe, true) && it.exists() }
            if (candidates.isNotEmpty()) return candidates[0].absolutePath
            return exe // let the OS try it
        }
        if (name == "settings") return "ms-settings:"
        // Windows Start-Menu search fallback via start command
        return "start:$nameRaw"
    }

    inner class OpenApplication : Tool {
        override val name = "open_application"
        override val description = "Launch an application by name (e.g. \"Chrome\", \"Notepad\", \"Steam\"). [risk=SAFE]"
        override val risk = RiskLevel.SAFE
        override fun parameters() = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("application") { put("type", "string"); put("description", "Application name or executable") }
            }
            putJsonArray("required") { add(JsonPrimitive("application")) }
        }
        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
            val appName = str(args, "application") ?: return ToolResult.Failure("Missing 'application'")
            val target = resolveApp(appName) ?: return ToolResult.Failure("Application not found: $appName")
            return try {
                when {
                    target.startsWith("ms-settings:") ->
                        Desktop.getDesktop().browse(URI(target))
                    target.startsWith("start:") ->
                        ProcessBuilder("cmd", "/c", "start", "", target.removePrefix("start:")).start()
                    else -> ProcessBuilder(target).start()
                }
                PcLog.i("APPS", "Launched $appName ($target)")
                ToolResult.Success(summary = "Launched $appName.", dataJson = """{"application":"$appName"}""")
            } catch (t: Throwable) {
                ToolResult.Failure("Could not launch $appName: ${t.message}", retryable = true)
            }
        }
    }

    inner class CloseApplication : Tool {
        override val name = "close_application"
        override val description = "Close an application by process name (graceful taskkill, no /F). [risk=DEFAULT]"
        override fun parameters() = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("process") { put("type", "string"); put("description", "Process name, e.g. chrome") }
            }
            putJsonArray("required") { add(JsonPrimitive("process")) }
        }
        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
            val proc = str(args, "process") ?: return ToolResult.Failure("Missing 'process'")
            val force = (args["force"] as? JsonPrimitive)?.booleanOrNull ?: false
            if (force) {
                // Force-kill is more destructive; require confirmation.
                val ok = ctx.requestConfirmation("Force-kill $proc?", "This terminates $proc immediately; unsaved work may be lost.")
                if (!ok) return ToolResult.Failure("User declined force-kill of $proc.")
            }
            val cmd = mutableListOf("taskkill", "/IM", "$proc.exe")
            if (force) cmd += "/F"
            val p = ProcessBuilder(cmd).start()
            val exit = p.waitFor()
            return if (exit == 0) ToolResult.Success("Closed $proc.")
            else ToolResult.Failure("taskkill exited $exit — is '$proc' running?")
        }
    }

    inner class ListApplications : Tool {
        override val name = "list_running_apps"
        override val description = "List currently running application processes. [risk=SAFE]"
        override val risk = RiskLevel.SAFE
        override fun parameters() = buildJsonObject {
            put("type", "object"); put("properties", JsonObject(emptyMap()))
        }
        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
            val p = if (isWindows) ProcessBuilder("tasklist", "/FO", "CSV", "/NH").start()
            else ProcessBuilder("ps", "-eo", "comm").start()
            val out = p.inputStream.bufferedReader().readText()
            val lines = out.lineSequence()
                .filter { it.isNotBlank() }
                .map { if (isWindows) it.split("\",\"").firstOrNull()?.removePrefix("\"") ?: it else it.trim() }
                .distinct()
                .filter { it.isNotEmpty() }
                .take(120)
                .toList()
            return ToolResult.Success("Found ${lines.size} running processes.", dataJson = JsonArray(lines.map { JsonPrimitive(it) }).toString())
        }
    }

    private fun win32Available(): Boolean =
        isWindows && runCatching { com.amayra.pc.tools.win32.Win32WindowManager.available() }.getOrDefault(false)

    private fun winTitle(args: JsonObject): String? = str(args, "title")

    private fun winDataJson(w: com.amayra.pc.tools.win32.Win32WindowManager.WindowInfo): String =
        """{"hwnd":${w.hwnd},"title":"${w.title.replace("\"", "'")}","class":"${w.className}","pid":${w.pid},"rect":${w.rectJson()},"minimized":${w.minimized},"maximized":${w.maximized},"focused":${w.focused}}"""

    inner class ListWindows : Tool {
        override val name = "list_windows"
        override val description = "List all open windows with title, class, position/size (rect [x,y,w,h]), minimized/maximized/focused state. [risk=SAFE]"
        override val risk = RiskLevel.SAFE
        override fun parameters() = buildJsonObject {
            put("type", "object"); put("properties", JsonObject(emptyMap()))
        }
        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
            if (!win32Available()) return ToolResult.Failure("Win32 window enumeration unavailable on this system.")
            val wins = com.amayra.pc.tools.win32.Win32WindowManager.listWindows()
            val json = JsonArray(wins.map { w ->
                buildJsonObject {
                    put("title", w.title)
                    put("class", w.className)
                    put("pid", w.pid)
                    put("rect", JsonArray((w.rect ?: intArrayOf()).map { JsonPrimitive(it) }))
                    put("minimized", w.minimized)
                    put("maximized", w.maximized)
                    put("focused", w.focused)
                }
            })
            val summary = wins.joinToString("; ") { "${it.title}${if (it.focused) " (focused)" else ""}" }.take(1500)
            return ToolResult.Success("${wins.size} windows: $summary", dataJson = json.toString())
        }
    }

    inner class SwitchWindow : Tool {
        override val name = "switch_window"
        override val description = "Bring a window to the foreground by (partial, case-insensitive) title; restores it if minimized. [risk=SAFE]"
        override val risk = RiskLevel.SAFE
        override fun parameters() = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("title") { put("type", "string"); put("description", "Window title or substring") }
            }
            putJsonArray("required") { add(JsonPrimitive("title")) }
        }
        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
            val title = winTitle(args) ?: return ToolResult.Failure("Missing 'title'")
            if (!win32Available()) return ToolResult.Failure("Win32 focus unavailable on this system.")
            val w = com.amayra.pc.tools.win32.Win32WindowManager.focus(title)
                ?: return ToolResult.Failure("No window matching '$title' found.")
            return ToolResult.Success("Focused '${w.title}'.", dataJson = winDataJson(w))
        }
    }

    inner class ActiveWindow : Tool {
        override val name = "get_active_window"
        override val description = "Get the currently focused window's title, class, process id and rectangle. [risk=SAFE]"
        override val risk = RiskLevel.SAFE
        override fun parameters() = buildJsonObject {
            put("type", "object"); put("properties", JsonObject(emptyMap()))
        }
        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
            if (!win32Available()) return ToolResult.Failure("Win32 unavailable on this system.")
            val w = com.amayra.pc.tools.win32.Win32WindowManager.activeWindow()
                ?: return ToolResult.Failure("No focused window right now.")
            return ToolResult.Success("Focused: '${w.title}' (${w.className}, ${w.rect?.get(2)}x${w.rect?.get(3)}).", dataJson = winDataJson(w))
        }
    }

    inner class MoveWindowTool : Tool {
        override val name = "move_window"
        override val description = "Move a window so its top-left corner is at (x, y), by (partial) title. [risk=DEFAULT]"
        override fun parameters() = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("title") { put("type", "string") }
                putJsonObject("x") { put("type", "integer") }
                putJsonObject("y") { put("type", "integer") }
            }
            putJsonArray("required") { add(JsonPrimitive("title")); add(JsonPrimitive("x")); add(JsonPrimitive("y")) }
        }
        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
            val title = winTitle(args) ?: return ToolResult.Failure("Missing 'title'")
            val x = (args["x"] as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() } ?: return ToolResult.Failure("Missing 'x'")
            val y = (args["y"] as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() } ?: return ToolResult.Failure("Missing 'y'")
            if (!win32Available()) return ToolResult.Failure("Win32 unavailable on this system.")
            val w = com.amayra.pc.tools.win32.Win32WindowManager.move(title, x, y)
                ?: return ToolResult.Failure("No window matching '$title' found.")
            return ToolResult.Success("Moved '${w.title}' to ($x,$y).", dataJson = winDataJson(w))
        }
    }

    inner class ResizeWindowTool : Tool {
        override val name = "resize_window"
        override val description = "Resize a window to width x height by (partial) title. [risk=DEFAULT]"
        override fun parameters() = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("title") { put("type", "string") }
                putJsonObject("width") { put("type", "integer") }
                putJsonObject("height") { put("type", "integer") }
            }
            putJsonArray("required") { add(JsonPrimitive("title")); add(JsonPrimitive("width")); add(JsonPrimitive("height")) }
        }
        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
            val title = winTitle(args) ?: return ToolResult.Failure("Missing 'title'")
            val width = (args["width"] as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() } ?: return ToolResult.Failure("Missing 'width'")
            val height = (args["height"] as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() } ?: return ToolResult.Failure("Missing 'height'")
            if (!win32Available()) return ToolResult.Failure("Win32 unavailable on this system.")
            val w = com.amayra.pc.tools.win32.Win32WindowManager.resize(title, width, height)
                ?: return ToolResult.Failure("No window matching '$title' found.")
            return ToolResult.Success("Resized '${w.title}' to ${width}x${height}.", dataJson = winDataJson(w))
        }
    }

    inner class WindowStateTool : Tool {
        override val name = "set_window_state"
        override val description = "Minimize, maximize or restore a window by (partial) title. [risk=DEFAULT]"
        override fun parameters() = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("title") { put("type", "string") }
                putJsonObject("state") { put("type", "string"); put("enum", JsonArray(listOf("minimize", "maximize", "restore").map { JsonPrimitive(it) })) }
            }
            putJsonArray("required") { add(JsonPrimitive("title")); add(JsonPrimitive("state")) }
        }
        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
            val title = winTitle(args) ?: return ToolResult.Failure("Missing 'title'")
            val state = str(args, "state") ?: return ToolResult.Failure("Missing 'state'")
            if (!win32Available()) return ToolResult.Failure("Win32 unavailable on this system.")
            val mgr = com.amayra.pc.tools.win32.Win32WindowManager
            val w = when (state) {
                "minimize" -> mgr.minimize(title)
                "maximize" -> mgr.maximize(title)
                "restore" -> mgr.restore(title)
                else -> null
            } ?: return ToolResult.Failure(if (state in listOf("minimize", "maximize", "restore")) "No window matching '$title' found." else "Unknown state '$state'.")
            return ToolResult.Success("'${w.title}' -> $state.", dataJson = winDataJson(w))
        }
    }

    inner class CloseWindowTool : Tool {
        override val name = "close_window"
        override val description = "Politely close a window (WM_CLOSE — unsaved-work prompts are respected) by (partial) title. [risk=DEFAULT]"
        override fun parameters() = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("title") { put("type", "string") }
            }
            putJsonArray("required") { add(JsonPrimitive("title")) }
        }
        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
            val title = winTitle(args) ?: return ToolResult.Failure("Missing 'title'")
            if (!win32Available()) return ToolResult.Failure("Win32 unavailable on this system.")
            val w = com.amayra.pc.tools.win32.Win32WindowManager.closeWindow(title)
                ?: return ToolResult.Failure("No window matching '$title' found.")
            return ToolResult.Success("Close request sent to '${w.title}'.", dataJson = winDataJson(w))
        }
    }

    inner class ShellCommand : Tool {
        override val name = "execute_command"
        override val description = "Run a shell command and return stdout/stderr. [risk=CONFIRM]"
        override val risk = RiskLevel.CONFIRM
        override fun parameters() = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("command") { put("type", "string"); put("description", "The command to run") }
            }
            putJsonArray("required") { add(JsonPrimitive("command")) }
        }
        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
            val command = str(args, "command") ?: return ToolResult.Failure("Missing 'command'")
            val p = if (isWindows) ProcessBuilder("cmd", "/c", command).start()
            else ProcessBuilder("bash", "-c", command).start()
            val done = p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)
            if (!done) { p.destroyForcibly(); return ToolResult.Failure("Command timed out after 30s.", retryable = true) }
            val out = p.inputStream.bufferedReader().readText()
            val err = p.errorStream.bufferedReader().readText()
            val exit = p.exitValue()
            return if (exit == 0) ToolResult.Success(out.ifBlank { "(no output)" }.take(4000))
            else ToolResult.Failure("Exit $exit. stderr: ${err.take(1000)}")
        }
    }

    inner class OpenUrl : Tool {
        override val name = "open_url"
        override val description = "Open a URL in the default browser. [risk=SAFE]"
        override val risk = RiskLevel.SAFE
        override fun parameters() = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("url") { put("type", "string"); put("description", "Full URL including https://") }
            }
            putJsonArray("required") { add(JsonPrimitive("url")) }
        }
        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
            val url = str(args, "url") ?: return ToolResult.Failure("Missing 'url'")
            return try {
                Desktop.getDesktop().browse(URI(url))
                ToolResult.Success("Opened $url in the default browser.")
            } catch (t: Throwable) {
                ToolResult.Failure("Could not open URL: ${t.message}")
            }
        }
    }

    inner class ClipboardSet : Tool {
        override val name = "clipboard_set"
        override val description = "Put text on the clipboard (use keyboard_hotkey ctrl+v to paste). [risk=SAFE]"
        override val risk = RiskLevel.SAFE
        override fun parameters() = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("text") { put("type", "string") }
            }
            putJsonArray("required") { add(JsonPrimitive("text")) }
        }
        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
            val text = str(args, "text") ?: return ToolResult.Failure("Missing 'text'")
            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
            return ToolResult.Success("Clipboard set (${text.length} chars).")
        }
    }

    internal fun runPowershell(script: String): Result<String> = runCatching {
        val p = ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", script)
            .redirectErrorStream(false).start()
        val finished = p.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)
        if (!finished) { p.destroyForcibly(); error("timeout") }
        val out = p.inputStream.bufferedReader().readText()
        val err = p.errorStream.bufferedReader().readText()
        if (p.exitValue() != 0) error("exit ${p.exitValue()}: ${err.take(300)}")
        out
    }
}
