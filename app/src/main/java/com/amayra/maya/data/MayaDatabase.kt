package com.amayra.maya.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Lightweight persistence engine. AGP 9's built-in Kotlin cannot apply KSP, so
 * instead of Room we persist each table as a JSON file with atomic writes and
 * a mutex-serialized in-memory cache. Same DAO surface as the Room design.
 */
class JsonTable<T>(private val file: File, private val encode: (T) -> JSONObject, private val decode: (JSONObject) -> T) {
    private val mutex = Mutex()
    private val cache = mutableListOf<T>()
    private var loaded = false

    private suspend fun ensureLoaded() {
        if (loaded) return
        withContext(Dispatchers.IO) {
            try {
                cache.clear()
                if (file.exists()) {
                    val arr = JSONArray(file.readText())
                    for (i in 0 until arr.length()) cache.add(decode(arr.getJSONObject(i)))
                }
            } catch (_: Throwable) { /* start fresh on corruption */ }
            loaded = true
        }
    }

    private suspend fun persist() = withContext(Dispatchers.IO) {
        val arr = JSONArray()
        cache.forEach { arr.put(encode(it)) }
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(file) || (file.delete() && tmp.renameTo(file))
    }

    suspend fun insert(item: T): Long = mutex.withLock {
        ensureLoaded()
        cache.add(item)
        persist()
        cache.size.toLong()
    }

    suspend fun all(): List<T> = mutex.withLock { ensureLoaded(); cache.toList() }

    suspend fun clear(): Unit = mutex.withLock { ensureLoaded(); cache.clear(); persist() }

    suspend fun updateAll(transform: (T) -> T?): Unit = mutex.withLock {
        ensureLoaded()
        val updated = cache.mapNotNull(transform)
        cache.clear(); cache.addAll(updated)
        persist()
    }

    suspend fun countWhere(pred: (T) -> Boolean): Int = mutex.withLock { ensureLoaded(); cache.count(pred) }
}

/** One chat message in a conversation. */
data class MessageEntity(
    val id: Long = 0,
    val conversationId: String,
    val role: String,              // user | assistant | system | tool
    val content: String,
    val attachmentUri: String? = null,
    val attachmentMime: String? = null,
    val toolName: String? = null,
    val toolCallId: String? = null,
    val toolArgsJson: String? = null,
    val thoughtSignature: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

/** Long-term memory entry (facts, preferences, tasks). */
data class MemoryEntity(
    val id: Long = 0,
    val kind: String,              // fact | preference | task
    val content: String,
    val importance: Int = 3,       // 1..5
    val pinned: Boolean = false,
    val done: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/** Audit log for every tool execution. */
data class ToolLogEntity(
    val id: Long = 0,
    val toolName: String,
    val argsJson: String,
    val resultSummary: String,
    val success: Boolean,
    val confirmed: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

/** Traceability for automated WhatsApp replies. */
data class AutoReplyLogEntity(
    val id: Long = 0,
    val packageName: String,
    val contact: String,
    val incomingText: String,
    val replyText: String,
    /** How the reply was delivered: "opened-chat" | "copied-to-clipboard" | "failed". */
    val delivery: String = "unknown",
    val createdAt: Long = System.currentTimeMillis()
)

class MessageDao(table: JsonTable<MessageEntity>) {
    private val t = table
    private var seq = 0L

    suspend fun insert(m: MessageEntity): Long {
        val id = ++seq
        t.insert(m.copy(id = id))
        return id
    }

    suspend fun forConversation(cid: String): List<MessageEntity> =
        t.all().filter { it.conversationId == cid }.sortedBy { it.id }

    suspend fun recent(cid: String, limit: Int): List<MessageEntity> =
        t.all().filter { it.conversationId == cid }.sortedByDescending { it.id }.take(limit).sortedBy { it.id }

    suspend fun latest(limit: Int): List<MessageEntity> =
        t.all().sortedByDescending { it.id }.take(limit)

    suspend fun deleteConversation(cid: String) =
        t.updateAll { if (it.conversationId == cid) null else it }

    suspend fun deleteAll() = t.clear()
}

class MemoryDao(table: JsonTable<MemoryEntity>) {
    private val t = table
    private var seq = 0L

    suspend fun insert(m: MemoryEntity): Long {
        val id = ++seq
        t.insert(m.copy(id = id))
        return id
    }

    suspend fun all(): List<MemoryEntity> =
        t.all().sortedWith(compareByDescending<MemoryEntity> { it.pinned }.thenByDescending { it.importance }.thenByDescending { it.updatedAt })

    suspend fun markDone(id: Long, now: Long) =
        t.updateAll { if (it.id == id) it.copy(done = true, updatedAt = now) else it }

    suspend fun delete(id: Long) = t.updateAll { if (it.id == id) null else it }

    suspend fun deleteAll() = t.clear()
}

class ToolLogDao(table: JsonTable<ToolLogEntity>) {
    private val t = table
    private var seq = 0L

    suspend fun insert(l: ToolLogEntity) {
        t.insert(l.copy(id = ++seq))
    }

    suspend fun recent(limit: Int): List<ToolLogEntity> =
        t.all().sortedByDescending { it.id }.take(limit)

    suspend fun successCountSince(tool: String, since: Long): Int =
        t.countWhere { it.toolName == tool && it.success && it.createdAt >= since }
}

class AutoReplyDao(table: JsonTable<AutoReplyLogEntity>) {
    private val t = table
    private var seq = 0L

    suspend fun insert(l: AutoReplyLogEntity) {
        t.insert(l.copy(id = ++seq))
    }

    suspend fun countSince(since: Long): Int =
        t.countWhere { it.createdAt >= since }

    /** Delivered replies only — used for the daily auto-reply cap. */
    suspend fun countDeliveredSince(since: Long): Int =
        t.countWhere { it.createdAt >= since && it.delivery != "failed" && it.delivery != "unknown" }

    suspend fun recent(limit: Int): List<AutoReplyLogEntity> =
        t.all().sortedByDescending { it.id }.take(limit)
}

/**
 * Persistence root. Same call surface as the Room version:
 * MayaDatabase.get(context).messageDao.insert(...)
 */
object MayaDatabase {
    @Volatile private var instance: MayaDatabaseHolder? = null

    fun get(context: Context): MayaDatabaseHolder =
        instance ?: synchronized(this) {
            instance ?: MayaDatabaseHolder(context.applicationContext).also { instance = it }
        }
}

class MayaDatabaseHolder(context: Context) {
    private val dir = File(context.filesDir, "store")

    private val messages = JsonTable<MessageEntity>(File(dir, "messages.json"),
        encode = { m ->
            JSONObject().apply {
                put("id", m.id); put("conversationId", m.conversationId); put("role", m.role)
                put("content", m.content); put("createdAt", m.createdAt)
                m.attachmentUri?.let { put("attachmentUri", it) }
                m.attachmentMime?.let { put("attachmentMime", it) }
                m.toolName?.let { put("toolName", it) }
                m.toolCallId?.let { put("toolCallId", it) }
                m.toolArgsJson?.let { put("toolArgsJson", it) }
                m.thoughtSignature?.let { put("thoughtSignature", it) }
            }
        },
        decode = { o ->
            MessageEntity(
                id = o.optLong("id"), conversationId = o.optString("conversationId"),
                role = o.optString("role"), content = o.optString("content"),
                attachmentUri = o.optStringOrNull("attachmentUri"),
                attachmentMime = o.optStringOrNull("attachmentMime"),
                toolName = o.optStringOrNull("toolName"),
                toolCallId = o.optStringOrNull("toolCallId"),
                toolArgsJson = o.optStringOrNull("toolArgsJson"),
                thoughtSignature = o.optStringOrNull("thoughtSignature"),
                createdAt = o.optLong("createdAt")
            )
        })

    private val memories = JsonTable<MemoryEntity>(File(dir, "memories.json"),
        encode = { m ->
            JSONObject().apply {
                put("id", m.id); put("kind", m.kind); put("content", m.content)
                put("importance", m.importance); put("pinned", m.pinned); put("done", m.done)
                put("createdAt", m.createdAt); put("updatedAt", m.updatedAt)
            }
        },
        decode = { o ->
            MemoryEntity(
                id = o.optLong("id"), kind = o.optString("kind"), content = o.optString("content"),
                importance = o.optInt("importance", 3), pinned = o.optBoolean("pinned"),
                done = o.optBoolean("done"), createdAt = o.optLong("createdAt"), updatedAt = o.optLong("updatedAt")
            )
        })

    private val toolLogs = JsonTable<ToolLogEntity>(File(dir, "tool_logs.json"),
        encode = { l ->
            JSONObject().apply {
                put("id", l.id); put("toolName", l.toolName); put("argsJson", l.argsJson)
                put("resultSummary", l.resultSummary); put("success", l.success)
                put("confirmed", l.confirmed); put("createdAt", l.createdAt)
            }
        },
        decode = { o ->
            ToolLogEntity(
                id = o.optLong("id"), toolName = o.optString("toolName"), argsJson = o.optString("argsJson"),
                resultSummary = o.optString("resultSummary"), success = o.optBoolean("success"),
                confirmed = o.optBoolean("confirmed"), createdAt = o.optLong("createdAt")
            )
        })

    private val autoReply = JsonTable<AutoReplyLogEntity>(File(dir, "auto_reply.json"),
        encode = { l ->
            JSONObject().apply {
                put("id", l.id); put("packageName", l.packageName); put("contact", l.contact)
                put("incomingText", l.incomingText); put("replyText", l.replyText)
                put("delivery", l.delivery); put("createdAt", l.createdAt)
            }
        },
        decode = { o ->
            AutoReplyLogEntity(
                id = o.optLong("id"), packageName = o.optString("packageName"), contact = o.optString("contact"),
                incomingText = o.optString("incomingText"), replyText = o.optString("replyText"),
                delivery = o.optString("delivery", "unknown"), createdAt = o.optLong("createdAt")
            )
        })

    val messageDao = MessageDao(messages)
    val memoryDao = MemoryDao(memories)
    val toolLogDao = ToolLogDao(toolLogs)
    val autoReplyDao = AutoReplyDao(autoReply)
}

private fun JSONObject.optStringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) optString(key) else null
