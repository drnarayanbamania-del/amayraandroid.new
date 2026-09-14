package com.amayra.maya.tools

import android.content.Context
import android.media.projection.MediaProjectionManager
import android.content.Intent
import com.amayra.maya.core.StateBus
import com.amayra.maya.tools.RiskLevel
import com.amayra.maya.tools.Tool
import com.amayra.maya.tools.ToolContext
import com.amayra.maya.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/** Barcode scan: opens the in-app scanner screen; result returns via StateBus. */
class ScanBarcodeTool : Tool {
    override val name = "scan_barcode"
    override val description = "Open the camera barcode scanner and read a QR/barcode. Result comes back after the user aims the camera."
    override val risk = RiskLevel.SAFE
    override fun parameters() = JsonObject(emptyMap())
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        StateBus.requestScan()
        return ToolResult.Success("Scanner opened — waiting for the user to point the camera at a code.")
    }
}
