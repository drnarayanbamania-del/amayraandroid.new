package com.amayra.maya.voice.tools

import com.amayra.maya.core.StateBus
import com.amayra.maya.tools.RiskLevel
import com.amayra.maya.tools.Tool
import com.amayra.maya.tools.ToolContext
import com.amayra.maya.tools.ToolResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * Voice-side tools (moved from tools-android's MacroTools): Voice Guardian
 * enrollment/status and the wake-word toggle. These live next to the voice
 * engine they control; the registry wiring in the app module imports them
 * from here.
 */
object VoiceGuardianCompanionBridge {
    fun get() = com.amayra.maya.voice.guardian.VoiceGuardian.get()
}

/** enroll_voice: capture-and-enroll is driven from the UI (mic loop); the tool marks intent. */
class GuardianEnrollTool : Tool {
    override val name = "guardian_enroll"
    override val description =
        "Start voice enrollment so Maya recognises Boss's voice (Voice Guardian). Opens the in-app enrollment flow; needs ~6 seconds of speech."
    override val risk = RiskLevel.DEFAULT
    override fun parameters() = JsonObject(mapOf(
        "type" to JsonPrimitive("object"),
        "properties" to JsonObject(mapOf(
            "label" to JsonObject(mapOf("type" to JsonPrimitive("string"), "description" to JsonPrimitive("Name for the voice profile, e.g. 'Boss'")))
        )),
        "required" to JsonArray(listOf(JsonPrimitive("label")))
    ))
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val label = (args["label"] as? JsonPrimitive)?.contentOrNull ?: "Boss"
        val g = VoiceGuardianCompanionBridge.get()
            ?: return ToolResult.Failure("Speaker model (assets/guardian/ecapa6s.tflite) is missing — Guardian unavailable on this build.")
        StateBus.publishGuardian(StateBus.GuardianSignal("enroll:$label", 0f, false))
        return ToolResult.Success("Enrollment started for '$label' — speak naturally for ~6 seconds.")
    }
}

/** guardian_status: profiles + assets state. */
class GuardianStatusTool : Tool {
    override val name = "guardian_status"
    override val description = "Voice Guardian status: enrolled voice profiles, model availability."
    override val risk = RiskLevel.SAFE
    override fun parameters() = JsonObject(emptyMap())
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val g = VoiceGuardianCompanionBridge.get()
            ?: return ToolResult.Success("Voice Guardian: unavailable (speaker model missing) — Maya runs exactly as before.")
        val profiles = g.store.all()
        return ToolResult.Success(
            "Voice Guardian: ${profiles.size} profile(s): " +
                profiles.joinToString { "${it.label} (${it.modelId})" } +
                ". Cohort size: ${g.normalizer.cohortSize}."
        )
    }
}

/** wake_word_toggle: enable/disable the always-listening service. */
class WakeWordToggleTool : Tool {
    override val name = "wake_word_toggle"
    override val description = "Enable or disable always-on 'Hey Maya' wake-word listening. Args: {\"on\": true|false}."
    override val risk = RiskLevel.DEFAULT
    override fun parameters() = JsonObject(mapOf(
        "type" to JsonPrimitive("object"),
        "properties" to JsonObject(mapOf(
            "on" to JsonObject(mapOf("type" to JsonPrimitive("boolean"), "description" to JsonPrimitive("true = start listening, false = stop")))
        )),
        "required" to JsonArray(listOf(JsonPrimitive("on")))
    ))
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val on = (args["on"] as? JsonPrimitive)?.booleanOrNull
            ?: return ToolResult.Failure("Missing 'on'.")
        return try {
            if (on) {
                com.amayra.maya.voice.wakeword.WakeWordService.start(ctx.appContext)
                ToolResult.Success("Wake-word listening started. Say \"Hey Maya\".")
            } else {
                com.amayra.maya.voice.wakeword.WakeWordService.stop(ctx.appContext)
                ToolResult.Success("Wake-word listening stopped.")
            }
        } catch (t: Throwable) {
            ToolResult.Failure("Wake-word toggle failed: ${t.message}")
        }
    }
}
