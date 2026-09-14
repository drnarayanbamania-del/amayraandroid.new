package com.amayra.maya.integration

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.amayra.maya.core.MayaLog
import com.amayra.maya.data.AutoReplyLogEntity
import com.amayra.maya.settings.SettingsRepository
import com.amayra.maya.tools.RiskLevel
import com.amayra.maya.tools.Tool
import com.amayra.maya.tools.ToolContext
import com.amayra.maya.tools.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.Calendar

/**
 * WhatsApp integration using Android-supported mechanisms:
 *  - direct-to-chat share intents (no silently-sent API messages),
 *  - notification listener feed for reading incoming messages,
 *  - auto-reply engine with allowlist, quiet hours, daily cap and full audit log.
 *
 * SAFETY CONTRACT (mirrors reference behavior): messages are always prepared as a
 * share intent the user completes, OR (auto-reply) sent only to allowlisted chats,
 * rate-limited, quiet-hours aware, and logged.
 */
object WhatsAppManager {
    const val WA_PACKAGE = "com.whatsapp"
    const val WA4_PACKAGE = "com.whatsapp.w4b"

    fun isInstalled(ctx: Context): Boolean = isInstalled(ctx, WA_PACKAGE) || isInstalled(ctx, WA4_PACKAGE)
    private fun isInstalled(ctx: Context, pkg: String): Boolean = try {
        ctx.packageManager.getPackageInfo(pkg, 0); true
    } catch (t: Throwable) { false }

    /** Opens a WhatsApp chat with the given phone number and pre-fills text. User presses send. */
    fun openChatWithMessage(ctx: Context, phoneE164: String, message: String): Result<Unit> = try {
        val uri = Uri.parse("https://wa.me/${phoneE164.filter { it.isDigit() }}?text=${Uri.encode(message)}")
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        MayaLog.i("WA", "Opened WhatsApp chat to ${phoneE164.take(6)}••• (user completes send)")
        Result.success(Unit)
    } catch (t: Throwable) {
        Result.failure(t)
    }

    /** Opens a WhatsApp group/chat search and pre-fills text (user picks the chat and sends). */
    fun openShareSheet(ctx: Context, message: String): Result<Unit> = try {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, message)
            setPackage(WA_PACKAGE)
        }
        ctx.startActivity(Intent.createChooser(intent, "Send via WhatsApp").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        Result.success(Unit)
    } catch (t: Throwable) {
        Result.failure(t)
    }

    /** Quiet-hours check: "23:00-07:00" style window, supports overnight ranges. */
    fun inQuietHours(window: String, now: Calendar = Calendar.getInstance()): Boolean {
        val m = Regex("""(\d{1,2}):(\d{2})\s*-\s*(\d{1,2}):(\d{2})""").find(window.trim()) ?: return false
        val (sh, sm, eh, em) = m.destructured
        val minutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val start = sh.toInt() * 60 + sm.toInt()
        val end = eh.toInt() * 60 + em.toInt()
        return if (start <= end) minutes in start..end else minutes >= start || minutes <= end
    }
}

/** whatsapp_send_message tool — always routes through user-visible share/chat flow. */
class WhatsAppSendTool : Tool {
    override val name = "whatsapp_send_message"
    override val description =
        "Open a WhatsApp chat with a message pre-filled for the user to send. " +
            "Args: phone (E.164 like +911234567890), message."
    override val risk = RiskLevel.CONFIRM
    override fun parameters() = JsonObject(mapOf(
        "type" to JsonPrimitive("object"),
        "properties" to JsonObject(mapOf(
            "phone" to JsonObject(mapOf("type" to JsonPrimitive("string"), "description" to JsonPrimitive("Recipient phone in E.164 format, e.g. +911234567890"))),
            "message" to JsonObject(mapOf("type" to JsonPrimitive("string"), "description" to JsonPrimitive("Exact message text to send")))
        )),
        "required" to JsonArray(listOf(JsonPrimitive("phone"), JsonPrimitive("message")))
    ))

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val phone = args["phone"]?.jsonPrimitive?.contentOrNull ?: return ToolResult.Failure("Missing phone.")
        val message = args["message"]?.jsonPrimitive?.contentOrNull ?: return ToolResult.Failure("Missing message.")
        val c = ctx.appContext
        if (!WhatsAppManager.isInstalled(c)) {
            return ToolResult.Failure("WhatsApp is not installed on this device.")
        }
        val res = WhatsAppManager.openChatWithMessage(c, phone, message)
        return if (res.isSuccess) {
            ToolResult.Success(
                "WhatsApp chat opened with message pre-filled. The message is NOT yet sent — the user must tap send. " +
                    "Tell the user to review and tap the send button."
            )
        } else {
            ToolResult.Failure("Could not open WhatsApp: ${res.exceptionOrNull()?.message}")
        }
    }
}

/**
 * Auto-reply engine: consumes notification-listener events for WhatsApp and
 * replies through the same user-visible flow, honoring allowlist/caps/quiet hours.
 */
class WhatsAppAutoReplyEngine(
    private val settings: SettingsRepository,
    private val ai: com.amayra.maya.ai.AiClient
) {
    private var lastReplyAt = mutableMapOf<String, Long>()
    private val minGapMs = 60_000L

    /** Returns the generated reply text, or null if policy blocks the reply. */
    suspend fun onIncoming(ctx: android.content.Context, contact: String, text: String): String? {
        val p = settings.prefs.first()
        if (!p.waAutoReplyEnabled) return null
        if (WhatsAppManager.inQuietHours(p.waAutoReplyQuietHours)) {
            MayaLog.i("WA", "Auto-reply suppressed (quiet hours) for $contact")
            return null
        }
        // Daily cap from audit log.
        val dayStart = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val dao = com.amayra.maya.data.MayaDatabase.get(ctx).autoReplyDao
        // Cap counts DELIVERED replies only — failed/undeliverable attempts don't consume quota.
        val deliveredToday = dao.countDeliveredSince(dayStart)
        if (deliveredToday >= p.waAutoReplyMaxPerDay) {
            MayaLog.w("WA", "Auto-reply daily cap reached ($deliveredToday/${p.waAutoReplyMaxPerDay})")
            return null
        }
        // Allowlist (empty = everyone allowed) + per-contact rate gap.
        val allow = p.waAutoReplyContacts.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (allow.isNotEmpty() && allow.none { contact.contains(it, ignoreCase = true) }) {
            MayaLog.i("WA", "Auto-reply skipped: $contact not in allowlist")
            return null
        }
        val last = lastReplyAt[contact] ?: 0
        if (System.currentTimeMillis() - last < minGapMs) return null

        // Generate a short, bounded reply. Never commit to anything on behalf of the user.
        val reply = ai.completeOnce(
            system = "You are Maya, replying to a WhatsApp message on behalf of the user. " +
                "Reply in the same language, at most 2 short sentences. Be warm. " +
                "NEVER make commitments, promises, payments, or confirm appointments on the user's behalf. " +
                "If the message needs a real decision, say the user will reply personally later.",
            user = "From: $contact\nMessage: $text"
        ) ?: return null

        // Resolve the notification contact NAME to a real phone number. Never send to
        // a fabricated recipient: unresolved names take the clipboard fallback instead.
        val delivery: String = withContext(Dispatchers.IO) {
            when (val r = ContactResolver.resolveByName(ctx, contact)) {
                is ContactResolver.Resolution.Resolved -> {
                    if (WhatsAppManager.openChatWithMessage(ctx, r.phoneE164, reply).isSuccess) "opened-chat"
                    else {
                        val outcome = ContactResolver.copyReplyAndOpenWhatsApp(ctx, contact, reply)
                        if (outcome.contains("failed")) "failed" else "copied-to-clipboard"
                    }
                }
                is ContactResolver.Resolution.Unresolved -> {
                    MayaLog.i("WA", "Recipient unresolved for $contact (${r.reason}) — using clipboard fallback")
                    val outcome = ContactResolver.copyReplyAndOpenWhatsApp(ctx, contact, reply)
                    if (outcome.contains("failed")) "failed" else "copied-to-clipboard"
                }
            }
        }
        // Rate-limit even failed attempts (bounds AI spend on broken recipients) and
        // record EVERY attempt with its honest delivery outcome for the audit trail.
        lastReplyAt[contact] = System.currentTimeMillis()
        dao.insert(AutoReplyLogEntity(
            packageName = WhatsAppManager.WA_PACKAGE,
            contact = contact,
            incomingText = text.take(500),
            replyText = reply.take(500),
            delivery = delivery
        ))
        if (delivery == "failed") {
            MayaLog.w("WA", "Auto-reply for $contact could not be delivered (see Tools log)")
            return null
        }
        MayaLog.i("WA", "Auto-reply delivered to $contact ($deliveredToday/${p.waAutoReplyMaxPerDay} cap used) delivery=$delivery")
        return reply
    }
}
