package com.castla.mirror.update

object UpdateManagerFactory {
    fun create(): UpdateManager = StandaloneUpdateManager()
}
