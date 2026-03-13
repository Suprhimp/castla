package com.jakarta.mirror.input

import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import com.jakarta.mirror.server.MirrorServer
import java.lang.reflect.Method

/**
 * Injects touch events into the Android input system.
 * Uses Shizuku's InputManager when available, falls back to Accessibility Service.
 */
class TouchInjector(
    private val displayWidth: Int,
    private val displayHeight: Int
) {
    companion object {
        private const val TAG = "TouchInjector"
        private const val INJECT_INPUT_EVENT_MODE_ASYNC = 0
    }

    private val activePointers = mutableMapOf<Int, Pair<Float, Float>>()

    // Shizuku-based IInputManager
    private var inputManagerInstance: Any? = null
    private var injectMethod: Method? = null
    private var useShizuku = false

    // Track gesture state for Accessibility fallback
    private var lastDownX = 0f
    private var lastDownY = 0f
    private var downTime = 0L

    init {
        tryInitShizukuInputManager()
    }

    private fun tryInitShizukuInputManager() {
        try {
            // Get InputManager via hidden API (requires Shizuku elevated privileges)
            val imClass = Class.forName("android.hardware.input.InputManager")
            val getInstance = imClass.getMethod("getInstance")
            inputManagerInstance = getInstance.invoke(null)
            injectMethod = imClass.getMethod("injectInputEvent", android.view.InputEvent::class.java, Int::class.javaPrimitiveType)
            useShizuku = true
            Log.i(TAG, "Shizuku InputManager initialized")
        } catch (e: Exception) {
            Log.w(TAG, "Shizuku InputManager unavailable, using Accessibility fallback", e)
            useShizuku = false
        }
    }

    fun onTouchEvent(event: MirrorServer.TouchEvent) {
        val absX = event.x * displayWidth
        val absY = event.y * displayHeight

        val action = when (event.action) {
            "down" -> MotionEvent.ACTION_DOWN
            "move" -> MotionEvent.ACTION_MOVE
            "up" -> MotionEvent.ACTION_UP
            else -> return
        }

        activePointers[event.pointerId] = Pair(absX, absY)

        if (action == MotionEvent.ACTION_UP) {
            activePointers.remove(event.pointerId)
        }

        if (useShizuku) {
            injectViaInputManager(action, absX, absY, event.pointerId)
        } else {
            injectViaAccessibility(action, absX, absY)
        }
    }

    private fun injectViaInputManager(action: Int, x: Float, y: Float, pointerId: Int) {
        val motionEvent = createMotionEvent(action, x, y, pointerId)
        try {
            injectMethod?.invoke(inputManagerInstance, motionEvent, INJECT_INPUT_EVENT_MODE_ASYNC)
        } catch (e: Exception) {
            Log.e(TAG, "InputManager inject failed", e)
            // Fall back to accessibility on failure
            useShizuku = false
            injectViaAccessibility(action, x, y)
        } finally {
            motionEvent.recycle()
        }
    }

    private fun injectViaAccessibility(action: Int, x: Float, y: Float) {
        val service = InputService.instance
        if (service == null) {
            Log.w(TAG, "Accessibility service not connected, touch dropped")
            return
        }

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                lastDownX = x
                lastDownY = y
                downTime = SystemClock.uptimeMillis()
            }
            MotionEvent.ACTION_UP -> {
                val dx = Math.abs(x - lastDownX)
                val dy = Math.abs(y - lastDownY)
                val duration = SystemClock.uptimeMillis() - downTime

                if (dx < 20f && dy < 20f && duration < 300) {
                    // Short, stationary — tap
                    service.tap(x, y)
                } else {
                    // Movement — swipe
                    service.swipe(lastDownX, lastDownY, x, y, duration.coerceAtLeast(100))
                }
            }
            // ACTION_MOVE: accumulated for swipe end detection, no immediate dispatch needed
        }
    }

    private fun createMotionEvent(action: Int, x: Float, y: Float, pointerId: Int): MotionEvent {
        val now = SystemClock.uptimeMillis()

        val pointerCount = activePointers.size.coerceAtLeast(1)
        val pointerProperties = Array(pointerCount) { i ->
            MotionEvent.PointerProperties().apply {
                id = if (i == 0) pointerId else activePointers.keys.elementAtOrNull(i) ?: 0
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }

        val pointerCoords = Array(pointerCount) { i ->
            MotionEvent.PointerCoords().apply {
                if (i == 0) {
                    this.x = x
                    this.y = y
                } else {
                    val key = activePointers.keys.elementAtOrNull(i)
                    val pos = key?.let { activePointers[it] }
                    this.x = pos?.first ?: x
                    this.y = pos?.second ?: y
                }
                pressure = 1.0f
                size = 1.0f
            }
        }

        return MotionEvent.obtain(
            now,       // downTime
            now,       // eventTime
            action,
            pointerCount,
            pointerProperties,
            pointerCoords,
            0,         // metaState
            0,         // buttonState
            1.0f,      // xPrecision
            1.0f,      // yPrecision
            0,         // deviceId
            0,         // edgeFlags
            InputDevice.SOURCE_TOUCHSCREEN,
            0          // flags
        )
    }

    fun release() {
        activePointers.clear()
        inputManagerInstance = null
        injectMethod = null
    }
}
