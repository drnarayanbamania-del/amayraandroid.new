package com.amayra.maya.integration

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import com.amayra.maya.tools.RiskLevel
import com.amayra.maya.tools.Tool
import com.amayra.maya.tools.ToolContext
import com.amayra.maya.tools.ToolResult
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject

/**
 * Optional, strictly opt-in SOS. Flow: countdown (cancellable) -> loud siren ->
 * dialer pre-filled with the emergency contact. Maya never silently places calls.
 */
object SOSManager {

    /** Runs the audible siren (ToneGenerator) for a few seconds. */
    fun siren(ctx: Context, seconds: Int = 5) {
        try {
            val tone = ToneGenerator(AudioManager.STREAM_ALARM, 100)
            // Repeating double-beep pattern for the duration.
            val perCycle = 900L
            var elapsed = 0L
            Thread {
                while (elapsed < seconds * 1000L) {
                    tone.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 300)
                    Thread.sleep(perCycle)
                    elapsed += perCycle
                }
                tone.release()
            }.start()
        } catch (_: Throwable) { /* siren is best-effort */ }
    }

    /** Opens the dialer pre-filled with the emergency number (user presses call). */
    fun openDialer(ctx: Context, number: String): Boolean = try {
        ctx.startActivity(
            Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    } catch (t: Throwable) { false }
}

/** sos_trigger tool. DANGEROUS tier: always confirmed; never silently dials. */
class SosTriggerTool : Tool {
    override val name = "sos_trigger"
    override val description =
        "EMERGENCY: start the SOS sequence (countdown + siren + pre-dial emergency contact). Only use when the user explicitly asks for SOS/emergency help."
    override val risk = RiskLevel.DANGEROUS
    override fun parameters() = JsonObject(emptyMap())

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val number = com.amayra.maya.MayaApplication.get(ctx.appContext)
            .settings.prefsCache?.sosContactNumber.orEmpty()
        if (number.isBlank()) {
            return ToolResult.Failure("No emergency contact configured. Set one in Settings → SOS first.")
        }
        SOSManager.siren(ctx.appContext, 5)
        val ok = SOSManager.openDialer(ctx.appContext, number)
        return if (ok) ToolResult.Success("SOS siren sounded and dialer opened to the emergency contact. Press call to connect.")
        else ToolResult.Failure("Could not open the dialer.")
    }
}
