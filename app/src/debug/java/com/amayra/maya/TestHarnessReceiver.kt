package com.amayra.maya

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.amayra.maya.core.MayaLog
import com.amayra.maya.core.StateBus
import com.amayra.maya.memory.SecureStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * DEBUG-ONLY test harness (src/debug — never packaged in release builds).
 * Drives Maya's REAL APIs (SettingsRepository, SecureStore, MayaCognitiveCore)
 * via adb broadcasts, because Gboard autocorrect mangles `adb shell input text`
 * on this emulator image. Actions:
 *
 *   am broadcast -a com.amayra.maya.SET_PROVIDER --es provider openai_compat \
 *       --es model maya-test-model --es base_url http://localhost:18789/v1 \
 *       --es api_key test-key-123
 *   am broadcast -a com.amayra.maya.SEND_CHAT --es text "what time is it"
 *   am broadcast -a com.amayra.maya.GET_STATE
 */
class TestHarnessReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as MayaApplication
        when (intent.action) {
            "com.amayra.maya.SET_PROVIDER" -> {
                val provider = intent.getStringExtra("provider")
                val model = intent.getStringExtra("model")
                val baseUrl = intent.getStringExtra("base_url")
                val apiKey = intent.getStringExtra("api_key")
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        app.settings.update { p ->
                            provider?.let { p[stringPreferencesKey("provider")] = it }
                            model?.let { p[stringPreferencesKey("model")] = it }
                            baseUrl?.let { p[stringPreferencesKey("base_url")] = it }
                        }
                        apiKey?.let { app.secure.putString(SecureStore.KEY_OPENAI_COMPAT, it) }
                        MayaLog.i("HARNESS", "SET_PROVIDER done: provider=$provider model=$model baseUrl=$baseUrl keySet=${apiKey != null}")
                    } catch (t: Throwable) {
                        MayaLog.w("HARNESS", "SET_PROVIDER failed: ${t.message}")
                    }
                }
            }
            "com.amayra.maya.SEND_CHAT" -> {
                val text = intent.getStringExtra("text") ?: return
                CoroutineScope(Dispatchers.Default).launch {
                    try {
                        app.core.onUserMessage(text)
                        MayaLog.i("HARNESS", "SEND_CHAT completed: \"$text\"")
                    } catch (t: Throwable) {
                        MayaLog.w("HARNESS", "SEND_CHAT failed: ${t.javaClass.simpleName}: ${t.message}")
                    }
                }
            }
            "com.amayra.maya.GET_STATE" -> {
                CoroutineScope(Dispatchers.IO).launch {
                    val turns = app.core.turnsState.value
                    val last = turns.lastOrNull()
                    MayaLog.i(
                        "HARNESS",
                        "state=${StateBus.state.value} emotion=${StateBus.emotion.value} " +
                            "turns=${turns.size} lastRole=${last?.role} " +
                            "lastContent=${last?.content?.take(200)}"
                    )
                }
            }
            "com.amayra.maya.SET_PREFS" -> {
                // --es bool_prefs "wa_auto_reply_enabled=true" --es str_prefs "sos_number=+91123"
                val bools = intent.getStringExtra("bool_prefs").orEmpty()
                val strs = intent.getStringExtra("str_prefs").orEmpty()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        app.settings.update { p ->
                            bools.split(",").map { it.trim() }.filter { "=" in it }.forEach { kv ->
                                val (k, v) = kv.split("=", limit = 2)
                                p[androidx.datastore.preferences.core.booleanPreferencesKey(k)] = v == "true"
                            }
                            strs.split(",").map { it.trim() }.filter { "=" in it }.forEach { kv ->
                                val (k, v) = kv.split("=", limit = 2)
                                p[androidx.datastore.preferences.core.stringPreferencesKey(k)] = v
                            }
                        }
                        MayaLog.i("HARNESS", "SET_PREFS done bools=[$bools] strs=[$strs]")
                    } catch (t: Throwable) {
                        MayaLog.w("HARNESS", "SET_PREFS failed: ${t.message}")
                    }
                }
            }
            "com.amayra.maya.WA_SIMULATE" -> {
                // Exercises the real auto-reply engine: gates -> AI -> recipient
                // resolution -> delivery (chat open or clipboard fallback) -> audit.
                val contact = intent.getStringExtra("contact") ?: "Test Contact"
                val text = intent.getStringExtra("text") ?: "hello"
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val engine = app.core.autoReplyEngine
                        val reply = engine.onIncoming(app, contact, text)
                        MayaLog.i("HARNESS", "WA_SIMULATE result=${reply?.take(120) ?: "null (blocked or failed)"}")
                    } catch (t: Throwable) {
                        MayaLog.w("HARNESS", "WA_SIMULATE failed: ${t.javaClass.simpleName}: ${t.message}")
                    }
                }
            }
            "com.amayra.maya.SEED_CONTACT" -> {
                // Seeds a test contact (Priya Sharma, mobile +919876543210) so the
                // ContactsContract resolution path can be verified end-to-end.
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val ops = arrayListOf<android.content.ContentProviderOperation>(
                            android.content.ContentProviderOperation
                                .newInsert(android.provider.ContactsContract.RawContacts.CONTENT_URI)
                                .withValue(android.provider.ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                                .withValue(android.provider.ContactsContract.RawContacts.ACCOUNT_NAME, null)
                                .build(),
                            android.content.ContentProviderOperation
                                .newInsert(android.provider.ContactsContract.Data.CONTENT_URI)
                                .withValueBackReference(android.provider.ContactsContract.Data.RAW_CONTACT_ID, 0)
                                .withValue(
                                    android.provider.ContactsContract.Data.MIMETYPE,
                                    android.provider.ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE
                                )
                                .withValue(android.provider.ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, "Priya Sharma")
                                .build(),
                            android.content.ContentProviderOperation
                                .newInsert(android.provider.ContactsContract.Data.CONTENT_URI)
                                .withValueBackReference(android.provider.ContactsContract.Data.RAW_CONTACT_ID, 0)
                                .withValue(
                                    android.provider.ContactsContract.Data.MIMETYPE,
                                    android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE
                                )
                                .withValue(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER, "+919876543210")
                                .withValue(
                                    android.provider.ContactsContract.CommonDataKinds.Phone.TYPE,
                                    android.provider.ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE
                                )
                                .build()
                        )
                        val results = app.contentResolver.applyBatch(android.provider.ContactsContract.AUTHORITY, ops)
                        MayaLog.i("HARNESS", "SEED_CONTACT inserted ${results.size} rows")
                    } catch (t: Throwable) {
                        MayaLog.w("HARNESS", "SEED_CONTACT failed: ${t.javaClass.simpleName}: ${t.message}")
                    }
                }
            }
            "com.amayra.maya.RESOLVE" -> {
                val name = intent.getStringExtra("name") ?: return
                CoroutineScope(Dispatchers.IO).launch {
                    val r = com.amayra.maya.integration.ContactResolver.resolveByName(app, name)
                    when (r) {
                        is com.amayra.maya.integration.ContactResolver.Resolution.Resolved ->
                            MayaLog.i("HARNESS", "RESOLVE '$name' -> Resolved(${r.phoneE164})")
                        is com.amayra.maya.integration.ContactResolver.Resolution.Unresolved ->
                            MayaLog.i("HARNESS", "RESOLVE '$name' -> Unresolved(${r.reason})")
                    }
                }
            }
            "com.amayra.maya.ANSWER_CONFIRM" -> {
                val approve = intent.getStringExtra("approve") == "true"
                CoroutineScope(Dispatchers.IO).launch {
                    val pending = app.core.pendingConfirm.value
                    if (pending == null) {
                        MayaLog.i("HARNESS", "ANSWER_CONFIRM: no pending confirmation")
                    } else {
                        MayaLog.i("HARNESS", "ANSWER_CONFIRM: answering=$approve to '${pending.title}'")
                        pending.answer(approve)
                    }
                }
            }
            "com.amayra.maya.TOOL_EXEC" -> {
                val name = intent.getStringExtra("name") ?: return
                val args = intent.getStringExtra("args") ?: "{}"
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val result = app.registry.execute(name, args, "harness")
                        val summary = when (result) {
                            is com.amayra.maya.tools.ToolResult.Success -> "OK: " + result.summary
                            is com.amayra.maya.tools.ToolResult.Failure -> "FAIL: " + result.error
                            is com.amayra.maya.tools.ToolResult.ConfirmRequired -> "CONFIRM: " + result.reason
                        }
                        MayaLog.i("HARNESS", "TOOL_EXEC $name -> ${summary.take(250)}")
                    } catch (t: Throwable) {
                        MayaLog.w("HARNESS", "TOOL_EXEC $name threw: ${t.javaClass.simpleName}: ${t.message}")
                    }
                }
            }
        }
    }
}
