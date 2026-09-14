package com.amayra.maya.voice.wakeword

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.amayra.maya.feature.Platform
import com.amayra.maya.core.AssistantState
import com.amayra.maya.core.MayaLog
import com.amayra.maya.core.StateBus
import com.amayra.maya.voice.guardian.VoiceGuardian
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Always-on mic loop for wake word + Voice Guardian.
 *
 * Design notes:
 *  - One 16 kHz mono AudioRecord feeds both the wake-word engine and (when the
 *    user opts in) the Guardian's voiced-audio buffer.
 *  - Wake word hot => publishes a WAKE event; MayaCoreService/Chat routes it to
 *    startListening().
 *  - Guardian verification runs only when 6 s of voiced audio accumulated —
 *    never blocks the audio loop (embeddings run on a separate dispatcher).
 */
class WakeWordService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var running = false

    private var audioThread: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var engine: WakeWordEngine? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            else -> startLoop()
        }
        return START_STICKY
    }

    private fun startLoop() {
        if (running) return
        // Foreground FIRST: on Android 12+ startForegroundService must show the
        // notification within ~10s or the system throws
        // ForegroundServiceDidNotStartInTimeException (observed crash on CPH2505).
        // TFLite engine load below can take seconds — never before this.
        startForegroundCompat()
        val wwe = WakeWordEngine.load(this)
        if (wwe == null) {
            MayaLog.w("WAKE", "Wake-word engine unavailable — service exiting (graceful)")
            stopSelf()
            return
        }
        engine = wwe
        WakeWordService.engineLoaded = true
        running = true
        WakeWordService.serviceRunning = true
        acquireWakeLock()
        audioThread = Thread({ loop(wwe) }, "maya-wakeword").apply {
            priority = Thread.NORM_PRIORITY - 1
            start()
        }
    }

    private fun loop(wwe: WakeWordEngine) {
        // Guardian init is lazy + heavy (44 MB ECAPA) — do it on this worker thread.
        val guardian = runCatching { VoiceGuardian.init(this@WakeWordService) }.getOrNull()
        val minBuf = android.media.AudioRecord.getMinBufferSize(
            SAMPLE_RATE, android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) { MayaLog.e("WAKE", "No AudioRecord support"); stopSelf(); return }
        val bufSize = maxOf(minBuf, wwe.chunkSamples * 2 * 4)
        val record = try {
            android.media.AudioRecord(
                android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE, android.media.AudioFormat.CHANNEL_IN_MONO,
                android.media.AudioFormat.ENCODING_PCM_16BIT, bufSize
            )
        } catch (t: Throwable) {
            MayaLog.e("WAKE", "AudioRecord create failed", t); stopSelf(); return
        }
        if (record.state != android.media.AudioRecord.STATE_INITIALIZED) {
            MayaLog.e("WAKE", "AudioRecord not initialized (mic permission?)")
            record.release(); stopSelf(); return
        }
        record.startRecording()
        val chunk = ShortArray(wwe.chunkSamples)
        val guardBuf = if (guardian != null && guardian.isUsable) VoiceGuardian.VoicedBuffer() else null
        MayaLog.i("WAKE", "Wake-word loop started (${wwe.chunkSamples}-sample chunks)")
        var lastTriggerAt = 0L
        try {
            while (running) {
                val n = record.read(chunk, 0, chunk.size)
                if (n <= 0) continue
                val floats = FloatArray(n) { chunk[it] / 32768f }
                val score = wwe.feed(floats)
                if (score >= 1f) {
                    // Duplicate-trigger cooldown: hotword echo / engine double-fire
                    // within this window is swallowed instead of restarting STT.
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now - lastTriggerAt < WAKE_COOLDOWN_MS) {
                        MayaLog.d("WAKE", "wake suppressed (cooldown ${WAKE_COOLDOWN_MS}ms)")
                        continue
                    }
                    lastTriggerAt = now
                    MayaLog.i("WAKE", "Wake word detected!")
                    StateBus.setState(AssistantState.Listening())
                    StateBus.publishWake()
                }
                // Guardian piggyback: collect voiced audio and verify when a window fills.
                if (guardBuf != null) {
                    val window = guardian?.feedFrame(floats, guardBuf)
                    if (window != null) {
                        val g = guardian ?: continue
                        scope.launch {
                            when (val r = g.verify(window)) {
                                is VoiceGuardian.VerifyResult.Scored -> {
                                    StateBus.publishGuardian(
                                        StateBus.GuardianSignal(r.label, r.probability, r.accepted)
                                    )
                                    if (r.accepted) MayaLog.i("WAKE", "Guardian accepted ${r.label}")
                                    else MayaLog.i("WAKE", "Guardian rejected (${r.label} %.2f)".format(r.probability))
                                }
                                else -> {}
                            }
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            if (running) MayaLog.e("WAKE", "Audio loop crashed", t)
        } finally {
            try { record.stop() } catch (_: Throwable) {}
            record.release()
            MayaLog.i("WAKE", "Wake-word loop ended")
        }
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Wake word listening", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val pi = PendingIntent.getActivity(
            this, 0, Platform.launchMainActivityIntent(),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notif: Notification = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Maya is listening for \"Hey Maya\"")
                .setContentText("Tap to open Maya")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentIntent(pi)
                .setOngoing(true)
                .build()
        } else {
            Notification.Builder(this)
                .setContentTitle("Maya is listening for \"Hey Maya\"")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentIntent(pi)
                .setOngoing(true)
                .build()
        }
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "maya:wakeword").apply {
            setReferenceCounted(false)
            acquire(4 * 60 * 60 * 1000L) // 4h; service restart refreshes
        }
    }

    override fun onDestroy() {
        running = false
        WakeWordService.serviceRunning = false
        WakeWordService.engineLoaded = false
        audioThread?.interrupt()
        engine?.close()
        engine = null
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        super.onDestroy()
    }

    companion object {
        @Volatile var serviceRunning: Boolean = false
            private set
        @Volatile var engineLoaded: Boolean = false
            private set

        private const val CHANNEL_ID = "maya_wakeword"
        private const val NOTIF_ID = 42

        /** Minimum gap between accepted wake triggers (echo/double-fire guard). */
        const val WAKE_COOLDOWN_MS = 4_000L
        const val SAMPLE_RATE = 16_000
        const val ACTION_STOP = "com.amayra.maya.wakeword.STOP"

        fun start(context: Context) {
            val i = Intent(context, WakeWordService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, WakeWordService::class.java).setAction(ACTION_STOP))
        }
    }
}

