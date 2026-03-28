package com.castla.mirror.utils

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

data class AppLaunchRequest(
    val packageName: String,
    val className: String? = null,
    val isVideoApp: Boolean = false,
    val intentExtra: String? = null,
    val splitMode: Boolean = false
)

/**
 * Singleton bus to pass launch requests from UI (DesktopActivity) to the running MirrorService.
 */
object AppLaunchBus {
    private val _events = MutableSharedFlow<AppLaunchRequest>(extraBufferCapacity = 5)
    val events: SharedFlow<AppLaunchRequest> = _events.asSharedFlow()

    fun requestLaunch(packageName: String, className: String? = null, isVideoApp: Boolean = false, intentExtra: String? = null, splitMode: Boolean = false) {
        _events.tryEmit(AppLaunchRequest(packageName, className, isVideoApp, intentExtra, splitMode))
    }
}
