package com.amayra.maya.avatar.live2d

import android.content.Context
import com.amayra.maya.core.MayaLog

/**
 * Discovers and validates the Live2D model packaged under assets/live2d/maya/.
 * All paths are asset-relative — no absolute filesystem paths are ever used.
 *
 * A model is LOADABLE only when:
 *   1. maya.model3.json exists and parses as a valid model3.json
 *   2. version == 3 (Cubism 3/4/5 format)
 *   3. the referenced .moc3 exists and starts with the MOC3 magic
 *   4. at least one referenced texture exists
 * Every check failure is captured in [Live2DModelStatus] with the exact missing
 * file so the user knows precisely what to supply.
 */
class Live2DModelRepository(private val context: Context) {

    companion object {
        const val MODEL_DIR = "live2d/maya"
        const val MODEL3_JSON = "maya.model3.json"
        const val README = "live2d/maya/README.txt"
    }

    /** Result of probing the packaged assets. */
    data class Live2DModelStatus(
        val state: State,
        val message: String,
        val model: Model3Json? = null,
        val motionGroups: List<String> = emptyList(),
        val expressionNames: List<String> = emptyList()
    ) {
        enum class State { NOT_PACKAGED, INVALID, READY }
    }

    private var cached: Live2DModelStatus? = null

    /** Probes assets (cached). Safe to call from any thread. */
    fun status(): Live2DModelStatus {
        cached?.let { return it }
        val s = probe()
        cached = s
        return s
    }

    /** Clears the cache so a freshly-supplied model is picked up next launch. */
    fun invalidate() { cached = null }

    private fun invalid(msg: String) = Live2DModelStatus(Live2DModelStatus.State.INVALID, msg)

    private fun probe(): Live2DModelStatus {
        // 1) model3.json present?
        val model3Text = try {
            context.assets.open("$MODEL_DIR/$MODEL3_JSON").bufferedReader().use { it.readText() }
        } catch (t: Throwable) {
            return Live2DModelStatus(
                Live2DModelStatus.State.NOT_PACKAGED,
                "No Live2D model packaged. Place maya.model3.json + maya.moc3 + textures under assets/$MODEL_DIR/ — see the bundled README.txt for the exact layout."
            )
        }

        // 2) Valid model3.json?
        val model = Live2DFormat.parseModel3(model3Text).getOrElse { t ->
            return invalid("maya.model3.json is not valid JSON (model3 format): ${t.message}")
        }

        // 3) Version 3?
        if (model.version != 3) {
            return invalid("model3.json \"version\" must be 3 (found ${model.version}). Re-export from Cubism 4/5 as a Cubism-3 model.")
        }

        // 4) moc3 exists with MOC3 magic?
        val mocPath = model.fileReferences.moc
            ?: return invalid("model3.json fileReferences.moc missing — cannot locate the .moc3 file.")
        if (!mocPath.endsWith(".moc3", ignoreCase = true)) {
            return invalid("fileReferences.moc must point to a .moc3 file (found \"$mocPath\").")
        }
        val mocBytes = try {
            context.assets.open("$MODEL_DIR/$mocPath").use { it.readBytes() }
        } catch (t: Throwable) {
            return invalid("Referenced moc file missing: $mocPath")
        }
        val magic = try {
            String(mocBytes, 0, 4, Charsets.US_ASCII)
        } catch (t: Throwable) { "" }
        if (magic != Live2DFormat.MOC3_MAGIC) {
            return invalid("$mocPath is not a real Cubism 3 moc (missing MOC3 magic). Do not rename old .moc/.model.json files.")
        }

        // 5) At least one texture exists.
        val textures = model.fileReferences.textures.flatten()
        if (textures.isEmpty()) {
            return invalid("model3.json references no textures (fileReferences.textures).")
        }
        val missingTex = textures.firstOrNull { tex ->
            runCatching { context.assets.open("$MODEL_DIR/$tex").close() }.isFailure
        }
        if (missingTex != null) {
            return invalid("Referenced texture missing: $missingTex")
        }

        // All good — enumerate motions/expressions for the controller.
        val motionGroups = model.fileReferences.motions.keys.toList()
        val exprs = model.fileReferences.expressions.mapNotNull { it.name }
        return Live2DModelStatus(
            Live2DModelStatus.State.READY,
            "Live2D model ready: ${model.fileReferences.moc}",
            model = model,
            motionGroups = motionGroups,
            expressionNames = exprs
        )
    }

    /** Reads an expression3.json by name; null when the model has none. */
    fun loadExpression(name: String): Expression3Json? {
        val model = status().model ?: return null
        val ref = model.fileReferences.expressions.firstOrNull { it.name == name } ?: return null
        val file = ref.file ?: return null
        return try {
            val text = context.assets.open("$MODEL_DIR/$file").bufferedReader().use { it.readText() }
            Live2DFormat.parseExpression3(text).getOrNull()
        } catch (t: Throwable) {
            MayaLog.w("L2D", "Expression $name failed to load: ${t.message}")
            null
        }
    }

    /** Opens a motion3.json stream for the given group/index, or null. */
    fun openMotion(group: String, index: Int): java.io.InputStream? {
        val model = status().model ?: return null
        val ref = model.fileReferences.motions[group]?.getOrNull(index) ?: return null
        val file = ref.file ?: return null
        return try {
            context.assets.open("$MODEL_DIR/$file")
        } catch (t: Throwable) {
            MayaLog.w("L2D", "Motion $group/$index ($file) failed to load: ${t.message}")
            null
        }
    }

    /** Opens a texture asset by its model-relative path. */
    fun openTexture(texturePath: String): java.io.InputStream? = try {
        context.assets.open("$MODEL_DIR/$texturePath")
    } catch (t: Throwable) {
        MayaLog.w("L2D", "Texture $texturePath failed to load: ${t.message}")
        null
    }
}
