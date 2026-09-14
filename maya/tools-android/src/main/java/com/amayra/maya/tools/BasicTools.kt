package com.amayra.maya.tools

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

internal fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull
internal fun JsonObject.bool(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.booleanOrNull
internal fun JsonObject.int(key: String): Int? =
    (this[key] as? JsonPrimitive)?.intOrNull

internal fun strSchema(vararg required: String): JsonObject = JsonObject(mapOf(
    "type" to JsonPrimitive("object"),
    "properties" to JsonObject(emptyMap()),
    "required" to JsonArray(required.map { JsonPrimitive(it) })
))

internal fun argSchema(props: Map<String, String>, required: List<String>): JsonObject = JsonObject(mapOf(
    "type" to JsonPrimitive("object"),
    "properties" to JsonObject(props.mapValues { (_, desc) ->
        JsonObject(mapOf("type" to JsonPrimitive("string"), "description" to JsonPrimitive(desc)))
    }),
    "required" to JsonArray(required.map { JsonPrimitive(it) })
))

/** Current date/time. */
class TimeTool : Tool {
    override val name = "get_time"
    override val description = "Get the current date and time on the device."
    override val risk = RiskLevel.SAFE
    override fun parameters() = JsonObject(emptyMap())
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val now = java.util.Date()
        val date = java.text.SimpleDateFormat("EEEE, d MMMM yyyy", java.util.Locale.getDefault()).format(now)
        val time = java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault()).format(now)
        return ToolResult.Success("Current date: $date, time: $time")
    }
}

/** Battery level and charging state. */
class BatteryTool : Tool {
    override val name = "battery_status"
    override val description = "Get battery percentage and charging status."
    override val risk = RiskLevel.SAFE
    override fun parameters() = JsonObject(emptyMap())
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val intent = ctx.appContext.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return ToolResult.Failure("Battery status unavailable.")
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        if (level < 0 || scale <= 0) return ToolResult.Failure("Battery readings invalid.")
        val pct = level * 100 / scale
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        return ToolResult.Success("Battery at $pct%, ${if (charging) "charging" else "discharging"}.")
    }
}

/** Flashlight on/off via CameraManager torch mode. */
class TorchTool : Tool {
    override val name = "flashlight"
    override val description = "Turn the flashlight on or off. Args: {\"on\": true|false}."
    override val risk = RiskLevel.DEFAULT
    override fun parameters() = JsonObject(mapOf(
        "type" to JsonPrimitive("object"),
        "properties" to JsonObject(mapOf(
            "on" to JsonObject(mapOf(
                "type" to JsonPrimitive("boolean"),
                "description" to JsonPrimitive("true = on, false = off")
            ))
        )),
        "required" to JsonArray(listOf(JsonPrimitive("on")))
    ))
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val on = args.bool("on") ?: return ToolResult.Failure("Missing 'on' argument.")
        val cam = ctx.appContext.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return ToolResult.Failure("Camera service unavailable.")
        val cameraId = cam.cameraIdList.firstOrNull { id ->
            val chars = cam.getCameraCharacteristics(id)
            chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: return ToolResult.Failure("No flash unit found on this device.")
        return try {
            cam.setTorchMode(cameraId, on)
            ToolResult.Success("Flashlight turned ${if (on) "on" else "off"}.")
        } catch (t: Throwable) {
            ToolResult.Failure("Flashlight failed (camera permission may be needed): ${t.message}")
        }
    }
}

/** Launch an installed app by name using the package manager. */
class OpenAppTool : Tool {
    override val name = "open_app"
    override val description = "Open an installed app by name (e.g. 'WhatsApp', 'YouTube', 'Camera')."
    override val risk = RiskLevel.DEFAULT
    override fun parameters() = argSchema(mapOf("app_name" to "Name of the app to open"), listOf("app_name"))
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val query = args.str("app_name")?.trim() ?: return ToolResult.Failure("Missing app_name.")
        val pm = ctx.appContext.packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        val labeled = apps.map { it.loadLabel(pm).toString() to it }
        val packageAliases = AppPackageAliases.packagesFor(query)
        // Prefer an exact known package alias (WhatsApp includes both personal
        // and Business packages), then fall back to launcher labels. Only one
        // exact package/label wins; unknown and ambiguous queries stay failures.
        val packageMatches = apps.filter { it.activityInfo.packageName in packageAliases }
        val match = when {
            packageMatches.size == 1 -> labeled.firstOrNull { it.second in packageMatches }
            packageMatches.size > 1 -> return ToolResult.Failure("Multiple installed apps match \"$query\"; please be specific.")
            else -> labeled.firstOrNull { it.first.equals(query, ignoreCase = true) }
                ?: labeled.firstOrNull { it.first.contains(query, ignoreCase = true) }
        } ?: return ToolResult.Failure(
            "No installed app matches \"$query\". Installed apps include: " +
                labeled.map { it.first }.sorted().take(15).joinToString(", ") + "…"
        )
        return try {
            val pkg = match.second.activityInfo.packageName
            val launch = pm.getLaunchIntentForPackage(pkg)
                ?: return ToolResult.Failure("App ${match.first} has no launcher activity.")
            ctx.appContext.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            ToolResult.Success("Opened ${match.first}.")
        } catch (t: Throwable) {
            ToolResult.Failure("Could not open ${match.first}: ${t.message}")
        }
    }
}

/** Open a URL in the browser. */
class OpenUrlTool : Tool {
    override val name = "open_url"
    override val description = "Open a URL in the browser. Args: {\"url\": \"https://…\"}."
    override val risk = RiskLevel.DEFAULT
    override fun parameters() = argSchema(mapOf("url" to "The URL to open"), listOf("url"))
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val raw = args.str("url") ?: return ToolResult.Failure("Missing url.")
        val url = if (raw.startsWith("http")) raw else "https://$raw"
        return try {
            ctx.appContext.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            ToolResult.Success("Opened $url.")
        } catch (t: Throwable) {
            ToolResult.Failure("No browser available for $url: ${t.message}")
        }
    }
}

/** Google web search (mirrors reference behavior of opening a search URL). */
class WebSearchTool : Tool {
    override val name = "web_search"
    override val description = "Search the web in the browser. Args: {\"query\": \"…\"}."
    override val risk = RiskLevel.SAFE
    override fun parameters() = argSchema(mapOf("query" to "Search query"), listOf("query"))
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val q = args.str("query") ?: return ToolResult.Failure("Missing query.")
        val url = "https://www.google.com/search?q=" + Uri.encode(q)
        return try {
            ctx.appContext.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            ToolResult.Success("Searching the web for \"$q\".")
        } catch (t: Throwable) {
            ToolResult.Failure("No browser available: ${t.message}")
        }
    }
}

/** Device facts: model, Android version, storage. */
class DeviceInfoTool : Tool {
    override val name = "device_info"
    override val description = "Get device model, manufacturer, Android version and free storage."
    override val risk = RiskLevel.SAFE
    override fun parameters() = JsonObject(emptyMap())
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val stat = StatFs(Environment.getDataDirectory().path)
        val freeGb = stat.availableBytes / (1024.0 * 1024 * 1024)
        val totalGb = stat.totalBytes / (1024.0 * 1024 * 1024)
        return ToolResult.Success(
            "Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} " +
                "(SDK ${Build.VERSION.SDK_INT}), free storage %.1f/%.1f GB.".format(freeGb, totalGb)
        )
    }
}

/** Store a long-term memory. */
class RememberTool : Tool {
    override val name = "remember"
    override val description = "Save something to memory. Args: {\"content\": \"…\", \"kind\": \"fact|preference|personal|favorite|interest|calendar|schedule|task\"}."
    override val risk = RiskLevel.SAFE
    override fun parameters() = argSchema(
        mapOf(
            "content" to "The thing to remember",
            "kind" to "Category: fact (default), preference, personal, favorite, interest, calendar, schedule, task"
        ),
        listOf("content")
    )
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val content = args.str("content")?.take(500) ?: return ToolResult.Failure("Missing content.")
        val valid = setOf("fact", "preference", "personal", "favorite", "interest", "calendar", "schedule", "task")
        val kind = args.str("kind")?.lowercase()?.takeIf { it in valid } ?: "fact"
        ctx.database.memoryDao.insert(
            com.amayra.maya.data.MemoryEntity(kind = kind, content = content, importance = 4)
        )
        return ToolResult.Success("Remembered ($kind): $content")
    }
}

/** Store a task / reminder entry. */
class AddTaskTool : Tool {
    override val name = "add_task"
    override val description = "Add a task or reminder to Maya's task list. Args: {\"content\": \"…\"}."
    override val risk = RiskLevel.SAFE
    override fun parameters() = argSchema(mapOf("content" to "The task to add"), listOf("content"))
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val content = args.str("content")?.take(500) ?: return ToolResult.Failure("Missing content.")
        ctx.database.memoryDao.insert(
            com.amayra.maya.data.MemoryEntity(kind = "task", content = content, importance = 3)
        )
        return ToolResult.Success("Task added: $content")
    }
}

/** Clear all memories. Destructive — requires confirmation. */
class ClearMemoryTool : Tool {
    override val name = "clear_memory"
    override val description = "Delete ALL long-term memories. Destructive; requires user confirmation."
    override val risk = RiskLevel.CONFIRM
    override fun parameters() = JsonObject(emptyMap())
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        ctx.database.memoryDao.deleteAll()
        return ToolResult.Success("All memories cleared.")
    }
}
