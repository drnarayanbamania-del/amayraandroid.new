package com.amayra.scanner.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "scanner_settings")

/** Languages supported by the on-device translation engine. */
val TRANSLATE_LANGUAGES: List<Pair<String, String>> = listOf(
    "en" to "English", "hi" to "Hindi", "fr" to "French", "de" to "German",
    "es" to "Spanish", "it" to "Italian", "zh" to "Chinese", "ja" to "Japanese"
)

data class ScannerPrefs(
    val ocrScript: String = "auto",   // auto | latin | devanagari | chinese | japanese | korean
    val targetLanguage: String = "en",
    val themeMode: String = "system", // system | light | dark
    val autoEnhance: Boolean = true,
    val autoSpeak: Boolean = false    // read extracted text aloud after OCR
)

class SettingsStore(private val context: Context) {
    private object Keys {
        val OCR_SCRIPT = stringPreferencesKey("ocr_script")
        val TARGET_LANG = stringPreferencesKey("target_lang")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val AUTO_ENHANCE = booleanPreferencesKey("auto_enhance")
        val AUTO_SPEAK = booleanPreferencesKey("auto_speak")
    }

    val prefs: Flow<ScannerPrefs> = context.dataStore.data.map { p ->
        ScannerPrefs(
            ocrScript = p[Keys.OCR_SCRIPT] ?: "auto",
            targetLanguage = p[Keys.TARGET_LANG] ?: "en",
            themeMode = p[Keys.THEME_MODE] ?: "system",
            autoEnhance = p[Keys.AUTO_ENHANCE] ?: true,
            autoSpeak = p[Keys.AUTO_SPEAK] ?: false
        )
    }

    suspend fun setOcrScript(v: String) = context.dataStore.edit { it[Keys.OCR_SCRIPT] = v }
    suspend fun setTargetLanguage(v: String) = context.dataStore.edit { it[Keys.TARGET_LANG] = v }
    suspend fun setThemeMode(v: String) = context.dataStore.edit { it[Keys.THEME_MODE] = v }
    suspend fun setAutoEnhance(v: Boolean) = context.dataStore.edit { it[Keys.AUTO_ENHANCE] = v }
    suspend fun setAutoSpeak(v: Boolean) = context.dataStore.edit { it[Keys.AUTO_SPEAK] = v }
}
