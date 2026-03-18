package com.castla.mirror.utils

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * In-process event bus for launching apps on the Virtual Display.
 * DesktopActivity (uid 10612) emits requests, MirrorForegroundService
 * collects them and delegates to Shizuku PrivilegedService (uid 2000).
 * No Android framework (Broadcast/Binder) needed — same process memory.
 */
object AppLaunchBus {
    data class LaunchRequest(
        val packageName: String,
        val className: String?,
        val isVideoApp: Boolean = false
    )

    private val _events = MutableSharedFlow<LaunchRequest>(extraBufferCapacity = 8)
    val events = _events.asSharedFlow()

    fun requestLaunch(packageName: String, className: String? = null, isVideoApp: Boolean = false) {
        _events.tryEmit(LaunchRequest(packageName, className, isVideoApp))
    }
}
