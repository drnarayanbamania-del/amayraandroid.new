package com.amayra.maya.memory

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Android Keystore-backed AES-256/GCM secret storage. API keys and tokens are
 * stored encrypted here, never in DataStore, never in backups, never in logs.
 *
 * Failure modes handled explicitly (both previously caused silent key loss):
 *  - Keystore transiently unavailable while SAVING → entry is written plaintext
 *    with a [PLAINTEXT_PREFIX] marker and re-encrypted transparently on the next
 *    successful read/write (fallback value was previously unreadable → key "vanished").
 *  - Keystore key invalidated while old ciphertext exists → the unreadable blob
 *    is dropped and null returned (previously the corrupt blob stayed forever and
 *    every future read failed). Callers re-save; the app then heals itself.
 */
class SecureStore(context: Context) {
    private val prefs = context.getSharedPreferences("maya_secure", Context.MODE_PRIVATE)
    private val alias = "maya_master_key"

    private fun obtainKey(): SecretKey? = try {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey) ?: run {
            val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            gen.init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            gen.generateKey()
        }
    } catch (t: Throwable) {
        Log.w(TAG, "Keystore unavailable, falling back to private prefs", t)
        null
    }

    fun putString(key: String, value: String) {
        if (value.isEmpty()) { prefs.edit().remove(key).apply(); return }
        val key2 = obtainKey()
        if (key2 == null) {
            // Keystore transiently unavailable: store with an explicit marker so a
            // later read knows this is plaintext, not a broken ciphertext.
            prefs.edit().putString(key, PLAINTEXT_PREFIX + value).apply(); return
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key2)
        val iv = cipher.iv
        val ct = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(key, Base64.encodeToString(iv, Base64.NO_WRAP) + "|" + Base64.encodeToString(ct, Base64.NO_WRAP)).apply()
    }

    fun getString(key: String): String? {
        val raw = prefs.getString(key, null) ?: return null
        // Plaintext fallback entry: if the Keystore is still unavailable, return it
        // as-is; once available, transparently upgrade it to encrypted storage.
        if (raw.startsWith(PLAINTEXT_PREFIX)) {
            val plain = raw.removePrefix(PLAINTEXT_PREFIX)
            if (obtainKey() != null) putString(key, plain) // best-effort upgrade
            return plain
        }
        if (!raw.contains('|')) {
            // Legacy plaintext with no marker (pre-fix writes). Read it and upgrade.
            obtainKey()?.let { putString(key, raw) }
            return raw
        }
        val key2 = obtainKey() ?: return null
        return try {
            val (ivB64, ctB64) = raw.split('|').let { it[0] to it[1] }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key2, GCMParameterSpec(128, Base64.decode(ivB64, Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(ctB64, Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (t: Throwable) {
            // Unreadable ciphertext (Keystore key was reset). Drop the dead blob so
            // the entry is cleanly "not set" — the UI shows "add key" and the next
            // save heals everything, instead of failing forever on the same blob.
            Log.w(TAG, "Dropping undecryptable entry $key (Keystore key likely reset)", t)
            prefs.edit().remove(key).apply()
            null
        }
    }

    fun remove(key: String) = prefs.edit().remove(key).apply()

    companion object {
        private const val TAG = "SecureStore"
        private const val PLAINTEXT_PREFIX = "plain::"
        const val KEY_GEMINI = "api_key_gemini"
        const val KEY_OPENAI_COMPAT = "api_key_openai_compat"
    }
}
