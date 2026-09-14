package com.amayra.maya.screen

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import com.amayra.maya.feature.Platform
import com.amayra.maya.core.MayaLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicReference

/**
 * Owns the MediaProjection session and the latest captured frame.
 *
 * Flow: MainActivity receives Android's MediaProjection consent dialog result
 * (forwarded on [ScreenShareBus.consent]) → this service is started foreground
 * (mediaProjection FGS type, required on API 35+) → creates a VirtualDisplay
 * rendering into an ImageReader → every frame replaces the latest one.
 *
 * Consumers (SeeScreenTool) pull the newest frame as a downscaled JPEG data URL.
 * The mediaProjection FGS type requires a valid consent result BEFORE startForeground
 * — Android throws if the projection was granted more than a few seconds ago.
 */
class ScreenCaptureService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var width = 0
    private var height = 0
    private val latestBitmap = AtomicReference<Bitmap?>(null)

    private val projectionListener = object : MediaProjection.Callback() {
        override fun onStop() {
            MayaLog.i("SCREEN", "Projection stopped by system/user")
            ScreenShareBus.publishActive(false)
            stopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                teardown()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                @Suppress("DEPRECATION")
                val data = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                if (resultCode != Activity.RESULT_OK || data == null) {
                    MayaLog.w("SCREEN", "Screen capture started without consent — stopping")
                    ScreenShareBus.publishActive(false)
                    stopSelf()
                    return START_NOT_STICKY
                }
                startForegroundCompat()
                startCapture(resultCode, data)
            }
        }
        return START_NOT_STICKY
    }

    private fun startCapture(resultCode: Int, data: Intent) {
        try {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = mpm.getMediaProjection(resultCode, data)
            projection?.registerCallback(projectionListener, null)

            val metrics = resources.displayMetrics
            width = metrics.widthPixels
            height = metrics.heightPixels
            val density = metrics.densityDpi

            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            imageReader?.setOnImageAvailableListener({ reader ->
                var image: Image? = null
                try {
                    image = reader.acquireLatestImage()
                    if (image != null) {
                        latestBitmap.set(imageToBitmap(image))
                    }
                } catch (t: Throwable) {
                    MayaLog.w("SCREEN", "frame read failed: ${t.message}")
                } finally {
                    try { image?.close() } catch (_: Throwable) {}
                }
            }, null)

            virtualDisplay = projection?.createVirtualDisplay(
                "maya-screen",
                width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader!!.surface,
                null, null
            )
            ScreenShareBus.publishActive(true)
            MayaLog.i("SCREEN", "Capturing ${width}x${height}")
        } catch (t: Throwable) {
            // SecurityException: stale/invalid consent token — must be honest and stop.
            MayaLog.e("SCREEN", "startCapture failed: ${t.message}", t)
            ScreenShareBus.publishActive(false)
            ScreenShareBus.publishError(
                "Screen capture failed (${t.message}). Re-run screen share to grant a fresh consent."
            )
            stopSelf()
        }
    }

    /** Convert an RGBA_8888 Image to a Bitmap (row stride aware). */
    private fun imageToBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val rowPadding = rowStride - pixelStride * width
        val bmp = Bitmap.createBitmap(
            width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888
        )
        bmp.copyPixelsFromBuffer(plane.buffer)
        // Crop the padding column introduced by the row stride.
        return if (rowPadding > 0) {
            Bitmap.createBitmap(bmp, 0, 0, width, height)
        } else bmp
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    private fun teardown() {
        try { virtualDisplay?.release() } catch (_: Throwable) {}
        try { imageReader?.close() } catch (_: Throwable) {}
        try { projection?.stop() } catch (_: Throwable) {}
        virtualDisplay = null
        imageReader = null
        projection = null
        latestBitmap.set(null)
        ScreenShareBus.publishActive(false)
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Screen share", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val pi = PendingIntent.getActivity(
            this, 0, Platform.launchMainActivityIntent(),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notif = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Maya is watching your screen")
            .setContentText("Tap to open Maya · say \"stop screen share\" to end")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    companion object {
        private const val CHANNEL_ID = "maya_screenshare"
        private const val NOTIF_ID = 51
        const val ACTION_START = "com.amayra.maya.screen.START"
        const val ACTION_STOP = "com.amayra.maya.screen.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        fun start(context: Context, resultCode: Int, data: Intent) {
            val i = Intent(context, ScreenCaptureService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, ScreenCaptureService::class.java).setAction(ACTION_STOP))
        }

        /** Latest frame as a downscaled JPEG data URL, or null. */
        fun latestFrameDataUrl(maxDim: Int = 1024, quality: Int = 72): String? {
            val src = instance?.latestBitmap?.get() ?: return null
            return try {
                val scale = minOf(1f, maxDim.toFloat() / maxOf(src.width, src.height))
                val bmp = if (scale < 1f) {
                    Bitmap.createScaledBitmap(src, (src.width * scale).toInt(), (src.height * scale).toInt(), true)
                } else src
                val bos = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, quality, bos)
                val b64 = android.util.Base64.encodeToString(bos.toByteArray(), android.util.Base64.NO_WRAP)
                "data:image/jpeg;base64,$b64"
            } catch (t: Throwable) {
                MayaLog.w("SCREEN", "frame encode failed: ${t.message}")
                null
            }
        }

        @Volatile var instance: ScreenCaptureService? = null
            private set
    }

    init {
        instance = this
    }
}

/** Bus between consent UI / tools / service. */
object ScreenShareBus {
    private val _consent = kotlinx.coroutines.flow.MutableSharedFlow<Pair<Int, Intent>>(extraBufferCapacity = 1)
    val consent: kotlinx.coroutines.flow.SharedFlow<Pair<Int, Intent>> = _consent
    fun publishConsent(resultCode: Int, data: Intent) { _consent.tryEmit(resultCode to data) }

    private val _active = kotlinx.coroutines.flow.MutableStateFlow(false)
    val active: kotlinx.coroutines.flow.StateFlow<Boolean> = _active
    fun publishActive(v: Boolean) { _active.value = v }

    private val _error = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 1)
    val error: kotlinx.coroutines.flow.SharedFlow<String> = _error
    fun publishError(msg: String) { _error.tryEmit(msg) }
}
