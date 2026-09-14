package com.amayra.pc.tools

import com.amayra.pc.core.PcLog
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Persistent, categorized memory. Categories from the design brief:
 * PREFERENCES, CONVERSATION, TASK_HISTORY, APP_KNOWLEDGE, WORKFLOW,
 * CORRECTIONS, PROJECT_CONTEXT.
 *
 * Stored as a JSONL file per category under data/memory/ — searchable,
 * editable and auditable by design. Not every conversation is stored: the
 * brain only writes what it deems useful (and corrections always win).
 */
object MemoryStore {
    const val PREFERENCES = "preferences"
    const val CONVERSATION = "conversation"
    const val TASK_HISTORY = "task_history"
    const val APP_KNOWLEDGE = "app_knowledge"
    const val WORKFLOW = "workflow"
    const val CORRECTIONS = "corrections"
    val ALL = listOf(PREFERENCES, CONVERSATION, TASK_HISTORY, APP_KNOWLEDGE, WORKFLOW, CORRECTIONS, "project_context")

    @Serializable
    data class Entry(
        val id: String,
        val category: String,
        val content: String,
        val createdAt: String,
        val source: String = "assistant" // assistant | user | correction
    )

    private val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val dir: Path = Path.of("data", "memory")

    private fun fileFor(category: String) = dir.resolve("$category.jsonl")

    @Synchronized
    fun remember(category: String, content: String, source: String = "assistant"): Entry {
        Files.createDirectories(dir)
        require(category in ALL) { "Unknown memory category: $category" }
        val entry = Entry(
            id = "mem_${System.currentTimeMillis()}",
            category = category,
            content = content.take(2000),
            createdAt = LocalDateTime.now().format(fmt),
            source = source
        )
        Files.writeString(
            fileFor(category), Json.encodeToString(Entry.serializer(), entry) + System.lineSeparator(),
            StandardOpenOption.CREATE, StandardOpenOption.APPEND
        )
        PcLog.i("MEMORY", "Stored [$category]: ${content.take(60)}")
        return entry
    }

    @Synchronized
    fun recall(category: String? = null, query: String? = null, limit: Int = 20): List<Entry> {
        val categories = if (category != null && category in ALL) listOf(category) else ALL
        val entries = categories.mapNotNull { c ->
            val f = fileFor(c)
            if (!Files.exists(f)) return@mapNotNull null
            runCatching {
                Files.readAllLines(f).mapNotNull { line ->
                    runCatching { Json.decodeFromString(Entry.serializer(), line) }.getOrNull()
                }
            }.getOrDefault(emptyList())
        }.flatten()
        val filtered = if (query.isNullOrBlank()) entries
        else entries.filter { it.content.contains(query, ignoreCase = true) }
        return filtered.sortedByDescending { it.createdAt }.take(limit)
    }

    /** Remove entries matching a query — how corrections replace earlier facts. */
    @Synchronized
    fun forget(category: String, query: String): Int {
        val f = fileFor(category)
        if (!Files.exists(f)) return 0
        val lines = Files.readAllLines(f)
        val kept = lines.filter { !it.contains(query, ignoreCase = true) }
        val removed = lines.size - kept.size
        if (removed > 0) {
            val tmp = dir.resolve("$category.jsonl.tmp")
            Files.writeString(tmp, kept.filter(String::isNotBlank).joinToString(System.lineSeparator(), postfix = System.lineSeparator()))
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING)
        }
        return removed
    }
}

/** Memory tools exposed to the model. */
class MemoryTools {
    fun registerAll(registry: ToolRegistry) {
        registry.register(Remember())
        registry.register(Recall())
        registry.register(Forget())
    }

    private fun str(args: JsonObject, key: String): String? = (args[key] as? JsonPrimitive)?.contentOrNull

    inner class Remember : Tool {
        override val name = "remember"
        override val description = "Persist a useful fact about the user or workflow (preference, correction, app knowledge). Use category=PREFERENCES for likes/dislikes, CORRECTIONS when the user corrects you. [risk=SAFE]"
        override val risk = RiskLevel.SAFE
        override fun parameters() = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("category") { put("type", "string"); put("enum", JsonArray(MemoryStore.ALL.map(::JsonPrimitive))) }
                putJsonObject("content") { put("type", "string") }
            }
            putJsonArray("required") { add(JsonPrimitive("category")); add(JsonPrimitive("content")) }
        }
        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
            val category = str(args, "category") ?: MemoryStore.PREFERENCES
            val content = str(args, "content") ?: return ToolResult.Failure("Missing 'content'")
            if (category !in MemoryStore.ALL) return ToolResult.Failure("Unknown category '$category'. Use one of: ${MemoryStore.ALL}")
            val e = MemoryStore.remember(category, content)
            return ToolResult.Success("Remembered [$category]: $content", dataJson = JsonPrimitive(e.id).toString())
        }
    }

    inner class Recall : Tool {
        override val name = "recall_memory"
        override val description = "Search long-term memory, optionally by category and/or keyword. [risk=SAFE]"
        override val risk = RiskLevel.SAFE
        override fun parameters() = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("category") { put("type", "string") }
                putJsonObject("query") { put("type", "string") }
            }
        }
        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
            val category = str(args, "category")
            val query = str(args, "query")
            val results = MemoryStore.recall(category, query)
            return if (results.isEmpty()) ToolResult.Success("No matching memories.")
            else ToolResult.Success(
                results.joinToString("\n") { "[${it.category}] ${it.content} (${it.createdAt})" }.take(3000)
            )
        }
    }

    inner class Forget : Tool {
        override val name = "forget_memory"
        override val description = "Delete memories matching a query in a category (used to replace outdated facts). [risk=CONFIRM]"
        override val risk = RiskLevel.CONFIRM
        override fun parameters() = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("category") { put("type", "string") }
                putJsonObject("query") { put("type", "string") }
            }
            putJsonArray("required") { add(JsonPrimitive("category")); add(JsonPrimitive("query")) }
        }
        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
            val category = str(args, "category") ?: return ToolResult.Failure("Missing 'category'")
            val query = str(args, "query") ?: return ToolResult.Failure("Missing 'query'")
            if (category !in MemoryStore.ALL) return ToolResult.Failure("Unknown category '$category'")
            val removed = MemoryStore.forget(category, query)
            return ToolResult.Success("Removed $removed memory entries matching '$query' in $category.")
        }
    }
}
