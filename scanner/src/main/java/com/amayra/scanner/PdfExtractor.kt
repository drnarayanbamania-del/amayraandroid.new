package com.amayra.scanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Renders PDF pages to bitmaps via the platform PdfRenderer for OCR. */
object PdfExtractor {

    /** Render up to [maxPages] pages of [uri] at ~160dpi for OCR. */
    suspend fun render(context: Context, uri: Uri, maxPages: Int = 20): List<Bitmap> =
        withContext(Dispatchers.IO) {
            val src = when {
                uri.scheme == "content" -> context.contentResolver.openFileDescriptor(uri, "r")
                else -> {
                    val p = java.io.File(uri.path ?: return@withContext emptyList())
                    if (p.exists()) android.os.ParcelFileDescriptor.open(
                        p, android.os.ParcelFileDescriptor.MODE_READ_ONLY
                    ) else null
                }
            } ?: return@withContext emptyList()

            src.use { pfd ->
                val out = mutableListOf<Bitmap>()
                try {
                    PdfRenderer(pfd).use { renderer ->
                        val count = minOf(renderer.pageCount, maxPages)
                        for (i in 0 until count) {
                            renderer.openPage(i).use { page ->
                                // ~160 dpi render scale
                                val scale = 160f / 72f
                                val w = (page.width * scale).toInt().coerceIn(1, 2200)
                                val h = (page.height * scale).toInt().coerceIn(1, 2200)
                                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                                bmp.eraseColor(Color.WHITE)
                                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                out.add(bmp)
                            }
                        }
                    }
                } catch (_: SecurityException) {
                    return@withContext emptyList()
                } catch (_: Exception) {
                    if (out.isEmpty()) return@withContext emptyList()
                }
                out
            }
        }
}
