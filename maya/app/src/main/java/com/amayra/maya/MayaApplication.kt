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

    companion object {
        fun get(ctx: Context): MayaApplication = ctx.applicationContext as MayaApplication
    }
}
