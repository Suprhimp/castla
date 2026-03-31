package com.castla.mirror

import android.app.Application
import com.castla.mirror.ads.AdManager

class CastlaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AdManager.init(this)
    }
}
