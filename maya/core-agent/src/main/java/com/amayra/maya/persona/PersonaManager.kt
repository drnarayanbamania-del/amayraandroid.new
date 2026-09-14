package com.amayra.maya.persona

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.amayra.maya.core.MayaLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import com.amayra.maya.tools.Tool
import com.amayra.maya.tools.ToolContext
import com.amayra.maya.tools.ToolResult

private val Context.personaDataStore by preferencesDataStore(name = "maya_persona")

/** Holds and persists the currently-active persona + voice. */
class PersonaManager(private val context: Context) {
    private val _active = MutableStateFlow(Persona.MAYA)
    val active: kotlinx.coroutines.flow.StateFlow<Persona> = _active

    private val _voice = MutableStateFlow(Persona.MAYA.defaultVoice)
    val voice: kotlinx.coroutines.flow.StateFlow<String> = _voice

    private val KEY_PERSONA = stringPreferencesKey("persona_id")
    private val KEY_VOICE = stringPreferencesKey("persona_voice")

    val personaIdFlow: Flow<String> = context.personaDataStore.data.map { it[KEY_PERSONA] ?: "maya" }
    val voiceFlow: Flow<String> = context.personaDataStore.data.map { it[KEY_VOICE] ?: "" }

    /** Load persisted persona/voice at startup. */
    suspend fun load() {
        val pid = personaIdFlow.first()
        val voice = voiceFlow.first()
        val p = Persona.byId(pid)
        _active.value = p
        // Stored voice must belong to the persona; otherwise fall back to its default
        // (keeps old installs on natural voices instead of stale/retired picks).
        _voice.value = when {
            voice == "Gacrux" -> "Leda" // one-time upgrade: raspy Gacrux → youthful female
            voice in p.voices -> voice
            else -> p.defaultVoice
        }
        MayaLog.i("PERSONA", "Loaded persona=${p.id} voice=${_voice.value}")
    }

    /** Switch persona; optionally keep the current voice if [voice] is null/blank. */
    suspend fun switch(personaId: String, voice: String? = null): Persona {
        val p = Persona.byId(personaId)
        _active.value = p
        _voice.value = (voice?.takeIf { it.isNotBlank() } ?: p.defaultVoice)
        context.personaDataStore.edit {
            it[KEY_PERSONA] = p.id
            it[KEY_VOICE] = _voice.value
        }
        MayaLog.i("PERSONA", "Switched to ${p.displayName} voice=${_voice.value}")
        return p
    }

    // NOTE: TTS voice resolution moved to the app module's PersonaVoiceResolver:
    // core-agent cannot reference core-voice types (GeminiTtsClient.PersonaVoice).
    // The resolver reads [active] and [activeVoice] below.
    val activeVoice: String get() = _voice.value
    suspend fun setVoice(voice: String) {
        _voice.value = voice
        context.personaDataStore.edit { it[KEY_VOICE] = voice }
    }

    /** LLM-callable tool: only explicit user requests may switch persona. */
    inner class SwitchPersonaTool : Tool {
        override val name = "switch_persona"
        override val description =
            "Switch the active persona/voice. Call this ONLY when the user EXPLICITLY asks by name " +
                "to summon a different persona (e.g. 'Friday ko bulao', 'switch to Venom'). " +
                "Target persona the user explicitly named: 'maya', 'friday' or 'venom'. " +
                "Only voice and personality change. Girlfriend mode is a Settings toggle, NOT a persona: " +
                "if the user asks for girlfriend mode, do NOT call this tool."
        override val risk = com.amayra.maya.tools.RiskLevel.CONFIRM
        override fun parameters() = JsonObject(mapOf(
            "type" to JsonPrimitive("object"),
            "properties" to JsonObject(mapOf(
                "persona" to JsonObject(mapOf(
                    "type" to JsonPrimitive("string"),
                    "description" to JsonPrimitive("Target persona the user explicitly named: 'maya', 'friday' or 'venom'.")
                )),
                "voice" to JsonObject(mapOf(
                    "type" to JsonPrimitive("string"),
                    "description" to JsonPrimitive("Optional voice name for the target persona (e.g. 'Kore'). Omit to use the persona default.")
                ))
            )),
            "required" to kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("persona")))
        ))
        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
            val target = (args["persona"] as? JsonPrimitive)?.contentOrNull
                ?: return ToolResult.Failure("Missing 'persona'.")
            val v = (args["voice"] as? JsonPrimitive)?.contentOrNull
            val p = switch(target, v)
            return ToolResult.Success(
                "Persona switched to ${p.displayName}. Speak with voice '${_voice.value}' from now on."
            )
        }
    }
}
