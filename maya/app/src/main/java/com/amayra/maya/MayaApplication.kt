package com.amayra.maya

import android.app.Application
import android.content.Context

/**
 * Thin Android shell. ALL composition lives in [AppGraph.init]; every property
 * forwards to the graph so existing `app.settings`-style call sites keep
 * working without a rename pass.
 */
class MayaApplication : Application() {

    val settings get() = AppGraph.settings
    val secure get() = AppGraph.secure
    val db get() = AppGraph.db
    val ai get() = AppGraph.ai
    val registry get() = AppGraph.registry
    val voice get() = AppGraph.voice
    val avatar get() = AppGraph.avatar
    val live2d get() = AppGraph.live2d
    val live2dModels get() = AppGraph.live2dModels
    val core get() = AppGraph.core
    val guardian get() = AppGraph.guardian

    override fun onCreate() {
        super.onCreate()
        AppGraph.init(this)
    }

    /**
     * Device has only 7.5 GB RAM and runs under constant LMK pressure
     * (observed: device memory 473 MB / free 54 MB). Maya's Kokoro TTS holds
     * ~200 MB native for the loaded model; when the system is squeezing, the
     * app gets killed silently ("baar baar band"). Release the big engine the
     * moment pressure arrives — it lazily reloads on the next utterance.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        when (level) {
            TRIM_MEMORY_RUNNING_LOW,
            TRIM_MEMORY_RUNNING_CRITICAL,
            TRIM_MEMORY_COMPLETE,
            TRIM_MEMORY_MODERATE -> {
                runCatching { AppGraph.voice.releaseHeavyEngines() }
            }
        }
    }

    companion object {
        fun get(ctx: Context): MayaApplication = ctx.applicationContext as MayaApplication
    }
}
