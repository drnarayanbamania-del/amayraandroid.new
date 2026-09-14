package com.amayra.scanner.ocr

import android.content.Context
import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Script options for bundled on-device recognizers. */
enum class OcrScript(val code: String) {
    LATIN("latin"), DEVANAGARI("devanagari"), CHINESE("chinese"),
    JAPANESE("japanese"), KOREAN("korean");

    companion object {
        fun fromCode(code: String?): OcrScript? = entries.firstOrNull { it.code == code }
    }
}

data class OcrResult(
    val text: String,
    val scriptUsed: OcrScript,
    val wordCount: Int
)

/**
 * On-device OCR via ML Kit. In "auto" mode runs the Latin recognizer first and
 * falls back to other bundled recognizers when little text is found, picking
 * the richest result. All processing is local — works offline.
 */
class OcrEngine(context: Context) {
    private val appContext = context.applicationContext

    suspend fun recognize(bitmap: Bitmap, scriptCode: String): OcrResult {
        val requested = OcrScript.fromCode(scriptCode)
        val order = when (requested) {
            null -> listOf(OcrScript.LATIN, OcrScript.DEVANAGARI, OcrScript.CHINESE, OcrScript.JAPANESE, OcrScript.KOREAN)
            else -> listOf(requested)
        }

        val image = InputImage.fromBitmap(bitmap, 0)
        var best: OcrResult? = null
        for (script in order) {
            val r = runRecognizer(image, script)
            if (r != null && r.wordCount > 0) {
                // Auto mode: accept the first recognizer that yields real text;
                // Latin/Devanagari rarely produce false positives on other scripts,
                // but keep the longest result across fallbacks for safety.
                if (best == null || r.text.length > best.text.length) best = r
                if (requested != null) return r
                // Heuristic: stop early when Latin finds a healthy page.
                if (script == OcrScript.LATIN && r.wordCount >= 3) return r
            } else if (best == null && r != null) {
                best = r
            }
        }
        return best ?: OcrResult("", order.first(), 0)
    }

    private suspend fun runRecognizer(image: InputImage, script: OcrScript): OcrResult? {
        val client: TextRecognizer = when (script) {
            OcrScript.LATIN -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            OcrScript.DEVANAGARI -> TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
            OcrScript.CHINESE -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            OcrScript.JAPANESE -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
            OcrScript.KOREAN -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        }
        return try {
            val text: Text = client.await(image)
            val joined = text.textBlocks.joinToString("\n\n") { block ->
                block.lines.joinToString("\n") { it.text }
            }.ifBlank { text.text }
            OcrResult(joined, script, ScannerWordCounter.count(joined))
        } catch (_: Exception) {
            null
        } finally {
            runCatching { client.close() }
        }
    }
}

private suspend fun TextRecognizer.await(image: InputImage): Text =
    suspendCancellableCoroutine { cont ->
        process(image)
            .addOnSuccessListener { if (cont.isActive) cont.resume(it) }
            .addOnFailureListener { if (cont.isActive) cont.resumeWithException(it) }
    }

object ScannerWordCounter {
    fun count(text: String): Int = text.trim().split(Regex("\\s+")).count { it.isNotBlank() }
}
