package com.getmaya.android.core

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Local half of the 10-minute demo / license subsystem, reconstructed from
 * the recovered identifiers: durationLimitMillis, durationMs, rateRemaining,
 * rateReset, rateLimited, rateLimitedAllMs, licensed / unlicensed,
 * LICENSED / NOT_LICENSED / LICENSE_CHECK_FAILED.
 *
 * The original APK talked to these backend endpoints:
 *   /api/license/verify-android, /api/android/bootstrap, /api/android/config,
 *   /api/android/heartbeat, /api/v1/lease
 * Point the backend base URL at your own deployment; without a backend the
 * state machine runs in fully local free mode.
 */
object LicenseState {
    private const val FREE_TALK_PER_DAY_MS = 10 * 60 * 1000L

    private lateinit var prefs: SharedPreferences

    @Volatile var licensed: Boolean = false
        private set
    @Volatile var rateRemaining: Long = FREE_TALK_PER_DAY_MS
        private set
    @Volatile var rateLimited: Boolean = false
        private set
    @Volatile var rateLimitedAllMs: Long = 0
        private set
    var hasGeminiKey: Boolean = false
        private set

    fun init(context: Context) {
        prefs = context.getSharedPreferences("maya_license", Context.MODE_PRIVATE)
        licensed = prefs.getBoolean("licensed", false)
        consumeRollingWindowIfNeeded()
        hasGeminiKey = prefs.getString("gemini_api_key", null)?.isNotBlank() == true
    }

    fun setGeminiKey(key: String) {
        prefs.edit().putString("gemini_api_key", key).apply()
        hasGeminiKey = key.isNotBlank()
    }

    fun applyLicenseResult(licensedNow: Boolean) {
        licensed = licensedNow
        prefs.edit().putBoolean("licensed", licensedNow).apply()
    }

    data class TalkGate(val allowed: Boolean, val sessionMs: Long, val reason: String)

    fun checkTalkAccess(): TalkGate {
        if (licensed) return TalkGate(true, Long.MAX_VALUE, "")
        consumeRollingWindowIfNeeded()
        return if (rateRemaining > 0) {
            TalkGate(true, rateRemaining, "")
        } else {
            rateLimited = true
            TalkGate(false, 0, "Free mode gives you 10 minutes of talk a day. Try again after $rateReset.")
        }
    }

    /** Called by the voice session as talk time is consumed. */
    fun recordTalkDuration(durationMs: Long) {
        if (licensed) return
        val used = prefs.getLong("usedTodayMs", 0L) + durationMs
        prefs.edit().putLong("usedTodayMs", used).apply()
        rateRemaining = (FREE_TALK_PER_DAY_MS - used).coerceAtLeast(0)
        rateLimited = rateRemaining <= 0
    }

    private fun consumeRollingWindowIfNeeded() {
        val day = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        if (prefs.getString("usedDay", null) != day) {
            prefs.edit().putString("usedDay", day).putLong("usedTodayMs", 0L).apply()
            rateRemaining = FREE_TALK_PER_DAY_MS
            rateLimited = false
        } else {
            rateRemaining = (FREE_TALK_PER_DAY_MS - prefs.getLong("usedTodayMs", 0L)).coerceAtLeast(0)
        }
    }

    /** Hook for /api/license/verify-android — call from your own backend client. */
    fun verifyWithBackend(baseUrl: String): Result<Boolean> {
        return Result.failure(UnsupportedOperationException(
            "Connect your backend at $baseUrl implementing /api/license/verify-android"
        ))
    }

    fun statusString(): String {
        val st = if (licensed) "LICENSED" else if (rateLimited) "LICENSE_CHECK_FAILED" else "NOT_LICENSED"
        return "$st durationLimitMillis=$rateRemaining rateReset=$rateResetText"
    }

    private val rateResetText: String
        get() = SimpleDateFormat("EEE HH:mm", Locale.US).format(
            Date(System.currentTimeMillis() + 24 * 60 * 60 * 1000L)
        )
}
