package com.getmaya.android

import android.app.Application

class MayaApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        LicenseState.init(this)
    }

    companion object {
        lateinit var instance: MayaApplication
            private set
    }
}
