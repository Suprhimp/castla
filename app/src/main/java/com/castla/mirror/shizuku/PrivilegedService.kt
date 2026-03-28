package com.castla.mirror.shizuku

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
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
        // FLAG_PUBLIC ensures the virtual display behaves like a real display and allows home/launcher to render
        private const val DISPLAY_FLAG_PUBLIC = 1 shl 0
        // FLAG_OWN_CONTENT_ONLY prevents the main display's content from leaking into the VD
        private const val DISPLAY_FLAG_OWN_CONTENT_ONLY = 1 shl 3
        // FLAG_PRESENTATION tells the system this is a presentation display, which helps keeping it alive
        private const val DISPLAY_FLAG_PRESENTATION = 1 shl 1
        // FLAG_ALWAYS_UNLOCKED (API 33+) prevents the VD from locking when the physical screen locks
        private const val DISPLAY_FLAG_ALWAYS_UNLOCKED = 1 shl 12
        // FLAG_TRUSTED makes the system treat this VD as a trusted display (needed for some system UI)
        private const val DISPLAY_FLAG_TRUSTED = 1 shl 10
    }

    private val virtualDisplays = mutableMapOf<Int, VirtualDisplay>()
    private val virtualDisplayNames = mutableMapOf<Int, String>()
    private var inputManagerInstance: Any? = null
    private var injectMethod: Method? = null
    private var shellContext: android.content.Context? = null

    // Cached objects for injectInput — avoids allocation per touch event
    private val cachedProps = arrayOf(
        MotionEvent.PointerProperties().apply { toolType = MotionEvent.TOOL_TYPE_FINGER }
    )
    private val cachedCoords = arrayOf(
        MotionEvent.PointerCoords().apply { pressure = 1.0f; size = 1.0f }
    )
    private var setDisplayIdMethod: Method? = null

    init {
        tryInitInputManager()
        tryInitShellContext()
    }

    private fun tryInitShellContext() {
        try {
            if (android.os.Looper.myLooper() == null) {
                android.os.Looper.prepare()
            }
            val atClass = Class.forName("android.app.ActivityThread")
            val at = try {
                atClass.getMethod("currentActivityThread").invoke(null)
            } catch (_: Exception) {
                atClass.getMethod("systemMain").invoke(null)
            }
            val systemContext = atClass.getMethod("getSystemContext").invoke(at) as android.content.Context

            // Wrap with "com.android.shell" package name to match Shizuku uid 2000
            shellContext = object : android.content.ContextWrapper(systemContext) {
                override fun getPackageName(): String = "com.android.shell"
                override fun getOpPackageName(): String = "com.android.shell"
                override fun getAttributionTag(): String? = null
            }
            Log.i(TAG, "Shell context initialized: pkg=${shellContext?.packageName}")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to init shell context", e)
        }
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
        val existingDisplayIds = virtualDisplayNames
            .filterValues { it == name }
            .keys
            .toList()
        if (existingDisplayIds.isNotEmpty()) {
            Log.i(TAG, "Releasing ${existingDisplayIds.size} existing VD(s) for name=$name before recreating")
            existingDisplayIds.forEach { displayId ->
                virtualDisplays.remove(displayId)?.let { vd ->
                    try { vd.release() } catch (_: Exception) {}
                }
                virtualDisplayNames.remove(displayId)
            }
        }

        return try {
            val ctx = shellContext
            if (ctx == null) {
                Log.e(TAG, "Shell context not initialized")
                return -1
            }

            val configClass = Class.forName("android.hardware.display.VirtualDisplayConfig")
            val builderClass = Class.forName("android.hardware.display.VirtualDisplayConfig\$Builder")

            // Critical fix for screen off issue:
            // PUBLIC + PRESENTATION + OWN_CONTENT_ONLY treats VD as external monitor.
            // ALWAYS_UNLOCKED (API 33+) prevents VD from entering lock state when phone locks.
            // TRUSTED allows system UI to render normally on the VD.
            var flags = DISPLAY_FLAG_PUBLIC or DISPLAY_FLAG_OWN_CONTENT_ONLY or DISPLAY_FLAG_PRESENTATION
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                flags = flags or DISPLAY_FLAG_ALWAYS_UNLOCKED or DISPLAY_FLAG_TRUSTED
            }

            val builderCtor = builderClass.getConstructor(
                String::class.java, Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType
            )
            val builder = builderCtor.newInstance(name, width, height, dpi)
            builderClass.getMethod("setFlags", Int::class.javaPrimitiveType).invoke(builder, flags)
            val config = builderClass.getMethod("build").invoke(builder)

            val dmgClass = Class.forName("android.hardware.display.DisplayManagerGlobal")
            val dmg = dmgClass.getMethod("getInstance").invoke(null)

            val createMethod = dmgClass.declaredMethods.first { m ->
                m.name == "createVirtualDisplay" &&
                m.parameterTypes.any { it == configClass }
            }
            createMethod.isAccessible = true

            val params = createMethod.parameterTypes
            val args = arrayOfNulls<Any>(params.size)
            for (i in params.indices) {
                when {
                    params[i] == configClass -> args[i] = config
                    params[i] == android.content.Context::class.java -> args[i] = ctx
                }
            }

            val display = createMethod.invoke(dmg, *args) as? VirtualDisplay

            if (display != null) {
                val displayId = display.display.displayId
                virtualDisplays[displayId] = display
                virtualDisplayNames[displayId] = name
                Log.i(TAG, "Virtual display created: id=$displayId, ${width}x${height}, flags=$flags")
                
                // Keep the display explicitly powered on
                execCommand("dumpsys power set-display-state $displayId ON")

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
            virtualDisplayNames.remove(displayId)
            it.release()
            Log.i(TAG, "Virtual display released: id=$displayId")
        }
    }

    override fun injectInput(displayId: Int, action: Int, x: Float, y: Float, pointerId: Int) {
        val now = SystemClock.uptimeMillis()

        cachedProps[0].id = pointerId
        cachedCoords[0].x = x
        cachedCoords[0].y = y

        val event = MotionEvent.obtain(
            now, now, action, 1,
            cachedProps, cachedCoords,
            0, 0, 1.0f, 1.0f,
            0, 0,
            InputDevice.SOURCE_TOUCHSCREEN,
            0
        )

        try {
            if (setDisplayIdMethod == null) {
                setDisplayIdMethod = MotionEvent::class.java.getMethod(
                    "setDisplayId", Int::class.javaPrimitiveType
                )
            }
            setDisplayIdMethod?.invoke(event, displayId)
        } catch (_: Exception) {}

        try {
            val result = injectMethod?.invoke(inputManagerInstance, event, 0)
        } catch (e: Exception) {
            Log.e(TAG, "Input injection failed on display $displayId", e)
        } finally {
            event.recycle()
        }
    }

    override fun execCommand(command: String): String {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val output = process.inputStream.bufferedReader().readText()
            val error = process.errorStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            if (error.isNotEmpty()) Log.w(TAG, "stderr: $error")
            output
        } catch (e: Exception) {
            Log.e(TAG, "exec failed: $command", e)
            ""
        }
    }

    private fun escapeShellArg(value: String): String {
        return "'" + value.replace("'", "'\\''") + "'"
    }

    private fun resolveLaunchComponent(packageOrComponent: String): String? {
        if (packageOrComponent.contains('/')) return packageOrComponent

        val ctx = shellContext ?: return null
        return try {
            val pm = ctx.packageManager
            val launchIntent = pm.getLaunchIntentForPackage(packageOrComponent)
            val component = launchIntent?.component ?: run {
                val intent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    `package` = packageOrComponent
                }
                pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
                    .firstOrNull()
                    ?.activityInfo
                    ?.let { ComponentName(it.packageName, it.name) }
            }
            component?.flattenToShortString()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to resolve launcher component for $packageOrComponent", e)
            null
        }
    }

    private fun buildLaunchCommand(
        displayId: Int,
        packageOrComponent: String,
        extraKey: String? = null,
        extraValue: String? = null
    ): String {
        val resolvedComponent = resolveLaunchComponent(packageOrComponent)
        val launchTarget = resolvedComponent ?: packageOrComponent
        val isSplitBrowserLaunch =
            (launchTarget.contains("com.castla.mirror/.ui.WebBrowserActivity") ||
                launchTarget.contains("com.castla.mirror.ui.WebBrowserActivity")) &&
                extraKey == "url" && extraValue?.contains("#split=true") == true
        return buildString {
            append("am start -W --display $displayId -f 0x18000000 ") // FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_MULTIPLE_TASK
            if (isSplitBrowserLaunch) {
                append("--windowingMode 5 ")
            }
            if (resolvedComponent != null) {
                append("-n ${escapeShellArg(resolvedComponent)} ")
            } else {
                append("-a android.intent.action.MAIN -c android.intent.category.LAUNCHER ")
                append("-p ${escapeShellArg(packageOrComponent)} ")
            }
            if (!extraKey.isNullOrEmpty() && extraValue != null) {
                append("--es $extraKey ${escapeShellArg(extraValue)} ")
            }
        }.trim()
    }

    override fun launchAppOnDisplay(displayId: Int, packageName: String) {
        try {
            val cmd = buildLaunchCommand(displayId, packageName)
            val result = execCommand(cmd)
            Log.i(TAG, "Launched $packageName on display $displayId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch $packageName on display $displayId", e)
        }
    }

    override fun launchAppWithExtraOnDisplay(displayId: Int, packageName: String, extraKey: String, extraValue: String) {
        try {
            val cmd = buildLaunchCommand(displayId, packageName, extraKey, extraValue)
            val result = execCommand(cmd)
            Log.i(TAG, "Launched $packageName with extra on display $displayId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch $packageName on display $displayId", e)
        }
    }

    override fun launchHomeOnDisplay(displayId: Int) {
        try {
            execCommand("input -d $displayId keyevent 3")
            Log.i(TAG, "Sent HOME key to display $displayId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send HOME key to display $displayId", e)
        }
    }

    override fun injectText(text: String, displayId: Int) {
        if (text.isEmpty()) return
        val isAsciiOnly = text.all { it.code < 128 }
        if (isAsciiOnly) {
            try {
                val escaped = text.replace("%", "%%").replace("'", "'\\''").replace(" ", "%s")
                val cmd = if (displayId > 0) "input -d $displayId text '$escaped'" else "input text '$escaped'"
                execCommand(cmd)
            } catch (e: Exception) {
                Log.e(TAG, "Shell text injection failed", e)
            }
            return
        }
        try {
            val imClass = Class.forName("android.hardware.input.InputManager")
            val getInstance = imClass.getMethod("getInstance")
            val im = getInstance.invoke(null)
            val injectMethod = imClass.getMethod(
                "injectInputEvent",
                android.view.InputEvent::class.java,
                Int::class.javaPrimitiveType
            )
            val smClass = Class.forName("android.os.ServiceManager")
            val getService = smClass.getMethod("getService", String::class.java)
            val clipBinder = getService.invoke(null, "clipboard") as android.os.IBinder
            val clipStubClass = Class.forName("android.content.IClipboard\$Stub")
            val asInterface = clipStubClass.getMethod("asInterface", android.os.IBinder::class.java)
            val clipService = asInterface.invoke(null, clipBinder)
            val clipData = android.content.ClipData.newPlainText("castla", text)
            val setPrimary = clipService.javaClass.methods.find { it.name == "setPrimaryClip" }
            if (setPrimary != null) {
                val paramCount = setPrimary.parameterTypes.size
                val args: Array<Any?> = when (paramCount) {
                    5 -> arrayOf(clipData, "com.android.shell", "com.android.shell", 0, 0)
                    4 -> arrayOf(clipData, "com.android.shell", null, 0)
                    3 -> arrayOf(clipData, "com.android.shell", 0)
                    2 -> arrayOf(clipData, "com.android.shell")
                    else -> arrayOf(clipData)
                }
                setPrimary.invoke(clipService, *args)
            }
            Thread.sleep(50)
            val time = android.os.SystemClock.uptimeMillis()
            val meta = android.view.KeyEvent.META_CTRL_LEFT_ON or android.view.KeyEvent.META_CTRL_ON
            fun makeKeyEvent(action: Int, keyCode: Int, metaState: Int): android.view.KeyEvent {
                val ev = android.view.KeyEvent(time, time, action, keyCode, 0, metaState,
                    android.view.KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0,
                    android.view.InputDevice.SOURCE_KEYBOARD)
                if (displayId > 0) {
                    try { ev.javaClass.getMethod("setDisplayId", Int::class.javaPrimitiveType).invoke(ev, displayId) }
                    catch (_: Exception) {}
                }
                return ev
            }
            injectMethod.invoke(im, makeKeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_CTRL_LEFT, meta), 0)
            injectMethod.invoke(im, makeKeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_V, meta), 0)
            injectMethod.invoke(im, makeKeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_V, meta), 0)
            injectMethod.invoke(im, makeKeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_CTRL_LEFT, 0), 0)
        } catch (e: Exception) {
            Log.e(TAG, "Clipboard+CTRL+V injection failed", e)
        }
    }

    override fun injectComposingText(backspaces: Int, text: String, displayId: Int) {
        try {
            if (backspaces > 0) {
                val bsKeys = (1..backspaces).joinToString(" ") { "67" }
                val cmd = if (displayId > 0) "input -d $displayId keyevent $bsKeys" else "input keyevent $bsKeys"
                execCommand(cmd)
            }
            if (text.isNotEmpty()) injectText(text, displayId)
        } catch (e: Exception) {
            Log.e(TAG, "injectComposingText failed", e)
        }
    }

    override fun addInterfaceAddress(ifName: String, address: String, prefixLength: Int): Boolean { return true }
    override fun removeInterfaceAddress(ifName: String, address: String, prefixLength: Int): Boolean { return true }
    override fun setupTeslaNetworking(ifName: String, virtualIp: String): String { return "" }
    override fun restartTetheringWithCgnat(): String { return "" }
    override fun isAlive(): Boolean = true

    override fun destroy() {
        virtualDisplays.values.forEach { it.release() }
        virtualDisplays.clear()
        virtualDisplayNames.clear()
        Log.i(TAG, "PrivilegedService destroyed")
    }

    override fun wakeUpDisplay(displayId: Int) {
        try {
            // 1. Wake up the display via PowerManagerService internal API
            val smClass = Class.forName("android.os.ServiceManager")
            val getService = smClass.getMethod("getService", String::class.java)
            val powerBinder = getService.invoke(null, "power") as android.os.IBinder
            val ipmStub = Class.forName("android.os.IPowerManager\$Stub")
            val pm = ipmStub.getMethod("asInterface", android.os.IBinder::class.java)
                .invoke(null, powerBinder)

            // Try wakeUp(long time, int reason, String details, String opPackageName)
            val now = android.os.SystemClock.uptimeMillis()
            val wakeUpMethods = pm.javaClass.methods.filter { it.name == "wakeUp" }
            var woken = false
            for (m in wakeUpMethods) {
                try {
                    when (m.parameterTypes.size) {
                        4 -> { m.invoke(pm, now, 0, "castla:vd_keep_alive", "com.android.shell"); woken = true }
                        2 -> { m.invoke(pm, now, "castla:vd_keep_alive"); woken = true }
                        1 -> { m.invoke(pm, now); woken = true }
                    }
                    if (woken) break
                } catch (_: Exception) {}
            }

            // 2. Inject user activity to prevent doze/dream on the VD
            val userActivityMethods = pm.javaClass.methods.filter { it.name == "userActivity" }
            for (m in userActivityMethods) {
                try {
                    when (m.parameterTypes.size) {
                        4 -> { m.invoke(pm, displayId.toLong(), now, 0, 0); break }
                        3 -> { m.invoke(pm, now, 0, 0); break }
                        2 -> { m.invoke(pm, now, 0); break }
                    }
                } catch (_: Exception) {}
            }

            // 3. Send WAKEUP key event to the specific display
            execCommand("input -d $displayId keyevent 224")

            // 4. Dismiss keyguard on the VD just in case
            execCommand("wm dismiss-keyguard -d $displayId")

            Log.i(TAG, "wakeUpDisplay($displayId): woken=$woken")
        } catch (e: Exception) {
            Log.w(TAG, "wakeUpDisplay($displayId) failed, falling back to keyevent", e)
            try {
                execCommand("input -d $displayId keyevent 224")
            } catch (_: Exception) {}
        }
    }

    override fun resizeVirtualDisplay(displayId: Int, width: Int, height: Int, densityDpi: Int) {
        val vd = virtualDisplays[displayId]
        if (vd == null) {
            Log.w(TAG, "resizeVirtualDisplay: no display with id=$displayId")
            return
        }
        vd.resize(width, height, densityDpi)
        Log.i(TAG, "Resized virtual display $displayId to ${width}x${height} @ ${densityDpi}dpi")
    }

    override fun registerDeathToken(token: android.os.IBinder) {
        try {
            token.linkToDeath({
                Log.w(TAG, "Client died! Cleaning up PrivilegedService and killing VDs.")
                destroy()
                System.exit(0)
            }, 0)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to link to death", e)
        }
    }
}
