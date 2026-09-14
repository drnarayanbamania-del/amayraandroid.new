package com.amayra.maya.voice.guardian

import android.content.Context
import android.os.ParcelFileDescriptor
import com.amayra.maya.core.MayaLog
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/** Asset-backed TFLite helpers (no support-library dependency). */
object TFLiteSupport {

    fun loadAsset(context: Context, path: String): ByteBuffer? = try {
        val afd = context.assets.openFd(path)
        FileInputStream(afd.fileDescriptor).channel.map(
            FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength
        )
    } catch (t: Throwable) {
        // Some build pipelines compress assets; fall back to a heap buffer.
        try {
            val bytes = context.assets.open(path).use { it.readBytes() }
            val buf = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
            buf.put(bytes); buf.rewind()
            buf
        } catch (t2: Throwable) {
            MayaLog.w("TFLITE", "Asset missing: $path (${t.message})")
            null
        }
    }

    fun newInterpreter(buf: ByteBuffer, threads: Int = 2): Interpreter =
        Interpreter(buf, Interpreter.Options().setNumThreads(threads))

    /** Tensor shape of input [index], or null. */
    fun inputShape(itp: Interpreter, index: Int): IntArray? = runCatching {
        itp.getInputTensor(index).shape()
    }.getOrNull()

    fun outputShape(itp: Interpreter, index: Int): IntArray? = runCatching {
        itp.getOutputTensor(index).shape()
    }.getOrNull()

    fun inputCount(itp: Interpreter): Int = runCatching { itp.inputTensorCount }.getOrDefault(0)

    fun inputName(itp: Interpreter, index: Int): String? = runCatching {
        itp.getInputTensor(index).name()
    }.getOrNull()

    fun isAssetPresent(context: Context, path: String): Boolean = try {
        context.assets.open(path).use { true }
    } catch (_: Throwable) { false }

    /** Allocate direct float buffer of [size]. */
    fun floatBuf(size: Int): ByteBuffer =
        ByteBuffer.allocateDirect(size * 4).order(ByteOrder.nativeOrder())

    /** Direct float buffer view of a JVM float array (writes then rewinds). */
    fun floatBufOf(values: FloatArray): ByteBuffer =
        floatBuf(values.size).also { it.asFloatBuffer().put(values); it.rewind() }
}

/** PCM16 [-32768..32767] -> float [-1..1]. */
fun ShortArray.toFloat32(): FloatArray = FloatArray(size) { this[it] / 32768f }

/** Write a short array chunk into a direct buffer. */
fun ShortArray.toDirectPcm(): ByteBuffer =
    ByteBuffer.allocateDirect(size * 2).order(ByteOrder.nativeOrder()).also {
        it.asShortBuffer().put(this); it.rewind()
    }
