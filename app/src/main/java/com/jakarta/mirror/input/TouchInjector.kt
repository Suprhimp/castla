package com.jakarta.mirror.input

import android.os.SystemClock
import android.util.Log
import android.view.InputEvent
import android.view.MotionEvent
import com.jakarta.mirror.server.MirrorServer

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
    }

    // Track active pointers for multi-touch
    private val activePointers = mutableMapOf<Int, Pair<Float, Float>>()
    private var inputManager: Any? = null // IInputManager via Shizuku, set later

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

        val motionEvent = createMotionEvent(action, absX, absY, event.pointerId)
        injectEvent(motionEvent)
        motionEvent.recycle()
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
            0,         // source (touchscreen)
            0          // flags
        )
    }

    private fun injectEvent(event: MotionEvent) {
        // TODO: Use Shizuku InputManager for injection
        // For now, log the event (Phase 5 will add Shizuku integration)
        Log.d(TAG, "Inject: action=${event.action} x=${event.x} y=${event.y}")
    }

    fun release() {
        activePointers.clear()
    }
}
