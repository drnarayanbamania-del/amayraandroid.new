package com.amayra.maya.core

import android.util.Log
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Structured, bounded, auto-redacting logger.
 * Categories: AI, VOICE, AVATAR, WA (WhatsApp), TERMUX, OPENCLAW, AUTO, PERM, NET, TOOLS, CORE.
 * Secrets (API keys, tokens, passwords, Bearer headers) are redacted before write.
 */
object MayaLog {
    private const val TAG = "Maya"
    private const val MAX_LINES = 600
    private val lines = ArrayDeque<String>(MAX_LINES)
    private val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val listeners = mutableListOf<(String) -> Unit>()

    fun addListener(l: (String) -> Unit) { synchronized(listeners) { listeners.add(l) } }

    fun d(cat: String, msg: String) = log("D", cat, msg)
    fun i(cat: String, msg: String) = log("I", cat, msg)
    fun w(cat: String, msg: String) = log("W", cat, msg)
    fun e(cat: String, msg: String, t: Throwable? = null) =
        log("E", cat, if (t != null) "$msg :: ${t.message}" else msg)

    private fun log(level: String, cat: String, msg: String) {
        val safe = redact(msg)
        val line = "${ts.format(Date())} $level/$cat: $safe"
        synchronized(lines) {
            lines.addLast(line)
            while (lines.size > MAX_LINES) lines.removeFirst()
        }
        when (level) {
            "E" -> Log.e(TAG, "[$cat] $safe")
            "W" -> Log.w(TAG, "[$cat] $safe")
            "I" -> Log.i(TAG, "[$cat] $safe")
            else -> Log.d(TAG, "[$cat] $safe")
        }
        val snapshot: List<(String) -> Unit> = synchronized(listeners) { listeners.toList() }
        snapshot.forEach { runCatching { it(line) } }
    }

    fun dump(): List<String> = synchronized(lines) { lines.toList() }

    /** Redacts API keys, tokens, passwords and JSON secret fields. */
    fun redact(s: String): String {
        var out = s
        // JSON-style keys
        out = REDACT_JSON_KEYS.replace(out) { m ->
            val quote = m.groupValues[1]
            "${quote}${m.groupValues[2]}${quote}:${quote}•••${quote}"
        }
        // Bearer / sk- style tokens
        out = BEARER.replace(out, "Bearer •••")
        out = SK_KEYS.replace(out) { m -> m.groupValues[1] + "•••" }
        // Common query params
        out = QUERY_KEYS.replace(out) { m -> "${m.groupValues[1]}=•••" }
        return out
    }

    private val REDACT_JSON_KEYS = Regex("""(["']?)(key|api_?key|token|password|secret|Authorization)(["']?)\s*:\s*["'][^"']*["']""", RegexOption.IGNORE_CASE)
    private val BEARER = Regex("""Bearer\s+[A-Za-z0-9._\-]+""")
    private val SK_KEYS = Regex("""((?:sk|gsk|AIza|rk)[A-Za-z0-9_\-]{8,})""")
    private val QUERY_KEYS = Regex("""([?&](?:key|token|api_key|apikey)=)[^&\s]+""")

    /** Renders an args JSON object safely for logging. */
    fun args(vararg pairs: Pair<String, Any?>): String {
        val o = JSONObject()
        pairs.forEach { (k, v) -> o.put(k, v ?: JSONObject.NULL) }
        return redact(o.toString())
    }
}
