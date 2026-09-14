package com.amayra.maya.integration

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.amayra.maya.core.MayaLog

/**
 * Resolves a WhatsApp notification contact NAME ("John Doe") to a dialable
 * phone number via ContactsContract, with honest degraded behavior when
 * resolution is impossible (no permission, no match, or numberless contact).
 *
 * Never returns a fabricated number: the result is either a real E.164-ish
 * number from the user's contacts, or Unresolved.
 */
object ContactResolver {

    sealed class Resolution {
        /** Resolved phone number as stored by the user (digits/+ kept). */
        data class Resolved(val phoneE164: String, val displayName: String) : Resolution()
        data class Unresolved(val reason: String) : Resolution()
    }

    fun hasReadContactsPermission(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Looks up [displayName] in the contacts database (case-insensitive exact match
     * first, then a "starts with" fallback). Reads the primary mobile phone number.
     * Pure data lookup — safe on any thread; caller chooses dispatcher.
     */
    fun resolveByName(ctx: Context, displayName: String): Resolution {
        if (displayName.isBlank()) return Resolution.Unresolved("contact name is blank")
        if (!hasReadContactsPermission(ctx)) {
            return Resolution.Unresolved("READ_CONTACTS permission not granted")
        }
        val cr = ctx.contentResolver
        // 1) exact (case-insensitive) display-name match
        var cursor: Cursor? = null
        try {
            cursor = cr.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY),
                "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} = ? COLLATE NOCASE",
                arrayOf(displayName.trim()),
                null
            )
            val id = cursor?.use { c ->
                if (c.moveToFirst()) c.getLong(0) else null
            }
            if (id == null) {
                // 2) relaxed prefix match
                cursor?.close()
                cursor = cr.query(
                    ContactsContract.Contacts.CONTENT_URI,
                    arrayOf(ContactsContract.Contacts._ID),
                    "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} LIKE ? COLLATE NOCASE",
                    arrayOf("${displayName.trim()}%"),
                    null
                )
                val relaxed = cursor?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }
                    ?: return Resolution.Unresolved("no contact named \"$displayName\"")
                return resolvePhoneForContact(ctx, relaxed, displayName)
            }
            return resolvePhoneForContact(ctx, id, displayName)
        } catch (t: Throwable) {
            MayaLog.w("WA", "Contact lookup failed: ${t.message}")
            return Resolution.Unresolved("contact lookup error: ${t.message}")
        } finally {
            runCatching { cursor?.close() }
        }
    }

    /** Reads the preferred phone number for a contact id (mobile first, then any). */
    private fun resolvePhoneForContact(ctx: Context, contactId: Long, displayName: String): Resolution {
        return try {
            val phoneCursor = ctx.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.TYPE
                ),
                "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                arrayOf(contactId.toString()),
                null
            )
            var anyNumber: String? = null
            var mobile: String? = null
            phoneCursor?.use { c ->
                while (c.moveToNext()) {
                    val num = c.getString(0)?.trim().orEmpty()
                    if (num.isEmpty()) continue
                    val type = c.getInt(1)
                    if (type == ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE && mobile == null) {
                        mobile = num
                    } else if (anyNumber == null) {
                        anyNumber = num
                    }
                }
            }
            val picked = mobile ?: anyNumber
            if (picked.isNullOrBlank()) {
                Resolution.Unresolved("contact \"$displayName\" has no phone number")
            } else {
                Resolution.Resolved(picked, displayName)
            }
        } catch (t: Throwable) {
            MayaLog.w("WA", "Phone lookup failed: ${t.message}")
            Resolution.Unresolved("phone lookup error: ${t.message}")
        }
    }

    /**
     * The honest degraded path: copy the reply to the clipboard and open WhatsApp's
     * chat list so the user can paste it. Returns a human-readable outcome for the
     * audit log. Never fabricates a recipient.
     */
    fun copyReplyAndOpenWhatsApp(ctx: Context, contact: String, reply: String): String {
        return try {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Maya reply for $contact", reply))
            // Open the WhatsApp chat list (no bogus target — the user picks the chat).
            val intent = ctx.packageManager.getLaunchIntentForPackage(WhatsAppManager.WA_PACKAGE)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(intent)
                "Contact \"$contact\" not resolvable to a number — reply copied to clipboard and WhatsApp opened. Paste and send manually."
            } else {
                "Contact \"$contact\" not resolvable to a number — reply copied to clipboard (WhatsApp not openable)."
            }
        } catch (t: Throwable) {
            MayaLog.w("WA", "Clipboard fallback failed: ${t.message}")
            "Contact \"$contact\" not resolvable to a number — clipboard fallback failed (${t.message})."
        }
    }
}
