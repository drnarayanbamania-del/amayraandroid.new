package com.amayra.maya.tools

/** Package aliases used only to resolve explicit app-open requests. */
object AppPackageAliases {
    private val aliases = mapOf(
        "whatsapp" to setOf("com.whatsapp", "com.whatsapp.w4b"),
        "youtube" to setOf("com.google.android.youtube"),
        "instagram" to setOf("com.instagram.android"),
        "telegram" to setOf("org.telegram.messenger"),
        "chrome" to setOf("com.android.chrome"),
        "gmail" to setOf("com.google.android.gm"),
        "maps" to setOf("com.google.android.apps.maps"),
        "spotify" to setOf("com.spotify.music"),
        "chatgpt" to setOf("com.openai.chatgpt")
    )

    fun packagesFor(query: String): Set<String> =
        aliases[query.trim().lowercase()].orEmpty()
}
