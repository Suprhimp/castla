package com.castla.mirror.capture

import android.content.ComponentName
import android.content.ServiceConnection
import android.hardware.display.VirtualDisplay
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Surface
import com.castla.mirror.shizuku.IPrivilegedService
import com.castla.mirror.shizuku.PrivilegedService
import com.castla.mirror.shizuku.ShizukuSetup
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
    private var bindingInProgress = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var serviceConnection: ServiceConnection? = null
    private var userServiceArgs: Shizuku.UserServiceArgs? = null

    private fun runOnMainSync(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
            return
        }
        val latch = CountDownLatch(1)
        var error: Throwable? = null
        mainHandler.post {
            try {
                block()
            } catch (t: Throwable) {
                error = t
            } finally {
                latch.countDown()
            }
        }
        latch.await(2, TimeUnit.SECONDS)
        error?.let { throw it }
    }

    /** Called when Shizuku service reconnects after a death — caller should recreate VD + launch home */
    var reconnectListener: (() -> Unit)? = null

    /** Expose the privileged service for IME checks (avoids separate binder connection) */
    fun getPrivilegedService(): IPrivilegedService? = privilegedService

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
        if (bindingInProgress) {
            Log.d(TAG, "bindShizukuService skipped: bind already in progress")
            return true
        }

        return try {
            bindingInProgress = true
            val args = Shizuku.UserServiceArgs(
                ComponentName(
                    com.castla.mirror.BuildConfig.APPLICATION_ID,
                    PrivilegedService::class.java.name
                )
            )
                .daemon(false)
                .processNameSuffix("privileged")
                .debuggable(true)
                .version(ShizukuSetup.USER_SERVICE_VERSION)
            userServiceArgs = args

            var callbackFired = false
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    privilegedService = IPrivilegedService.Stub.asInterface(binder)
                    bindingInProgress = false
                    
                    try {
                        privilegedService?.registerDeathToken(android.os.Binder())
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to register death token", e)
                    }

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
                    bindingInProgress = false
                    displayId = -1
                    Log.i(TAG, "Shizuku privileged service disconnected")
                }
            }
            serviceConnection = connection

            runOnMainSync { Shizuku.bindUserService(args, connection) }
            true
        } catch (e: Exception) {
            bindingInProgress = false
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
    
    /** Creates an additional virtual display for dual-screen scenarios */
    fun createSecondaryVirtualDisplay(width: Int, height: Int, dpi: Int, surface: Surface): Int {
        val service = privilegedService
        if (service == null) return -1
        
        return try {
            val id = service.createVirtualDisplay(width, height, dpi, "Castla_Sec")
            if (id >= 0) {
                service.setSurface(id, surface)
                id
            } else -1
        } catch (e: Exception) {
            -1
        }
    }
    
    fun releaseSecondaryVirtualDisplay(id: Int) {
        try {
            privilegedService?.releaseVirtualDisplay(id)
        } catch (e: Exception) {}
    }
    
    fun launchAppOnSpecificDisplay(targetDisplayId: Int, packageName: String) {
        try {
            privilegedService?.launchAppOnDisplay(targetDisplayId, packageName)
        } catch (e: Exception) {}
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

    /**
     * Force the virtual display to stay awake/unlocked when the physical screen turns off.
     * Uses PowerManager internal APIs via Shizuku to wake the display and inject user activity.
     */
    fun keepDisplayAwake() {
        val id = displayId
        if (id < 0) return
        try {
            privilegedService?.wakeUpDisplay(id)
            Log.i(TAG, "Forced VD $id display state to ON")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to force VD awake", e)
        }
    }

    /**
     * Turn the physical display panel on/off via SurfaceControl (scrcpy approach).
     * When off, device stays awake and VD keeps rendering. Physical screen goes dark.
     * @return true if the call succeeded, false on error or no service
     */
    fun setPhysicalDisplayPower(on: Boolean): Boolean {
        return try {
            val svc = privilegedService ?: run {
                Log.w(TAG, "setPhysicalDisplayPower: no privileged service")
                return false
            }
            svc.setPhysicalDisplayPower(on)
            Log.i(TAG, "Physical display power: ${if (on) "ON" else "OFF"}")
            true
        } catch (e: Exception) {
            Log.w(TAG, "setPhysicalDisplayPower failed", e)
            false
        }
    }

    /** Returns true if the Shizuku service is bound (even if no VD is active). */
    fun isBound(): Boolean = isBound && privilegedService != null

    /** Display ID of the Shizuku-created virtual display, or -1. */
    fun getDisplayId(): Int = displayId

    /** Returns true if a Shizuku virtual display is active. */
    fun hasVirtualDisplay(): Boolean = displayId >= 0 && privilegedService != null

    /** Resize a virtual display by ID without destroying it. */
    fun resizeDisplay(displayId: Int, width: Int, height: Int, dpi: Int): Boolean {
        if (displayId < 0) return false
        return try {
            privilegedService?.resizeVirtualDisplay(displayId, width, height, dpi)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resize VD $displayId", e)
            false
        }
    }

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
            try { runOnMainSync { Shizuku.unbindUserService(args, conn, true) } } catch (_: Exception) {}
        }

        privilegedService = null
        isBound = false
        bindingInProgress = false
        serviceConnection = null
        userServiceArgs = null
        virtualDisplay?.release()
        virtualDisplay = null
        displayId = -1
    }
}
