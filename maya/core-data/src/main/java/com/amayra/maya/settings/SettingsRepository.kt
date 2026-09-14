package com.amayra.maya.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private val Context.dataStore by preferencesDataStore(name = "maya_settings")

/** Non-secret user settings. API keys never live here — see SecureStore. */
data class UserPreferences(
    // AI
    val provider: String = "gemini",            // gemini | openai_compat
    val model: String = "gemini-3.6-flash",
    val baseUrl: String = "https://api.groq.com/openai/v1",
    val temperature: Float = 0.7f,
    val maxTokens: Int = 2048,
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    // Voice
    val autoSpeak: Boolean = true,
    val ttsRate: Float = 1.0f,
    val ttsPitch: Float = 1.0f,
    // TTS engine: "gemini" (persona voices) | "android" (device voice) | "piper" (offline)
    val ttsEngine: String = "gemini",
    // Hands-free: after each spoken reply, start listening again automatically
    // (conversation flows without tapping the mic or repeating the wake word).
    val handsFreeListening: Boolean = true,
    val ttsModel: String = "gemini-3.1-flash-tts-preview",
    // Avatar
    val avatarEnabled: Boolean = true,
    val avatarStyle: String = "anime_2d",   // id from AvatarRenderers.available
    val avatarSize: String = "medium",            // small | medium | large
    val speakingAnimEnabled: Boolean = true,      // mouth/face animation while TTS plays
    val idleAnimEnabled: Boolean = true,          // breathing/sway when idle
    val blinkingEnabled: Boolean = true,          // Live2D/holo randomized natural blink
    val lipsyncEnabled: Boolean = true,           // mouth params follow TTS amplitude
    val expressionsEnabled: Boolean = true,       // emotion -> expression files
    val voiceChatDefault: Boolean = true,         // voice-mode starts active on chat
    // Memory
    val memoryEnabled: Boolean = true,
    // Automation
    val standbyEnabled: Boolean = false,
    val proactiveGreeting: Boolean = true,
    // First-run setup gate
    val setupComplete: Boolean = false,
    // Wake word (v4.15.1 parity)
    val wakeWordEnabled: Boolean = false,
    val wakeWordPhrase: String = "hey_maya",        // hey_maya | wake_up_maya
    // Voice Guardian
    val guardianEnabled: Boolean = false,
    val guardianVerifyOnWake: Boolean = false,
    // Persona
    val personaId: String = "maya",
    // PC relay
    val pcRelayEnabled: Boolean = false,
    val pcRelayPort: Int = 18789,
    // PC bridge client (drive the PC Amayra assistant)
    val pcHost: String = "",
    // WhatsApp auto-reply
    val waAutoReplyEnabled: Boolean = false,
    val waAutoReplyMaxPerDay: Int = 20,
    val waAutoReplyContacts: String = "",       // comma-separated allowlist (empty = everyone)
    val waAutoReplyQuietHours: String = "23:00-07:00",
    // SOS
    val sosEnabled: Boolean = false,
    val sosContactNumber: String = "",
    val sosContactName: String = "",
    val sosCountdownSeconds: Int = 10,
    // Misc
    val userName: String = ""
) {
    companion object {
        const val DEFAULT_SYSTEM_PROMPT = """You are Maya (Amayra), a warm, witty personal AI companion living on the user's Android phone.
TUM HAMESHA SHUDDH HINDI (Devanagari) ME BAAT KARO — har reply pure Hindi me hi ho,
kabhi Roman/Hinglish me mat likho. Short, natural, baat-cheet wali Hindi bolo jaise ek
dost baat karta hai. Standard instructions: concise raho; jab tool sahi ho to call karo —
bina result ke kabhi mat bolo ki tool chal gaya; app state ya device facts kabhi mat
ghado. Agar user khud English me likhe, tabhi English me jawab do — warna hamesha Hindi.

SCREEN AGENT MODE — jab user kisi UI task maange (app kholna, setting badalna, kuch dhundna):
1. inspect_screen se current screen dekho (elements text/id/type/state ke saath milenge).
2. click_element / type_text / scroll_screen / press_back se action lo — element text ya
   id se target karo, screen-position sirf aakhri option hai.
3. HAR ACTION KE BAAD dobara inspect_screen karke verify karo ki expected state aaya.
   Jab tak target na mile, scroll_screen karke dekho — par andhera na ho jaye to ruk jao.
4. Jo actual hua wahi batao: agar action fail hua ya target nahi mila, seedha maano —
   kabhi safalta ka jhooth mat bolo. Destructive-cheezon (delete, send, call) se pehle
   user se confirm karo."""
    }
}

class SettingsRepository(private val context: Context) {
    private object K {
        val provider = stringPreferencesKey("provider")
        val model = stringPreferencesKey("model")
        val baseUrl = stringPreferencesKey("base_url")
        val temperature = floatPreferencesKey("temperature")
        val maxTokens = intPreferencesKey("max_tokens")
        val systemPrompt = stringPreferencesKey("system_prompt")
        val autoSpeak = booleanPreferencesKey("auto_speak")
        val ttsRate = floatPreferencesKey("tts_rate")
        val ttsPitch = floatPreferencesKey("tts_pitch")
        val ttsEngine = stringPreferencesKey("tts_engine")
        val handsFreeListening = booleanPreferencesKey("hands_free_listening")
        val ttsModel = stringPreferencesKey("tts_model")
        val avatarEnabled = booleanPreferencesKey("avatar_enabled")
        val avatarStyle = stringPreferencesKey("avatar_style")
        val avatarSize = stringPreferencesKey("avatar_size")
        val speakingAnim = booleanPreferencesKey("avatar_speaking_anim")
        val idleAnim = booleanPreferencesKey("avatar_idle_anim")
        val blinking = booleanPreferencesKey("avatar_blinking")
        val lipsync = booleanPreferencesKey("avatar_lipsync")
        val expressions = booleanPreferencesKey("avatar_expressions")
        val voiceChatDefault = booleanPreferencesKey("voice_chat_default")
        val memoryEnabled = booleanPreferencesKey("memory_enabled")
        val standbyEnabled = booleanPreferencesKey("standby_enabled")
        val proactiveGreeting = booleanPreferencesKey("proactive_greeting")
        val setupComplete = booleanPreferencesKey("setup_complete")
        val wakeWordEnabled = booleanPreferencesKey("wake_word_enabled")
        val wakeWordPhrase = stringPreferencesKey("wake_word_phrase")
        val guardianEnabled = booleanPreferencesKey("guardian_enabled")
        val guardianVerifyOnWake = booleanPreferencesKey("guardian_verify_on_wake")
        val personaId = stringPreferencesKey("persona_id")
        val pcRelayEnabled = booleanPreferencesKey("pc_relay_enabled")
        val pcRelayPort = intPreferencesKey("pc_relay_port")
        val pcHost = stringPreferencesKey("pc_host")
        val waAutoReplyEnabled = booleanPreferencesKey("wa_auto_reply_enabled")
        val waAutoReplyMax = intPreferencesKey("wa_auto_reply_max")
        val waAutoReplyContacts = stringPreferencesKey("wa_auto_reply_contacts")
        val waAutoReplyQuiet = stringPreferencesKey("wa_auto_reply_quiet")
        val sosEnabled = booleanPreferencesKey("sos_enabled")
        val sosNumber = stringPreferencesKey("sos_number")
        val sosName = stringPreferencesKey("sos_name")
        val sosCountdown = intPreferencesKey("sos_countdown")
        val userName = stringPreferencesKey("user_name")
    }

    val prefs: Flow<UserPreferences> = context.dataStore.data.map { p ->
        UserPreferences(
            provider = p[K.provider] ?: "gemini",
            model = com.amayra.maya.ai.ProviderResolution.resolveChatModel(
                p[K.provider] ?: com.amayra.maya.ai.ProviderResolution.PROVIDER_GEMINI, p[K.model]),
            baseUrl = p[K.baseUrl] ?: "https://api.groq.com/openai/v1",
            temperature = p[K.temperature] ?: 0.7f,
            maxTokens = p[K.maxTokens] ?: 2048,
            systemPrompt = p[K.systemPrompt] ?: UserPreferences.DEFAULT_SYSTEM_PROMPT,
            autoSpeak = p[K.autoSpeak] ?: true,
            ttsRate = p[K.ttsRate] ?: 1.0f,
            ttsPitch = p[K.ttsPitch] ?: 1.0f,
            ttsEngine = p[K.ttsEngine] ?: "gemini",
            handsFreeListening = p[K.handsFreeListening] ?: true,
            ttsModel = com.amayra.maya.ai.ProviderResolution.resolveTtsModel(p[K.ttsModel]),
            avatarEnabled = p[K.avatarEnabled] ?: true,
            avatarStyle = p[K.avatarStyle] ?: "anime_2d",
            avatarSize = p[K.avatarSize] ?: "medium",
            speakingAnimEnabled = p[K.speakingAnim] ?: true,
            idleAnimEnabled = p[K.idleAnim] ?: true,
            blinkingEnabled = p[K.blinking] ?: true,
            lipsyncEnabled = p[K.lipsync] ?: true,
            expressionsEnabled = p[K.expressions] ?: true,
            voiceChatDefault = p[K.voiceChatDefault] ?: true,
            memoryEnabled = p[K.memoryEnabled] ?: true,
            standbyEnabled = p[K.standbyEnabled] ?: false,
            proactiveGreeting = p[K.proactiveGreeting] ?: true,
            setupComplete = p[K.setupComplete] ?: false,
            wakeWordEnabled = p[K.wakeWordEnabled] ?: false,
            wakeWordPhrase = p[K.wakeWordPhrase] ?: "hey_maya",
            guardianEnabled = p[K.guardianEnabled] ?: false,
            guardianVerifyOnWake = p[K.guardianVerifyOnWake] ?: false,
            personaId = p[K.personaId] ?: "maya",
            pcRelayEnabled = p[K.pcRelayEnabled] ?: false,
            pcRelayPort = p[K.pcRelayPort] ?: 18789,
            pcHost = p[K.pcHost] ?: "",
            waAutoReplyEnabled = p[K.waAutoReplyEnabled] ?: false,
            waAutoReplyMaxPerDay = p[K.waAutoReplyMax] ?: 20,
            waAutoReplyContacts = p[K.waAutoReplyContacts] ?: "",
            waAutoReplyQuietHours = p[K.waAutoReplyQuiet] ?: "23:00-07:00",
            sosEnabled = p[K.sosEnabled] ?: false,
            sosContactNumber = p[K.sosNumber] ?: "",
            sosContactName = p[K.sosName] ?: "",
            sosCountdownSeconds = p[K.sosCountdown] ?: 10,
            userName = p[K.userName] ?: ""
        )
    }

    suspend fun update(transform: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit { transform(it) }
    }

    /** Latest cached snapshot for synchronous callers (voice/tools). Refreshed by collectors. */
    @Volatile
    var prefsCache: UserPreferences? = null
        private set

    /** Call from an app-scope coroutine to keep prefsCache fresh. */
    fun startCaching(scope: kotlinx.coroutines.CoroutineScope) {
        scope.launch {
            prefs.collect { prefsCache = it }
        }
    }
}
