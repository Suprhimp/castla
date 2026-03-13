package com.jakarta.mirror.shizuku

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import android.view.Surface
import java.lang.reflect.Method

/**
 * Runs in Shizuku's elevated process — has system-level access.
 * Creates virtual displays and injects input events for the mirroring pipeline.
 */
class PrivilegedService : IPrivilegedService.Stub() {

    companion object {
        private const val TAG = "PrivilegedService"
        // VIRTUAL_DISPLAY_FLAG_PUBLIC: system renders home/launcher on this display
        private const val DISPLAY_FLAG_PUBLIC = 1 shl 0
    }

    private val virtualDisplays = mutableMapOf<Int, VirtualDisplay>()
    private var inputManagerInstance: Any? = null
    private var injectMethod: Method? = null

    init {
        tryInitInputManager()
    }

    private fun tryInitInputManager() {
        try {
            val imClass = Class.forName("android.hardware.input.InputManager")
            val getInstance = imClass.getMethod("getInstance")
            inputManagerInstance = getInstance.invoke(null)
            injectMethod = imClass.getMethod(
                "injectInputEvent",
                InputEvent::class.java,
                Int::class.javaPrimitiveType
            )
            Log.i(TAG, "InputManager initialized in privileged process")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init InputManager", e)
        }
    }

    override fun createVirtualDisplay(width: Int, height: Int, dpi: Int, name: String): Int {
        return try {
            // Use hidden DisplayManager API to create virtual display without Activity context
            val dmClass = Class.forName("android.hardware.display.DisplayManagerGlobal")
            val getInstance = dmClass.getMethod("getInstance")
            val dmInstance = getInstance.invoke(null)

            val createMethod = dmClass.getMethod(
                "createVirtualDisplay",
                android.content.Context::class.java,
                android.media.projection.MediaProjection::class.java,
                String::class.java,
                Int::class.javaPrimitiveType,     // width
                Int::class.javaPrimitiveType,     // height
                Int::class.javaPrimitiveType,     // dpi
                android.view.Surface::class.java,
                Int::class.javaPrimitiveType,     // flags
                android.hardware.display.VirtualDisplay.Callback::class.java,
                android.os.Handler::class.java,
                String::class.java                // uniqueId
            )

            val display = createMethod.invoke(
                dmInstance,
                null,    // context
                null,    // projection
                name,
                width,
                height,
                dpi,
                null,    // surface (set later)
                DISPLAY_FLAG_PUBLIC,
                null,    // callback
                null,    // handler
                null     // uniqueId
            ) as? VirtualDisplay

            if (display != null) {
                val displayId = display.display.displayId
                virtualDisplays[displayId] = display
                Log.i(TAG, "Virtual display created: id=$displayId, ${width}x${height}")
                displayId
            } else {
                Log.e(TAG, "createVirtualDisplay returned null")
                -1
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create virtual display", e)
            -1
        }
    }

    override fun setSurface(displayId: Int, surface: Surface?) {
        val display = virtualDisplays[displayId]
        if (display == null) {
            Log.w(TAG, "setSurface: no display with id=$displayId")
            return
        }
        display.surface = surface
        Log.i(TAG, "Surface attached to virtual display $displayId")
    }

    override fun releaseVirtualDisplay(displayId: Int) {
        virtualDisplays.remove(displayId)?.let {
            it.release()
            Log.i(TAG, "Virtual display released: id=$displayId")
        }
    }

    override fun injectInput(displayId: Int, action: Int, x: Float, y: Float, pointerId: Int) {
        val now = SystemClock.uptimeMillis()
        val properties = arrayOf(
            MotionEvent.PointerProperties().apply {
                id = pointerId
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        )
        val coords = arrayOf(
            MotionEvent.PointerCoords().apply {
                this.x = x
                this.y = y
                pressure = 1.0f
                size = 1.0f
            }
        )

        val event = MotionEvent.obtain(
            now, now, action, 1,
            properties, coords,
            0, 0, 1.0f, 1.0f,
            0, 0,
            InputDevice.SOURCE_TOUCHSCREEN,
            0
        )

        // Set display ID via reflection (hidden API)
        try {
            val setDisplayId = MotionEvent::class.java.getMethod(
                "setDisplayId", Int::class.javaPrimitiveType
            )
            setDisplayId.invoke(event, displayId)
        } catch (_: Exception) {
            // Fallback: use setSource with display info
        }

        try {
            injectMethod?.invoke(inputManagerInstance, event, 0) // INJECT_INPUT_EVENT_MODE_ASYNC
        } catch (e: Exception) {
            Log.e(TAG, "Input injection failed on display $displayId", e)
        } finally {
            event.recycle()
        }
    }

    override fun isAlive(): Boolean = true

    override fun destroy() {
        virtualDisplays.values.forEach { it.release() }
        virtualDisplays.clear()
        Log.i(TAG, "PrivilegedService destroyed")
    }
}
