package com.amayra.scanner.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Lightweight JSON-file persistence: one file backs the documents table with
 * atomic writes and a mutex-serialized in-memory cache. Stores everything
 * locally under filesDir — no account, no cloud, per the offline spec.
 */
class ScannerStore private constructor(context: Context) {
    private val file = File(File(context.filesDir, "store"), "documents.json")
    private val mutex = Mutex()
    private val cache = mutableListOf<DocEntity>()
    private var loaded = false
    private var seq = 0L

    private val _docs = MutableStateFlow<List<DocEntity>>(emptyList())
    val docs: StateFlow<List<DocEntity>> = _docs

    data class DocEntity(
        val id: Long = 0,
        val name: String,
        val text: String,
        val type: String,                 // "scan" | "handwriting" | "pdf" | "gallery"
        val thumbnailPath: String? = null,
        val wordCount: Int = 0,
        val pinned: Boolean = false,
        val createdAt: Long = System.currentTimeMillis()
    )

    private suspend fun ensureLoaded() {
        if (loaded) return
        withContext(Dispatchers.IO) {
            try {
                cache.clear()
                if (file.exists()) {
                    val arr = JSONArray(file.readText())
                    for (i in 0 until arr.length()) cache.add(decode(arr.getJSONObject(i)))
                    seq = cache.maxOfOrNull { it.id } ?: 0L
                }
            } catch (_: Throwable) { /* start fresh on corruption */ }
            loaded = true
        }
        publish()
    }

    private fun publish() {
        _docs.value = cache.sortedWith(
            compareByDescending<DocEntity> { it.pinned }.thenByDescending { it.createdAt }
        )
    }

    private suspend fun persist() = withContext(Dispatchers.IO) {
        val arr = JSONArray()
        cache.forEach { arr.put(encode(it)) }
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(file) || (file.delete() && tmp.renameTo(file))
    }

    suspend fun insert(d: DocEntity): Long = mutex.withLock {
        ensureLoaded()
        val id = ++seq
        val entity = d.copy(
            id = id,
            wordCount = d.wordCount.takeIf { it > 0 } ?: countWords(d.text)
        )
        cache.add(entity)
        persist()
        publish()
        id
    }

    suspend fun all(): List<DocEntity> = mutex.withLock { ensureLoaded(); cache.sortedByDescending { it.createdAt } }

    suspend fun rename(id: Long, newName: String) = mutex.withLock {
        ensureLoaded()
        val idx = cache.indexOfFirst { it.id == id }
        if (idx >= 0) {
            cache[idx] = cache[idx].copy(name = newName)
            persist(); publish()
        }
    }

    suspend fun setPinned(id: Long, pinned: Boolean) = mutex.withLock {
        ensureLoaded()
        val idx = cache.indexOfFirst { it.id == id }
        if (idx >= 0) {
            cache[idx] = cache[idx].copy(pinned = pinned)
            persist(); publish()
        }
    }

    suspend fun delete(id: Long) = mutex.withLock {
        ensureLoaded()
        cache.removeAll { it.id == id }
        persist(); publish()
    }

    suspend fun clearAll() = mutex.withLock {
        ensureLoaded()
        cache.clear()
        persist(); publish()
    }

    private fun encode(d: DocEntity): JSONObject = JSONObject().apply {
        put("id", d.id); put("name", d.name); put("text", d.text); put("type", d.type)
        put("wordCount", d.wordCount); put("pinned", d.pinned); put("createdAt", d.createdAt)
        d.thumbnailPath?.let { put("thumbnailPath", it) }
    }

    private fun decode(o: JSONObject): DocEntity = DocEntity(
        id = o.optLong("id"),
        name = o.optString("name"),
        text = o.optString("text"),
        type = o.optString("type", "scan"),
        thumbnailPath = o.optStringOrNull("thumbnailPath"),
        wordCount = o.optInt("wordCount"),
        pinned = o.optBoolean("pinned"),
        createdAt = o.optLong("createdAt")
    )

    companion object {
        @Volatile private var instance: ScannerStore? = null

        fun get(context: Context): ScannerStore =
            instance ?: synchronized(this) {
                instance ?: ScannerStore(context.applicationContext).also { instance = it }
            }

        fun countWords(text: String): Int = text.trim().split(Regex("\\s+")).count { it.isNotBlank() }
    }
}

private fun JSONObject.optStringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) optString(key) else null
