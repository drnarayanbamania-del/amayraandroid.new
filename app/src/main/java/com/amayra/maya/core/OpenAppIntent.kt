package com.amayra.maya.core

/**
 * Deterministic pre-model intent layer: "open X" style commands are matched in
 * code and executed directly, bypassing the model. Groq llama-3.3 frequently
 * answers these in text without emitting the open_app tool call, which made
 * voice "open whatsapp" do nothing. Everything that doesn't match keeps
 * flowing through the normal model tool-call path untouched.
 *
 * Pure matcher — no Android imports — so it is cheaply unit-testable; the
 * caller maps [appName] through the existing OpenAppTool.
 */
object OpenAppIntent {

    /** Common Hinglish/English phrasings → app-name alias table. */
    private val APP_ALIASES = mapOf(
        "whatsapp" to "WhatsApp",
        "whatapp" to "WhatsApp",
        "watsapp" to "WhatsApp",
        "youtube" to "YouTube",
        "camera" to "Camera",
        "kaimra" to "Camera",
        "instagram" to "Instagram",
        "insta" to "Instagram",
        "telegram" to "Telegram",
        "chrome" to "Chrome",
        "google" to "Google",
        "maps" to "Maps",
        "gmail" to "Gmail",
        "phone" to "Phone",
        "settings" to "Settings",
        "gallery" to "Gallery",
        "photos" to "Photos",
        "playstore" to "Play Store",
        "play store" to "Play Store",
        "facebook" to "Facebook",
        "spotify" to "Spotify",
        "chatgpt" to "ChatGPT"
    )

    /** Hindi/Hinglish verbs: kholo/khol/kholna (open), chalu/chalao (start), launch. */
    private val OPEN_VERBS =
        Regex("""\b(open|kholo?|kholna|chalu\s*kar|chalao|launch|start)\b""", RegexOption.IGNORE_CASE)

    data class Match(val appName: String)

    /**
     * Match an "open <app>" command. Returns null for anything that should
     * route to the model (questions, multi-clause sentences, other intents).
     */
    fun match(text: String): Match? {
        val t = text.trim()
        if (t.isEmpty() || t.length > 60) return null           // commands are short; sentences are not
        if (t.contains('?') || t.split(' ').size > 6) return null
        if (!OPEN_VERBS.containsMatchIn(t)) return null

        // Remove the verb + filler so the remaining token(s) are the app name.
        val stripped = t
            .replace(OPEN_VERBS, " ")
            .replace(Regex("""\b(do|please|kar|ke|ko|ka|ki|to|the|a|an|app|jaldi|se|abhi|zara)\b""", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

        // Exact alias hit, then alias substring ("whatsapp kholo" → "whatsapp").
        val hit = APP_ALIASES[stripped.lowercase()]
            ?: APP_ALIASES.entries.firstOrNull { stripped.lowercase().contains(it.key) }?.value
            ?: return null
        return Match(hit)
    }
}
