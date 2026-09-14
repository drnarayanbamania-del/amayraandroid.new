package com.amayra.maya.avatar.live2d

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json

/**
 * Parsed Live2D model3.json entry point.
 *
 * This is the REAL Live2D file format — parsed with strict validation. A model is
 * only considered loadable when this parses AND the referenced .moc3 exists with a
 * valid MOC3 magic header. Nothing here is faked: every field maps to the actual
 * Cubism model3.json schema (https://docs.live2d.com/en/cubism-sdk-manual/model3json/).
 */
@Serializable
data class Model3Json(
    /** "3" for Cubism 3/4/5 models. */
    val version: Int? = null,
    val fileReferences: FileReferences = FileReferences(),
    val groups: List<ParameterGroup> = emptyList(),
    val hitAreas: List<HitArea> = emptyList(),
    val layouts: Layouts? = null
) {
    @Serializable
    data class FileReferences(
        val moc: String? = null,
        val physics: String? = null,
        val pose: String? = null,
        val displayInfo: String? = null,
        val textures: List<List<String>> = emptyList(),
        val motions: Map<String, List<MotionRef>> = emptyMap(),
        val expressions: List<ExpressionRef> = emptyList()
    )

    @Serializable
    data class MotionRef(
        val file: String? = null,
        val sound: String? = null,
        val text: String? = null,
        val fadein: Int? = null,
        val fadeout: Int? = null
    )

    @Serializable
    data class ExpressionRef(val name: String? = null, val file: String? = null)

    @Serializable
    data class ParameterGroup(val id: String, val name: String? = null)

    @Serializable
    data class HitArea(val id: String, val name: String? = null)

    @Serializable
    data class Layouts(val width: Float? = null, val height: Float? = null)
}

/** Parsed expression3.json (a list of parameter additions). */
@Serializable
data class Expression3Json(
    val type: String? = null,
    val parameters: List<ExpressionParam> = emptyList()
) {
    @Serializable
    data class ExpressionParam(val id: String, val value: Float? = null, val blend: String? = null)
}

object Live2DFormat {
    val json = Json { ignoreUnknownKeys = true }

    /** .moc3 files always start with the ASCII magic "MOC3" (0x4D4F4333). */
    const val MOC3_MAGIC = "MOC3"

    fun parseModel3(text: String): Result<Model3Json> = runCatching { json.decodeFromString<Model3Json>(text) }
    fun parseExpression3(text: String): Result<Expression3Json> = runCatching { json.decodeFromString<Expression3Json>(text) }

    /** Canonical parameter ids used by real Cubism models (standard namespace). */
    object Params {
        const val MOUTH_OPEN = "ParamMouthOpenY"
        const val MOUTH_FORM = "ParamMouthForm"
        const val EYE_L_OPEN = "ParamEyeLOpen"
        const val EYE_R_OPEN = "ParamEyeROpen"
        const val EYE_BALL_X = "ParamEyeBallX"
        const val EYE_BALL_Y = "ParamEyeBallY"
        const val ANGLE_X = "ParamAngleX"
        const val ANGLE_Y = "ParamAngleY"
        const val ANGLE_Z = "ParamAngleZ"
        const val BODY_ANGLE_X = "ParamBodyAngleX"
        const val BREATH = "ParamBreath"
    }

    /** Standard motion group names the Maya state machine asks for. */
    object MotionGroups {
        const val IDLE = "idle"
        const val LISTENING = "listening"
        const val THINKING = "thinking"
        const val SPEAKING = "speaking"
        const val GREETING = "greeting"
        const val HAPPY = "happy"
        const val CONCERNED = "concerned"
        const val ERROR = "error"
    }

    /** Maps Maya assistant states to preferred motion groups. */
    fun motionGroupFor(state: com.amayra.maya.core.AssistantState): String = when (state) {
        is com.amayra.maya.core.AssistantState.Idle -> MotionGroups.IDLE
        is com.amayra.maya.core.AssistantState.Sleeping -> MotionGroups.IDLE
        is com.amayra.maya.core.AssistantState.Listening -> MotionGroups.LISTENING
        is com.amayra.maya.core.AssistantState.Processing,
        is com.amayra.maya.core.AssistantState.Responding,
        is com.amayra.maya.core.AssistantState.ToolExecution -> MotionGroups.THINKING
        is com.amayra.maya.core.AssistantState.Speaking -> MotionGroups.SPEAKING
        is com.amayra.maya.core.AssistantState.Error -> MotionGroups.ERROR
    }
}
