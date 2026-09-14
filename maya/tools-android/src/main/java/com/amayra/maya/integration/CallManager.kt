package com.amayra.maya.integration

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telecom.TelecomManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.amayra.maya.core.MayaLog
import com.amayra.maya.tools.RiskLevel
import com.amayra.maya.tools.Tool
import com.amayra.maya.tools.ToolContext
import com.amayra.maya.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * Call handling: answer/reject ringing calls (needs ANSWER_PHONE_CALLS, granted
 * to dialer-class apps; on many OEMs only the default dialer may answer —
 * failures are honest, never faked), plus caller announcement.
 */
object CallManager {

    fun hasPhoneState(ctx: Context) = ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
    fun hasAnswerCalls(ctx: Context) = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED

    fun answer(ctx: Context): Pair<Boolean, String> {
        val tm = ctx.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
            ?: return false to "Telecom service unavailable."
        return try {
            if (Build.VERSION.SDK_INT >= 26) {
                tm.acceptRingingCall()
                true to "Call answered."
            } else false to "Answering calls needs Android 8+."
        } catch (t: SecurityException) {
            MayaLog.w("CALL", "answer refused: ${t.message}")
            false to "Android refused: answering requires Maya to be the default dialer (or the ANSWER_PHONE_CALLS grant)."
        } catch (t: Throwable) {
            false to "Answer failed: ${t.message}"
        }
    }

    fun reject(ctx: Context): Pair<Boolean, String> {
        val tm = ctx.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
            ?: return false to "Telecom service unavailable."
        return try {
            if (Build.VERSION.SDK_INT >= 28) {
                val ok = tm.endCall()
                if (ok) true to "Call rejected." else false to "No active ringing call."
            } else false to "Rejecting calls needs Android 9+."
        } catch (t: SecurityException) {
            false to "Android refused: rejecting requires the ANSWER_PHONE_CALLS grant / default dialer."
        } catch (t: Throwable) {
            false to "Reject failed: ${t.message}"
        }
    }
}

class AnswerCallTool : Tool {
    override val name = "answer_call"
    override val description = "Answer an incoming phone call. Only works when Android permits it (default dialer or ANSWER_PHONE_CALLS)."
    override val risk = RiskLevel.CONFIRM
    override fun parameters() = JsonObject(emptyMap())
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        if (!CallManager.hasAnswerCalls(ctx.appContext)) {
            return ToolResult.Failure("ANSWER_PHONE_CALLS permission not granted. Grant it in Settings → Apps → Maya → Permissions.")
        }
        val (ok, msg) = CallManager.answer(ctx.appContext)
        return if (ok) ToolResult.Success(msg) else ToolResult.Failure(msg)
    }
}

class RejectCallTool : Tool {
    override val name = "reject_call"
    override val description = "Reject the incoming/ringing phone call."
    override val risk = RiskLevel.CONFIRM
    override fun parameters() = JsonObject(emptyMap())
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        if (!CallManager.hasAnswerCalls(ctx.appContext)) {
            return ToolResult.Failure("ANSWER_PHONE_CALLS permission not granted.")
        }
        val (ok, msg) = CallManager.reject(ctx.appContext)
        return if (ok) ToolResult.Success(msg) else ToolResult.Failure(msg)
    }
}

/** Send an SMS via SmsManager (user confirmed by the risk gate). */
class SendSmsTool : Tool {
    override val name = "send_sms"
    override val description = "Send an SMS text message. Args: {\"number\": \"+91…\", \"text\": \"…\"}. Always confirmed with the user first."
    override val risk = RiskLevel.CONFIRM
    override fun parameters() = JsonObject(mapOf(
        "type" to JsonPrimitive("object"),
        "properties" to JsonObject(mapOf(
            "number" to JsonObject(mapOf("type" to JsonPrimitive("string"), "description" to JsonPrimitive("Destination phone number"))),
            "text" to JsonObject(mapOf("type" to JsonPrimitive("string"), "description" to JsonPrimitive("Message body")))
        )),
        "required" to JsonArray(listOf(JsonPrimitive("number"), JsonPrimitive("text")))
    ))
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val number = (args["number"] as? JsonPrimitive)?.contentOrNull
            ?: return ToolResult.Failure("Missing 'number'.")
        val text = (args["text"] as? JsonPrimitive)?.contentOrNull
            ?: return ToolResult.Failure("Missing 'text'.")
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            ctx.appContext, android.Manifest.permission.SEND_SMS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) return ToolResult.Failure("SEND_SMS permission not granted.")
        return try {
            val sm = if (Build.VERSION.SDK_INT >= 31) {
                ctx.appContext.getSystemService(android.telephony.SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION") android.telephony.SmsManager.getDefault()
            } ?: return ToolResult.Failure("SmsManager unavailable.")
            sm.sendTextMessage(number, null, text, null, null)
            ToolResult.Success("SMS sent to $number.")
        } catch (t: Throwable) {
            ToolResult.Failure("SMS failed: ${t.message}")
        }
    }
}
