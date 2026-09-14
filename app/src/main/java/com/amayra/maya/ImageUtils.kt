package com.amayra.maya

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream

/** Reads a content Uri, downscales it, and returns a JPEG data URL for AI multimodal input. */
fun MayaApplication.readImageAsDataUrl(uri: Uri, maxDim: Int = 1280): String? = try {
    val source = ImageDecoder.createSource(contentResolver, uri)
    val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        decoder.setTargetSampleSize(
            maxOf(info.size.width, info.size.height) / maxDim + 1
        )
    }
    val scaled = scaleDown(bitmap, maxDim)
    val bos = ByteArrayOutputStream()
    scaled.compress(Bitmap.CompressFormat.JPEG, 82, bos)
    val b64 = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
    "data:image/jpeg;base64,$b64"
} catch (t: Throwable) {
    com.amayra.maya.core.MayaLog.e("MEDIA", "Image read failed", t)
    null
}

private fun scaleDown(bitmap: Bitmap, maxDim: Int): Bitmap {
    val w = bitmap.width
    val h = bitmap.height
    val scale = minOf(1f, maxDim.toFloat() / maxOf(w, h))
    if (scale >= 1f) return bitmap
    return Bitmap.createScaledBitmap(bitmap, (w * scale).toInt(), (h * scale).toInt(), true)
}
