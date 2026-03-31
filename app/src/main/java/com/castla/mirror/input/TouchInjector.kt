package com.castla.mirror.input

import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import com.castla.mirror.server.TouchEvent

class TouchInjector(private var displayWidth: Int, private var displayHeight: Int) {

    companion object {
        private const val TAG = "TouchInjector"
        private const val MAX_POINTERS = 10
    }

    private var inputManagerInstance: Any? = null
    private var injectMethod: java.lang.reflect.Method? = null
    
    // Callback to let VirtualDisplayManager handle injection (since it has the display ID)
    private var virtualDisplayInjector: ((Int, Float, Float, Int) -> Unit)? = null

    private data class PointerState(var x: Float, var y: Float)
    private val activePointers = mutableMapOf<Int, PointerState>()
    private val pointerOrder = mutableListOf<Int>() // Maintains stable order of pointer IDs

    // Cached objects to avoid allocation on every frame
    private val pointerProperties = Array(MAX_POINTERS) { MotionEvent.PointerProperties() }
    private val pointerCoords = Array(MAX_POINTERS) { MotionEvent.PointerCoords() }

    init {
        tryInitShizuku()
    }

    fun updateDimensions(width: Int, height: Int) {
        displayWidth = width
        displayHeight = height
        activePointers.clear()
        pointerOrder.clear()
        Log.i(TAG, "TouchInjector dimensions updated: ${width}x${height}")
    }

    /** Set callback to inject touch directly onto the VirtualDisplay via Shizuku */
    fun setVirtualDisplayInjector(injector: ((Int, Float, Float, Int) -> Unit)?) {
        virtualDisplayInjector = injector
    }

    private fun tryInitShizuku() {
        try {
            val imClass = Class.forName("android.hardware.input.InputManager")
            val getInstance = imClass.getMethod("getInstance")
            inputManagerInstance = getInstance.invoke(null)
            injectMethod = imClass.getMethod("injectInputEvent", android.view.InputEvent::class.java, Int::class.javaPrimitiveType)
            Log.i(TAG, "Shizuku InputManager initialized")
        } catch (e: Exception) {
            Log.w(TAG, "Shizuku InputManager unavailable", e)
        }
    }

    fun onTouchEvent(event: TouchEvent) {
        val absX = event.x * displayWidth
        val absY = event.y * displayHeight
        val pointerId = event.pointerId

        val beforeCount = activePointers.size

        // Update pointer state BEFORE computing action
        when (event.action) {
            "down" -> {
                // Clear stale pointers from lost "up" events before starting new gesture
                if (activePointers.isNotEmpty() && !activePointers.containsKey(pointerId)) {
                    activePointers.clear()
                    pointerOrder.clear()
                }
                activePointers[pointerId] = PointerState(absX, absY)
                if (!pointerOrder.contains(pointerId)) {
                    pointerOrder.add(pointerId)
                }
            }
            "move" -> {
                activePointers[pointerId]?.let {
                    it.x = absX
                    it.y = absY
                } ?: run {
                    // Implicit down if we missed it
                    activePointers[pointerId] = PointerState(absX, absY)
                    pointerOrder.add(pointerId)
                }
            }
            "up" -> {
                // Keep the pointer in state for the UP event generation,
                // we will remove it AFTER the injection.
                activePointers[pointerId]?.let {
                    it.x = absX
                    it.y = absY
                }
            }
        }

        val afterCount = activePointers.size
        if (afterCount == 0) return

        // If we have a virtual display injector, use it (Shizuku + display ID)
        if (virtualDisplayInjector != null) {
            val actionCode = when (event.action) {
                "down" -> MotionEvent.ACTION_DOWN
                "up" -> MotionEvent.ACTION_UP
                "move" -> MotionEvent.ACTION_MOVE
                else -> -1
            }
            if (actionCode >= 0) {
                virtualDisplayInjector?.invoke(actionCode, absX, absY, pointerId)
            }
            // Remove pointer AFTER injection
            if (event.action == "up") {
                activePointers.remove(pointerId)
                pointerOrder.remove(pointerId)
            }
            return
        }

        // Fallback: MediaProjection screen mirroring (main display)
        injectViaShizuku(event, beforeCount, afterCount, pointerId)

        // Remove pointer AFTER injection
        if (event.action == "up") {
            activePointers.remove(pointerId)
            pointerOrder.remove(pointerId)
        }
    }

    private fun injectViaShizuku(event: TouchEvent, beforeCount: Int, afterCount: Int, targetPointerId: Int) {
        val now = SystemClock.uptimeMillis()

        // 1. Map active pointers to array indices using stable order
        var targetIndex = 0
        for (i in 0 until afterCount) {
            val pid = pointerOrder[i]
            val state = activePointers[pid]!!
            
            pointerProperties[i].apply {
                id = pid
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
            pointerCoords[i].apply {
                x = state.x
                y = state.y
                pressure = 1.0f
                size = 1.0f
            }
            
            if (pid == targetPointerId) {
                targetIndex = i
            }
        }

        // 2. Compute MotionEvent action
        val action = when (event.action) {
            "down" -> {
                if (beforeCount == 0) MotionEvent.ACTION_DOWN
                else MotionEvent.ACTION_POINTER_DOWN or (targetIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
            }
            "up" -> {
                if (afterCount == 1) MotionEvent.ACTION_UP
                else MotionEvent.ACTION_POINTER_UP or (targetIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
            }
            "move" -> MotionEvent.ACTION_MOVE
            else -> return
        }

        // 3. Create and inject event
        val motionEvent = MotionEvent.obtain(
            now, now, action, afterCount,
            pointerProperties, pointerCoords,
            0, 0, 1.0f, 1.0f, 0, 0,
            InputDevice.SOURCE_TOUCHSCREEN, 0
        )

        try {
            injectMethod?.invoke(inputManagerInstance, motionEvent, 0) // 0 = INJECT_INPUT_EVENT_MODE_ASYNC
        } catch (e: Exception) {
            Log.e(TAG, "Failed to inject event", e)
        } finally {
            motionEvent.recycle()
        }
    }

    fun release() {
        activePointers.clear()
        pointerOrder.clear()
    }
}
