package com.amayra.scanner.translate

import android.content.Context
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A language supported by the on-device translator. */
data class TranslateLanguageOption(val code: String, val label: String)

/**
 * ML Kit on-device translation. Downloads ~30MB language models on first use
 * per language pair (requires connectivity once), then translates fully
 * offline.
 */
class TranslationEngine(private val context: Context) {

    val languages: List<TranslateLanguageOption> = listOf(
        TranslateLanguageOption("en", "English"),
        TranslateLanguageOption("hi", "Hindi"),
        TranslateLanguageOption("fr", "French"),
        TranslateLanguageOption("de", "German"),
        TranslateLanguageOption("es", "Spanish"),
        TranslateLanguageOption("it", "Italian"),
        TranslateLanguageOption("zh", "Chinese"),
        TranslateLanguageOption("ja", "Japanese")
    )

    fun labelFor(code: String): String = languages.firstOrNull { it.code == code }?.label ?: code

    sealed class Outcome {
        data class Success(val translated: String) : Outcome()
        data class Downloading(val message: String) : Outcome()
        data class Failure(val friendly: String) : Outcome()
    }

    suspend fun translate(text: String, sourceCode: String, targetCode: String): Outcome {
        if (text.isBlank()) return Outcome.Failure("Nothing to translate.")
        if (sourceCode == targetCode) return Outcome.Success(text)

        val source = TranslateLanguage.fromLanguageTag(sourceCode) ?: return Outcome.Failure("Unsupported source language.")
        val target = TranslateLanguage.fromLanguageTag(targetCode) ?: return Outcome.Failure("Unsupported target language.")

        val options = TranslatorOptions.Builder()
            .setSourceLanguage(source)
            .setTargetLanguage(target)
            .build()
        val translator: Translator = com.google.mlkit.nl.translate.Translation.getClient(options)

        try {
            // Ensure both models exist (downloads once per language, ~30MB).
            val conditions = DownloadConditions.Builder().build()
            val downloaded = withTimeoutOrNull(120_000) {
                awaitModel(translator, conditions)
            }
            if (downloaded != true) {
                return Outcome.Failure(
                    "Couldn't download the language model. Connect to the internet once and try again."
                )
            }
            val result = withTimeoutOrNull(60_000) { awaitTranslate(translator, text) }
                ?: return Outcome.Failure("Translation took too long. Try shorter text.")
            return Outcome.Success(result)
        } catch (e: Exception) {
            return Outcome.Failure("Translation failed. Check your connection and try again.")
        } finally {
            runCatching { translator.close() }
        }
    }

    private suspend fun awaitModel(translator: Translator, conditions: DownloadConditions): Boolean =
        suspendCancellableCoroutine { cont ->
            translator.downloadModelIfNeeded(conditions)
                .addOnSuccessListener { if (cont.isActive) cont.resume(true) }
                .addOnFailureListener { if (cont.isActive) cont.resumeWithException(it) }
        }

    private suspend fun awaitTranslate(translator: Translator, text: String): String =
        suspendCancellableCoroutine { cont ->
            translator.translate(text)
                .addOnSuccessListener { if (cont.isActive) cont.resume(it) }
                .addOnFailureListener { if (cont.isActive) cont.resumeWithException(it) }
        }
}
