package com.amayra.scanner.ocr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import kotlin.math.max
import kotlin.math.min

/**
 * Pure-Kotlin bitmap pre-processing before OCR: resize, rotation, grayscale,
 * auto contrast, sharpen, background/shadow flattening and hard B&W
 * thresholding. No native deps — runs in a background dispatcher.
 */
object ImageEnhancer {

    data class Options(
        val rotationDegrees: Int = 0,
        val maxDimension: Int = 2048,
        val grayscale: Boolean = true,
        val contrast: Float = 1f,          // 1 = unchanged, 2 = strong
        val sharpen: Boolean = false,
        val removeShadow: Boolean = false,
        val blackAndWhite: Boolean = false,
        val brightness: Float = 1f         // 1 = unchanged, 1.4 = brighter
    )

    /** Load a bitmap from a content/file Uri with downscaling to maxDimension. */
    fun loadScaled(context: android.content.Context, uri: Uri, maxDimension: Int = 2048): Bitmap? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
        var sample = 1
        var dim = max(bounds.outWidth, bounds.outHeight)
        while (dim / 2 >= maxDimension) { sample *= 2; dim /= 2 }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }

    fun apply(src: Bitmap, o: Options): Bitmap {
        var bmp = src
        if (o.rotationDegrees != 0) bmp = rotate(bmp, o.rotationDegrees.toFloat())
        bmp = scaleDown(bmp, o.maxDimension)

        val w = bmp.width; val h = bmp.height
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)

        if (o.grayscale || o.blackAndWhite || o.removeShadow) toGrayscale(pixels)
        if (o.removeShadow) flattenBackground(pixels, w, h)
        if (o.contrast != 1f || o.brightness != 1f) adjustContrastBrightness(pixels, o.contrast, o.brightness)
        if (o.blackAndWhite) threshold(pixels, 165)

        var out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        if (o.sharpen) out = unsharp(out)
        return out
    }

    fun rotate(bmp: Bitmap, degrees: Float): Bitmap {
        val m = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }

    private fun scaleDown(bmp: Bitmap, maxDim: Int): Bitmap {
        val largest = max(bmp.width, bmp.height)
        if (largest <= maxDim) return bmp
        val scale = maxDim.toFloat() / largest
        return Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt().coerceAtLeast(1), (bmp.height * scale).toInt().coerceAtLeast(1), true)
    }

    private fun toGrayscale(px: IntArray) {
        for (i in px.indices) {
            val p = px[i]
            val g = ((p shr 16 and 0xFF) * 299 + (p shr 8 and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
            px[i] = (p and 0xFF000000.toInt()) or (g shl 16) or (g shl 8) or g
        }
    }

    private fun adjustContrastBrightness(px: IntArray, contrast: Float, brightness: Float) {
        for (i in px.indices) {
            val p = px[i]
            val r = applyChannel(p shr 16 and 0xFF, contrast, brightness)
            val g = applyChannel(p shr 8 and 0xFF, contrast, brightness)
            val b = applyChannel(p and 0xFF, contrast, brightness)
            px[i] = (p and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
        }
    }

    private fun applyChannel(v: Int, contrast: Float, brightness: Float): Int =
        min(255, max(0f, ((v - 128) * contrast + 128) * brightness).toInt())

    /** Unsharp mask via 3x3 kernel — quick edge crispness for OCR. */
    private fun unsharp(src: Bitmap): Bitmap {
        val w = src.width; val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val out = px.copyOf()
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val i = y * w + x
                var sum = 0
                for (dy in -1..1) for (dx in -1..1) {
                    val p = px[i + dy * w + dx]
                    sum += p shr 16 and 0xFF
                }
                val blur = sum / 9
                val v = px[i] shr 16 and 0xFF
                val sharpened = min(255, max(0, 2 * v - blur))
                out[i] = (px[i] and 0xFF000000.toInt()) or (sharpened shl 16) or (sharpened shl 8) or sharpened
            }
        }
        val res = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        res.setPixels(out, 0, w, 0, 0, w, h)
        return res
    }

    /**
     * Estimate per-row+column illumination with a coarse integral image and
     * divide it out — flattens shadows and uneven lighting cheaply.
     */
    private fun flattenBackground(px: IntArray, w: Int, h: Int) {
        val step = max(1, min(w, h) / 64)
        val gw = w / step + 1
        val gh = h / step + 1
        val integral = LongArray(gw * gh)
        for (gy in 0 until gh) {
            var rowSum = 0L
            for (gx in 0 until gw) {
                val sx = min(gx * step, w - 1)
                val sy = min(gy * step, h - 1)
                rowSum += px[sy * w + sx] shr 16 and 0xFF
                val idx = gy * gw + gx
                integral[idx] = rowSum + (if (gy > 0) integral[(gy - 1) * gw + gx] else 0L)
            }
        }
        val win = max(1, gw / 8)
        val target = 210.0
        for (y in 0 until h) {
            for (x in 0 until w) {
                val gx0 = max(0, x / step - win / 2)
                val gx1 = min(gw - 1, x / step + win / 2)
                val gy0 = max(0, y / step - win / 2)
                val gy1 = min(gh - 1, y / step + win / 2)
                val area = ((gx1 - gx0 + 1) * (gy1 - gy0 + 1)).toLong()
                var sum = integral[gy1 * gw + gx1]
                if (gy0 > 0) sum -= integral[(gy0 - 1) * gw + gx1]
                if (gx0 > 0) sum -= integral[gy1 * gw + gx0]
                if (gy0 > 0 && gx0 > 0) sum += integral[(gy0 - 1) * gw + gx0]
                val mean = sum / area.toDouble()
                if (mean > 1) {
                    val gain = min(2.5, target / mean)
                    val i = y * w + x
                    val v = px[i] shr 16 and 0xFF
                    val nv = min(255, max(0, (v * gain).toInt()))
                    px[i] = (px[i] and 0xFF000000.toInt()) or (nv shl 16) or (nv shl 8) or nv
                }
            }
        }
    }

    /** Hard threshold after contrast normalization — clean B&W document look. */
    private fun threshold(px: IntArray, t: Int) {
        for (i in px.indices) {
            val v = px[i] shr 16 and 0xFF
            val b = if (v >= t) 255 else 0
            px[i] = (px[i] and 0xFF000000.toInt()) or (b shl 16) or (b shl 8) or b
        }
    }
}
