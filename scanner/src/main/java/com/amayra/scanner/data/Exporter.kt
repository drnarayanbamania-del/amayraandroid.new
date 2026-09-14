package com.amayra.scanner.data

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Local export helpers — TXT and lightweight PDF output plus share/open
 * intents via FileProvider. All files land in filesDir/exports.
 */
object Exporter {

    private fun exportsDir(context: Context): File =
        File(context.filesDir, "exports").apply { mkdirs() }

    /** Write extracted text as a .txt file; returns the file. */
    suspend fun saveTxt(context: Context, name: String, text: String): File = withContext(Dispatchers.IO) {
        val safe = sanitize(name)
        File(exportsDir(context), "$safe.txt").apply { writeText(text) }
    }

    /**
     * Minimal single/multi-page PDF writer: text wrapped into simple lines,
     * Helvetica, letter size. Good enough for a local text export.
     */
    suspend fun savePdf(context: Context, name: String, text: String): File = withContext(Dispatchers.IO) {
        val safe = sanitize(name)
        val lines = wrapText(text, 90)
        val perPage = 54
        val pages: List<List<String>> =
            if (lines.isEmpty()) listOf(listOf("")) else lines.chunked(perPage)
        val objects = mutableListOf<String>()
        val pageObjNums = pages.indices.map { 4 + it * 2 } // layout fixed below

        fun esc(s: String) = s.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")

        pages.forEach { pageLines ->
            val y0 = 740
            val ops = StringBuilder("BT\n/F1 11 Tf\n16 TL\n")
            ops.append("1 0 0 1 56 ").append(y0).append(" Tm\n")
            pageLines.forEach { l ->
                ops.append("(").append(esc(l)).append(") Tj T*\n")
            }
            ops.append("ET")
            objects += ops.toString()
            objects += "" // content-stream object placeholder filled later
        }

        // Assemble body: 1=catalog, 2=pages, 3=font, then per page: page obj + content obj
        val body = StringBuilder()
        val offsets = mutableListOf<Int>()
        fun put(num: Int, obj: String) {
            offsets.add(num - 1, body.length)
            body.append(num).append(" 0 obj\n").append(obj).append("\nendobj\n")
        }

        val pageCount = pages.size
        put(1, "<< /Type /Catalog /Pages 2 0 R >>")
        put(2, "<< /Type /Pages /Count $pageCount /Kids [${pageObjNums.joinToString(" ") { "$it 0 R" }}] >>")
        put(3, "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")
        var next = 4
        pages.forEachIndexed { i, _ ->
            val content = objects[i * 2]
            put(next, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 3 0 R >> >> /Contents ${next + 1} 0 R >>")
            put(next + 1, "<< /Length ${content.length} >>\nstream\n$content\nendstream")
            next += 2
        }

        val total = next - 1
        val pdf = StringBuilder("%PDF-1.4\n")
        val xrefOffsets = mutableListOf<Int>()
        for (n in 1..total) {
            xrefOffsets.add(pdf.length)
            pdf.append(buildObject(body, offsets, n))
        }
        val xrefPos = pdf.length
        pdf.append("xref\n0 ").append(total + 1).append("\n")
        pdf.append("0000000000 65535 f \n")
        for (off in xrefOffsets) pdf.append("%010d 00000 n \n".format(off))
        pdf.append("trailer\n<< /Size ${total + 1} /Root 1 0 R >>\nstartxref\n").append(xrefPos).append("\n%%EOF")

        File(exportsDir(context), "$safe.pdf").apply { writeText(pdf.toString()) }
    }

    private fun buildObject(body: StringBuilder, offsets: List<Int>, n: Int): String {
        // Re-extract object n's text from the accumulated body by re-walking offsets.
        val start = offsets[n - 1]
        val end = if (n < offsets.size) offsets[n] else body.length
        return body.substring(start, end)
    }

    private fun wrapText(text: String, width: Int): List<String> {
        val out = mutableListOf<String>()
        text.replace("\r\n", "\n").split("\n").forEach { raw ->
            var line = raw
            while (line.length > width) {
                var cut = line.lastIndexOf(' ', width)
                if (cut <= 0) cut = width
                out.add(line.substring(0, cut))
                line = line.substring(cut).trimStart()
            }
            out.add(line)
        }
        return out
    }

    private fun sanitize(name: String) =
        name.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().ifEmpty { "scan" }.take(60)

    fun contentUri(context: Context, file: File) =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    fun sharePdf(context: Context, file: File) {
        val uri = contentUri(context, file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share PDF"))
    }

    fun shareText(context: Context, text: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(intent, "Share text"))
    }

    /** True if WhatsApp is installed. */
    fun canWhatsApp(context: Context): Boolean =
        runCatching { context.packageManager.getPackageInfo("com.whatsapp", 0) }.isSuccess

    /**
     * Share text straight into WhatsApp (opens the forward/pick-contact screen).
     * WhatsApp rejects file:// streams for text; plain EXTRA_TEXT works everywhere.
     */
    fun shareTextToWhatsApp(context: Context, text: String): Boolean {
        if (!canWhatsApp(context)) return false
        return runCatching {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                `package` = "com.whatsapp"
                putExtra(Intent.EXTRA_TEXT, text)
            }
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    /** Share an exported PDF file into WhatsApp via FileProvider. */
    fun sharePdfToWhatsApp(context: Context, file: File): Boolean {
        if (!canWhatsApp(context)) return false
        return runCatching {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                `package` = "com.whatsapp"
                putExtra(Intent.EXTRA_STREAM, contentUri(context, file))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    fun openPdf(context: Context, file: File) {
        val uri = contentUri(context, file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/pdf")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { context.startActivity(intent) }
    }
}
