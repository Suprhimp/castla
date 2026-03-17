package com.castla.mirror.input

import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
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
    private val pointerOrder = mutableListOf<Int>() // stable index for ACTION_POINTER_*

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
        pointerOrder.clear()
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
                activePointers[pointerId] = Pair(absX, absY)
                if (!pointerOrder.contains(pointerId)) {
                    pointerOrder.add(pointerId)
                }
            }
            "move" -> {
                if (!activePointers.containsKey(pointerId)) return // move without down — ignore
                activePointers[pointerId] = Pair(absX, absY)
            }
            "up" -> {
                if (!activePointers.containsKey(pointerId)) return // up without down — ignore
            }
            else -> return
        }

        val index = pointerOrder.indexOf(pointerId).coerceAtLeast(0)
        val action = when (event.action) {
            "down" -> {
                if (activePointers.size <= 1) MotionEvent.ACTION_DOWN
                else MotionEvent.ACTION_POINTER_DOWN or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
            }
            "move" -> MotionEvent.ACTION_MOVE
            "up" -> {
                if (activePointers.size <= 1) MotionEvent.ACTION_UP
                else MotionEvent.ACTION_POINTER_UP or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
            }
            else -> return
        }

        // Route to virtual display if active, otherwise use main screen injection
        val vdInjector = virtualDisplayInjector
        if (vdInjector != null) {
            Log.d(TAG, "Routing touch to VD: action=$action x=${"%.1f".format(absX)} y=${"%.1f".format(absY)}")
            vdInjector(action, absX, absY, pointerId)
        } else if (useShizuku) {
            Log.d(TAG, "Routing touch to InputManager (no VD injector)")
            injectViaInputManager(action, absX, absY, pointerId)
        } else {
            Log.d(TAG, "Routing touch to Accessibility (no Shizuku)")
            injectViaAccessibility(action, absX, absY)
        }

        // Remove pointer AFTER injection
        if (event.action == "up") {
            activePointers.remove(pointerId)
            pointerOrder.remove(pointerId)
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

        // Multi-pointer: build arrays from pointerOrder for stable index
        val pointerProperties = Array(pointerCount) { i ->
            MotionEvent.PointerProperties().apply {
                id = pointerOrder.getOrElse(i) { pointerId }
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }

        val pointerCoords = Array(pointerCount) { i ->
            MotionEvent.PointerCoords().apply {
                val pid = pointerOrder.getOrElse(i) { pointerId }
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

    /**
     * Inject text into the focused field using ACTION_MULTIPLE KeyEvent.
     * Works for all languages (Korean, Chinese, Japanese, emoji, etc.)
     * Falls back to clipboard+paste if ACTION_MULTIPLE fails.
     */
    fun injectText(text: String): Boolean {
        if (text.isEmpty()) return false

        val im = inputManagerInstance
        val method = injectMethod
        if (im == null || method == null) {
            Log.w(TAG, "InputManager not available for text injection")
            return false
        }

        try {
            // Method 1: ACTION_MULTIPLE — injects entire string as one event
            val time = SystemClock.uptimeMillis()
            val keyEvent = KeyEvent(time, text, KeyCharacterMap.VIRTUAL_KEYBOARD, 0)
            method.invoke(im, keyEvent, 0) // INJECT_INPUT_EVENT_MODE_ASYNC
            Log.i(TAG, "Text injected via ACTION_MULTIPLE: ${text.length} chars")
            return true
        } catch (e: Exception) {
            Log.w(TAG, "ACTION_MULTIPLE failed, trying clipboard fallback", e)
        }

        // Method 2: Clipboard + PASTE fallback
        try {
            return injectTextViaClipboard(text)
        } catch (e: Exception) {
            Log.e(TAG, "Clipboard text injection also failed", e)
            return false
        }
    }

    private fun injectTextViaClipboard(text: String): Boolean {
        val im = inputManagerInstance ?: return false
        val method = injectMethod ?: return false

        try {
            // Get IClipboard via ServiceManager
            val smClass = Class.forName("android.os.ServiceManager")
            val getService = smClass.getMethod("getService", String::class.java)
            val clipBinder = getService.invoke(null, "clipboard") as android.os.IBinder

            val clipStubClass = Class.forName("android.content.IClipboard\$Stub")
            val asInterface = clipStubClass.getMethod("asInterface", android.os.IBinder::class.java)
            val clipService = asInterface.invoke(null, clipBinder)

            // setPrimaryClip(ClipData, String packageName, String attributionTag, int userId)
            val clipData = android.content.ClipData.newPlainText("castla", text)
            val setPrimary = clipService.javaClass.methods.find { it.name == "setPrimaryClip" }
            if (setPrimary != null) {
                val params = setPrimary.parameterTypes
                val args = when (params.size) {
                    4 -> arrayOf(clipData, "com.android.shell", null, 0)
                    3 -> arrayOf(clipData, "com.android.shell", 0)
                    2 -> arrayOf(clipData, "com.android.shell")
                    else -> arrayOf(clipData)
                }
                setPrimary.invoke(clipService, *args)
            }

            // Small delay for clipboard to settle
            Thread.sleep(50)

            // Send PASTE keyevent
            val time = SystemClock.uptimeMillis()
            val pasteDown = KeyEvent(time, time, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_PASTE, 0)
            val pasteUp = KeyEvent(time, time, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_PASTE, 0)
            method.invoke(im, pasteDown, 0)
            method.invoke(im, pasteUp, 0)

            Log.i(TAG, "Text injected via clipboard+paste: ${text.length} chars")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Clipboard injection failed", e)
            return false
        }
    }

    fun release() {
        activePointers.clear()
        pointerOrder.clear()
        inputManagerInstance = null
        injectMethod = null
    }
}
