package com.castla.mirror.input

import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import com.castla.mirror.server.MirrorServer
import java.lang.reflect.Method

/**
 * Injects touch events into the Android input system.
 * Uses Shizuku's InputManager when available, falls back to Accessibility Service.
 */
class TouchInjector(
    private var displayWidth: Int,
    private var displayHeight: Int
) {
    companion object {
        private const val TAG = "TouchInjector"
        private const val INJECT_INPUT_EVENT_MODE_ASYNC = 0
    }

    private val activePointers = mutableMapOf<Int, Pair<Float, Float>>()

    // Cached objects for single-pointer events (most common case) — avoids GC pressure
    private val singleProps = arrayOf(
        MotionEvent.PointerProperties().apply { toolType = MotionEvent.TOOL_TYPE_FINGER }
    )
    private val singleCoords = arrayOf(
        MotionEvent.PointerCoords().apply { pressure = 1.0f; size = 1.0f }
    )

    // Shizuku-based IInputManager
    private var inputManagerInstance: Any? = null
    private var injectMethod: Method? = null
    private var useShizuku = false

    // Virtual display routing (Phase 5)
    private var virtualDisplayInjector: ((Int, Float, Float, Int) -> Unit)? = null

    // Track gesture state for Accessibility fallback
    private var lastDownX = 0f
    private var lastDownY = 0f
    private var downTime = 0L

    init {
        tryInitShizukuInputManager()
    }

    /**
     * Update the display dimensions used for touch coordinate scaling.
     * Clears active pointers to avoid stale state after a resize.
     */
    fun updateDimensions(width: Int, height: Int) {
        displayWidth = width
        displayHeight = height
        activePointers.clear()
    }

    /**
     * Set a virtual display input injector.
     * When set, touch events are routed to the virtual display instead of the main screen.
     */
    fun setVirtualDisplayInjector(injector: ((Int, Float, Float, Int) -> Unit)?) {
        virtualDisplayInjector = injector
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

        val action: Int
        when (event.action) {
            "down" -> {
                if (activePointers.isEmpty()) {
                    // First finger — primary pointer down
                    action = MotionEvent.ACTION_DOWN
                } else {
                    // Additional finger — secondary pointer down
                    // Must encode pointer index in upper bits
                    activePointers[event.pointerId] = Pair(absX, absY)
                    val pointerIndex = activePointers.keys.toList().indexOf(event.pointerId)
                    action = MotionEvent.ACTION_POINTER_DOWN or (pointerIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
                }
                activePointers[event.pointerId] = Pair(absX, absY)
            }
            "move" -> {
                activePointers[event.pointerId] = Pair(absX, absY)
                action = MotionEvent.ACTION_MOVE
            }
            "up" -> {
                if (activePointers.size <= 1) {
                    // Last finger — primary pointer up
                    action = MotionEvent.ACTION_UP
                } else {
                    // Non-last finger — secondary pointer up
                    val pointerIndex = activePointers.keys.toList().indexOf(event.pointerId)
                    action = MotionEvent.ACTION_POINTER_UP or (pointerIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
                }
                activePointers.remove(event.pointerId)
            }
            else -> return
        }

        // Route to virtual display if active, otherwise use main screen injection
        val vdInjector = virtualDisplayInjector
        if (vdInjector != null) {
            vdInjector(action, absX, absY, event.pointerId)
        } else if (useShizuku) {
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

        // Fast path: single pointer — reuse cached arrays
        if (pointerCount == 1) {
            singleProps[0].id = pointerId
            singleCoords[0].x = x
            singleCoords[0].y = y

            return MotionEvent.obtain(
                now, now, action, 1,
                singleProps, singleCoords,
                0, 0, 1.0f, 1.0f, 0, 0,
                InputDevice.SOURCE_TOUCHSCREEN, 0
            )
        }

        // Multi-pointer: build arrays from activePointers map in stable order
        val pointerIds = activePointers.keys.toList()
        val pointerProperties = Array(pointerCount) { i ->
            MotionEvent.PointerProperties().apply {
                id = pointerIds.getOrElse(i) { 0 }
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }

        val pointerCoords = Array(pointerCount) { i ->
            MotionEvent.PointerCoords().apply {
                val pid = pointerIds.getOrElse(i) { -1 }
                val pos = activePointers[pid]
                this.x = pos?.first ?: x
                this.y = pos?.second ?: y
                pressure = 1.0f
                size = 1.0f
            }
        }

        return MotionEvent.obtain(
            now, now, action, pointerCount,
            pointerProperties, pointerCoords,
            0, 0, 1.0f, 1.0f, 0, 0,
            InputDevice.SOURCE_TOUCHSCREEN, 0
        )
    }

    fun release() {
        activePointers.clear()
        inputManagerInstance = null
        injectMethod = null
    }
}
