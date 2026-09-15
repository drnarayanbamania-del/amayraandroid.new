package com.amayra.scanner

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.amayra.scanner.data.Exporter
import com.amayra.scanner.data.ScannerPrefs
import com.amayra.scanner.data.ScannerStore
import com.amayra.scanner.data.SettingsStore
import com.amayra.scanner.ocr.ImageEnhancer
import com.amayra.scanner.ocr.ImageQuality
import com.amayra.scanner.ocr.OcrEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** One recognized page's result inside a batch. */
data class PageResult(
    val index: Int,
    val name: String,
    val text: String,
    val thumbnailPath: String? = null
)

/** Overall OCR job state. */
sealed class ScanJob {
    data object Idle : ScanJob()
    data class Running(val done: Int, val total: Int, val label: String) : ScanJob()
    data class Done(val pages: List<PageResult>) : ScanJob()
    data class Error(val friendly: String) : ScanJob()
}

class ScannerViewModel(app: Application) : AndroidViewModel(app) {

    private val store = ScannerStore.get(app)
    val settings = SettingsStore(app)

    private val ocr = OcrEngine(app)

    private val _job = MutableStateFlow<ScanJob>(ScanJob.Idle)
    val job: StateFlow<ScanJob> = _job

    val docs: StateFlow<List<ScannerStore.DocEntity>> =
        store.docs.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val prefs: StateFlow<ScannerPrefs> = settings.prefs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ScannerPrefs())

    /** Save a thumbnail and remember it for the result screen. */
    private suspend fun saveThumb(bitmap: Bitmap, key: String): String = withContext(Dispatchers.IO) {
        val dir = File(getApplication<Application>().filesDir, "thumbs").apply { mkdirs() }
        val f = File(dir, "$key.jpg")
        f.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it) }
        f.absolutePath
    }

    /** Full pipeline: enhance -> OCR, per page, in sequence. */
    fun processImages(
        uris: List<Uri>,
        type: String,
        label: String,
        options: ImageEnhancer.Options = ImageEnhancer.Options(
            grayscale = true, contrast = 1.4f, sharpen = true, removeShadow = true
        )
    ) {
        if (uris.isEmpty()) return
        _job.value = ScanJob.Running(0, uris.size, label)
        viewModelScope.launch {
            val context = getApplication<Application>()
            val prefsNow = prefs.value
            val pages = mutableListOf<PageResult>()
            var anyText = false
            var lastQuality: ImageQuality.Report? = null
            try {
                uris.forEachIndexed { index, uri ->
                    val bitmap = withContext(Dispatchers.IO) {
                        ImageEnhancer.loadScaled(context, uri)
                    } ?: throw IllegalStateException("Could not read image ${index + 1}")
                    lastQuality = ImageQuality.analyze(bitmap)

                    // Pass 1: enhanced image. Pass 2 fallback: the raw photo —
                    // aggressive enhancement can hurt low-light / colorful pages.
                    val working = ImageEnhancer.apply(bitmap, options)
                    var result = ocr.recognize(working, prefsNow.ocrScript)
                    if (result.wordCount == 0) {
                        result = ocr.recognize(bitmap, prefsNow.ocrScript)
                    }
                    if (result.wordCount > 0) anyText = true

                    val thumb = saveThumb(bitmap, "page_${System.currentTimeMillis()}_$index")

                    pages.add(
                        PageResult(
                            index = index,
                            name = "Page ${index + 1}",
                            text = result.text,
                            thumbnailPath = thumb
                        )
                    )
                }
                _job.value =
                    if (anyText) ScanJob.Done(pages)
                    else ScanJob.Error(ImageQuality.failureReason(lastQuality))
            } catch (e: Exception) {
                _job.value = ScanJob.Error(friendlyMessage(e))
            }
        }
    }

    fun processPdf(uris: List<Uri>) {
        if (uris.isEmpty()) return
        _job.value = ScanJob.Running(0, uris.size, "Reading PDF…")
        viewModelScope.launch {
            val context = getApplication<Application>()
            try {
                val pdfPages = withContext(Dispatchers.IO) { PdfExtractor.render(context, uris.first()) }
                if (pdfPages.isEmpty()) throw IllegalStateException("No pages could be rendered.")
                processRenderedPages(pdfPages, "pdf", "Extracting text from PDF…")
            } catch (e: Exception) {
                _job.value = ScanJob.Error("Unable to process this file.")
            }
        }
    }

    /** Entry point after PdfExtractor.render() — OCR an already-decoded page list. */
    private suspend fun processRenderedPages(pages: List<Bitmap>, type: String, label: String) {
        _job.value = ScanJob.Running(0, pages.size, label)
        val prefsNow = prefs.value
        val out = mutableListOf<PageResult>()
        var anyText = false
        var lastQuality: ImageQuality.Report? = null
        try {
            pages.forEachIndexed { index, bmp ->
                lastQuality = ImageQuality.analyze(bmp)
                val enhanced = ImageEnhancer.apply(
                    bmp,
                    ImageEnhancer.Options(grayscale = true, contrast = 1.35f, sharpen = true)
                )
                var result = ocr.recognize(enhanced, prefsNow.ocrScript)
                if (result.wordCount == 0) {
                    result = ocr.recognize(bmp, prefsNow.ocrScript)
                }
                if (result.wordCount > 0) anyText = true
                out.add(
                    PageResult(
                        index = index,
                        name = "Page ${index + 1}",
                        text = result.text,
                        thumbnailPath = saveThumb(bmp, "pdf_${System.currentTimeMillis()}_$index")
                    )
                )
                _job.value = ScanJob.Running(index + 1, pages.size, label)
            }
            _job.value =
                if (anyText) ScanJob.Done(out)
                else ScanJob.Error(ImageQuality.failureReason(lastQuality).let { reason ->
                    if (reason == ImageQuality.failureReason(null))
                        "Unable to recognize text in this PDF. If it's a scan, try importing screenshots instead."
                    else reason
                })
        } catch (e: Exception) {
            _job.value = ScanJob.Error(friendlyMessage(e))
        }
    }

    /** Persist the current result as a document in history. */
    fun saveDocument(name: String, text: String, type: String, thumbnailPath: String?) {
        viewModelScope.launch {
            store.insert(
                ScannerStore.DocEntity(
                    name = name.ifBlank { "Scan" },
                    text = text,
                    type = type,
                    thumbnailPath = thumbnailPath,
                    wordCount = ScannerStore.countWords(text)
                )
            )
        }
    }

    fun renameDocument(id: Long, name: String) { viewModelScope.launch { store.rename(id, name) } }
    fun setPinned(id: Long, pinned: Boolean) { viewModelScope.launch { store.setPinned(id, pinned) } }
    fun deleteDocument(id: Long) { viewModelScope.launch { store.delete(id) } }
    fun clearHistory() { viewModelScope.launch { store.clearAll() } }

    fun resetJob() { _job.value = ScanJob.Idle }

    fun setThemeMode(v: String) { viewModelScope.launch { settings.setThemeMode(v) } }
    fun setOcrScript(v: String) { viewModelScope.launch { settings.setOcrScript(v) } }
    fun setTargetLanguage(v: String) { viewModelScope.launch { settings.setTargetLanguage(v) } }
    fun setAutoEnhance(v: Boolean) { viewModelScope.launch { settings.setAutoEnhance(v) } }
    fun setAutoSpeak(v: Boolean) { viewModelScope.launch { settings.setAutoSpeak(v) } }

    fun exportTxt(name: String, text: String, onDone: (File) -> Unit, onError: (String) -> Unit) {
        viewModelScope.launch {
            try {
                onDone(Exporter.saveTxt(getApplication(), name, text))
            } catch (e: Exception) {
                onError("Couldn't save the file.")
            }
        }
    }

    fun exportPdf(name: String, text: String, onDone: (File) -> Unit, onError: (String) -> Unit) {
        viewModelScope.launch {
            try {
                onDone(Exporter.savePdf(getApplication(), name, text))
            } catch (e: Exception) {
                onError("Couldn't create the PDF.")
            }
        }
    }

    private fun friendlyMessage(e: Exception): String = when {
        e.message?.contains("permission", ignoreCase = true) == true ->
            "Camera permission required for scanning"
        else -> "Something went wrong while processing. Please try again."
    }
}
