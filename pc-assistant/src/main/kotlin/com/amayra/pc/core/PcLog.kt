package com.amayra.pc.core

import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** Lightweight console logger (mirrors Android's MayaLog style). */
object PcLog {
    @Volatile var verbose: Boolean = false
    private val fmt = DateTimeFormatter.ofPattern("HH:mm:ss")

    fun i(tag: String, msg: String) = println("[${LocalTime.now().format(fmt)}] [$tag] $msg")
    fun w(tag: String, msg: String) = println("[${LocalTime.now().format(fmt)}] [$tag] WARN: $msg")
    fun e(tag: String, msg: String, t: Throwable? = null) =
        println("[${LocalTime.now().format(fmt)}] [$tag] ERROR: $msg${t?.let { " — ${it.message}" } ?: ""}")
    fun d(tag: String, msg: String) { if (verbose) println("[${LocalTime.now().format(fmt)}] [$tag] DEBUG: $msg") }

    /** Redact potentially sensitive argument values in logs. */
    fun args(vararg pairs: Pair<String, String>) =
        pairs.joinToString(" ") { (k, v) -> "$k=$v" }
}
