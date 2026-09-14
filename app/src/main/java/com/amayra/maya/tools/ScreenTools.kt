package com.amayra.maya.tools

import com.amayra.maya.MayaApplication
import com.amayra.maya.core.AssistantState
import com.amayra.maya.core.StateBus
import com.amayra.maya.screen.ScreenCaptureService
import com.amayra.maya.screen.ScreenShareBus
import com.amayra.maya.tools.RiskLevel
import com.amayra.maya.tools.Tool
import com.amayra.maya.tools.ToolContext
import com.amayra.maya.tools.ToolResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * screen_share: starts/stops the MediaProjection capture service. The consent
 * dialog itself is shown by MainActivity (StateBus.screenShareRequest → launcher),
 * which forwards the result on ScreenShareBus.consent.
 */
class ScreenShareTool : Tool {
    override val name = "screen_share"
    override val description =
        "Start screen sharing so Maya can see the current screen (asks the user for Android's media-projection consent dialog). Use when the user says 'screen share', 'dekh mera screen'."
    override val risk = RiskLevel.DEFAULT
    override fun parameters() = JsonObject(mapOf(
        "type" to JsonPrimitive("object"),
        "properties" to JsonObject(mapOf(
            "on" to JsonObject(mapOf("type" to JsonPrimitive("boolean"), "description" to JsonPrimitive("true = request consent + start capture, false = stop")))
        )),
        "required" to JsonArray(listOf(JsonPrimitive("on")))
    ))

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val on = (args["on"] as? JsonPrimitive)?.contentOrNull != "false"
        return if (on) {
            if (ScreenShareBus.active.value) {
                return ToolResult.Success("Screen sharing is already active — use see_screen to look at it.")
            }
            StateBus.requestScreenShareStart()
            // Wait briefly for MainActivity to forward the consent result and the
            // service to begin capturing (the consent dialog needs the user anyway).
            val started = withTimeoutOrNull(30_000) {
                ScreenShareBus.active.first { it }
            }
            if (started == true) {
                ToolResult.Success("Screen sharing active. Use see_screen to look at the screen.")
            } else {
                ToolResult.Failure(
                    "Screen share was not started — the user may have dismissed the consent dialog."
                )
            }
        } else {
            ScreenCaptureService.stop(ctx.appContext)
            ToolResult.Success("Screen share stopped.")
        }
    }
}

/**
 * see_screen: grabs the newest captured frame and asks the configured vision
 * model to describe it. Requires an active screen-share session.
 */
class SeeScreenTool : Tool {
    override val name = "see_screen"
    override val description =
        "Look at the user's current screen and describe what is on it (requires an active screen-share session). Use to answer 'what am I looking at', verify an app state, or read screen content."
    override val risk = RiskLevel.DEFAULT
    override fun parameters() = JsonObject(mapOf(
        "type" to JsonPrimitive("object"),
        "properties" to JsonObject(mapOf(
            "question" to JsonObject(mapOf("type" to JsonPrimitive("string"),
                "description" to JsonPrimitive("What to look for on the screen, e.g. 'what is the top notification' or 'is the order confirmed'")))
        ))
    ))

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        if (!ScreenShareBus.active.value) {
            return ToolResult.Failure(
                "No active screen share. Call screen_share first and the user must accept the consent dialog."
            )
        }
        val dataUrl = ScreenCaptureService.latestFrameDataUrl()
            ?: return ToolResult.Failure("No frame captured yet — try again in a second.")
        val question = (args["question"] as? JsonPrimitive)?.contentOrNull
            ?: "Describe this phone screen: which app is open and what are the key elements?"
        val app = ctx.appContext as? MayaApplication
        val answer = app?.ai?.visionOnce(dataUrl, question)
            ?: return ToolResult.Failure(
                "The vision model could not describe the screen (check the AI provider key/model)."
            )
        return ToolResult.Success(answer.take(1200))
    }
}
