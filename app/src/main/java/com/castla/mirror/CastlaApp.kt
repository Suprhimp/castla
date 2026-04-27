package com.castla.mirror

import android.app.Application
import com.castla.mirror.diagnostics.FileLogger

class CastlaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        FileLogger.init(this)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                FileLogger.e("UEH", "Uncaught on ${thread.name}", throwable)
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, throwable)
        }
    }
}
