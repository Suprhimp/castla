package com.castla.mirror.capture

import android.content.ComponentName
import android.content.ServiceConnection
import android.hardware.display.VirtualDisplay
import android.os.IBinder
import android.util.Log
import android.view.Surface
import com.castla.mirror.shizuku.IPrivilegedService
import com.castla.mirror.shizuku.PrivilegedService
import rikka.shizuku.Shizuku

/**
 * Creates a virtual display using Shizuku for independent phone + Tesla operation.
 * When a virtual display is active, the phone screen operates independently while
 * Tesla shows content on the virtual display.
 */
class VirtualDisplayManager {

    companion object {
        private const val TAG = "VirtualDisplayManager"
    }

    private var virtualDisplay: VirtualDisplay? = null
    private var privilegedService: IPrivilegedService? = null
    private var displayId: Int = -1
    private var isBound = false
    private var serviceConnection: ServiceConnection? = null
    private var userServiceArgs: Shizuku.UserServiceArgs? = null

    /** Called when Shizuku service reconnects after a death — caller should recreate VD + launch home */
    var reconnectListener: (() -> Unit)? = null

    /**
     * Bind to the Shizuku privileged service.
     * Must be called before createVirtualDisplay when using Shizuku mode.
     * Returns true if binding was initiated.
     */
    fun bindShizukuService(callback: (Boolean) -> Unit): Boolean {
        if (isBound && privilegedService != null) {
            callback(true)
            return true
        }

        return try {
            val args = Shizuku.UserServiceArgs(
                ComponentName(
                    "com.castla.mirror",
                    PrivilegedService::class.java.name
                )
            )
                .daemon(false)
                .processNameSuffix("privileged")
                .debuggable(true)
                .version(2)
            userServiceArgs = args

            var callbackFired = false
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    privilegedService = IPrivilegedService.Stub.asInterface(binder)
                    isBound = true
                    Log.i(TAG, "Shizuku privileged service connected")
                    if (!callbackFired) {
                        callbackFired = true
                        callback(true)
                    } else {
                        // Reconnect after service death — any previous displayId is now stale because
                        // PrivilegedService keeps the VirtualDisplay map in-process.
                        virtualDisplay = null
                        displayId = -1
                        Log.i(TAG, "Shizuku service reconnected (was dead), notifying listener")
                        reconnectListener?.invoke()
                    }
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    privilegedService = null
                    isBound = false
                    displayId = -1
                    Log.i(TAG, "Shizuku privileged service disconnected")
                }
            }
            serviceConnection = connection

            Shizuku.bindUserService(args, connection)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to bind Shizuku service", e)
            callback(false)
            false
        }
    }

    /**
     * Create a virtual display via Shizuku's elevated privileges.
     * If Shizuku is not available, returns null — caller should fall back
     * to MediaProjection's built-in VirtualDisplay.
     */
    fun createVirtualDisplay(
        width: Int,
        height: Int,
        dpi: Int,
        surface: Surface
    ): VirtualDisplay? {
        if (width <= 0 || height <= 0) {
            Log.w(TAG, "Invalid dimensions: ${width}x${height}")
            return null
        }

        val service = privilegedService
        if (service == null) {
            Log.i(TAG, "Shizuku service not bound, cannot create virtual display")
            return null
        }

        return try {
            val id = service.createVirtualDisplay(width, height, dpi, "Castla")
            if (id >= 0) {
                // Attach the encoder's Surface so VD content renders into the encoder
                try {
                    service.setSurface(id, surface)
                } catch (e: Exception) {
                    Log.e(TAG, "setSurface failed, releasing VD", e)
                    service.releaseVirtualDisplay(id)
                    displayId = -1
                    return null
                }
                displayId = id
                Log.i(TAG, "Virtual display created via Shizuku: id=$id, ${width}x${height}, surface attached")
                null
            } else {
                Log.e(TAG, "Shizuku returned invalid display ID")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create virtual display via Shizuku", e)
            displayId = -1
            null
        }
    }
    
    fun setSurface(surface: Surface) {
        if (displayId >= 0 && privilegedService != null) {
            try {
                privilegedService?.setSurface(displayId, surface)
                Log.i(TAG, "Surface updated on Virtual Display $displayId")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update surface on VD", e)
            }
        }
    }

    /** Returns true if the Shizuku service is bound (even if no VD is active). */
    fun isBound(): Boolean = isBound && privilegedService != null

    /** Display ID of the Shizuku-created virtual display, or -1. */
    fun getDisplayId(): Int = displayId

    /** Returns true if a Shizuku virtual display is active. */
    fun hasVirtualDisplay(): Boolean = displayId >= 0 && privilegedService != null

    /** Inject a touch event on the virtual display. */
    fun injectInput(action: Int, x: Float, y: Float, pointerId: Int) {
        if (displayId < 0) {
            Log.w(TAG, "injectInput skipped: displayId=$displayId")
            return
        }
        val svc = privilegedService
        if (svc == null) {
            Log.w(TAG, "injectInput skipped: privilegedService is null")
            return
        }
        try {
            svc.injectInput(displayId, action, x, y, pointerId)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to inject input on display $displayId", e)
        }
    }

    /** Launch the home screen on the virtual display. */
    fun launchHomeOnDisplay(): Boolean {
        if (displayId < 0) return false
        return try {
            privilegedService?.launchHomeOnDisplay(displayId)
            Log.i(TAG, "Launched HOME on virtual display $displayId")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch HOME on virtual display", e)
            false
        }
    }

    /** Launch an app on the virtual display. */
    fun launchAppOnDisplay(packageName: String): Boolean {
        if (displayId < 0 || packageName.isEmpty()) return false
        return try {
            privilegedService?.launchAppOnDisplay(displayId, packageName)
            Log.i(TAG, "Launched $packageName on virtual display $displayId")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch $packageName on virtual display", e)
            false
        }
    }
    
    /** Launch an app on the virtual display with string intent extra. */
    fun launchAppWithExtraOnDisplay(packageName: String, extraKey: String, extraValue: String): Boolean {
        if (displayId < 0 || packageName.isEmpty()) return false
        return try {
            privilegedService?.launchAppWithExtraOnDisplay(displayId, packageName, extraKey, extraValue)
            Log.i(TAG, "Launched $packageName with extra on virtual display $displayId")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch $packageName on virtual display", e)
            false
        }
    }

    /**
     * Release just the virtual display, keeping the Shizuku service bound.
     * Use this when rebuilding the pipeline with new dimensions.
     */
    fun releaseVirtualDisplay() {
        if (displayId >= 0) {
            try {
                privilegedService?.releaseVirtualDisplay(displayId)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to release virtual display", e)
            }
        }
        virtualDisplay?.release()
        virtualDisplay = null
        displayId = -1
    }

    fun release() {
        if (displayId >= 0) {
            try {
                privilegedService?.releaseVirtualDisplay(displayId)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to release virtual display", e)
            }
        }
        try {
            privilegedService?.destroy()
        } catch (_: Exception) {}

        val args = userServiceArgs
        val conn = serviceConnection
        if (args != null && conn != null) {
            try { Shizuku.unbindUserService(args, conn, true) } catch (_: Exception) {}
        }

        privilegedService = null
        virtualDisplay?.release()
        virtualDisplay = null
        displayId = -1
        isBound = false
        serviceConnection = null
        userServiceArgs = null
    }
}
