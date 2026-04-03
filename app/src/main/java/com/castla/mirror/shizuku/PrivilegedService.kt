package com.castla.mirror.shizuku

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.ParcelFileDescriptor
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

    private val tetheringExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

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
        // FLAG_OWN_DISPLAY_GROUP puts the VD in a separate display group so Keyguard does NOT show on it
        private const val DISPLAY_FLAG_OWN_DISPLAY_GROUP = 1 shl 11
        // FLAG_DESTROY_CONTENT_ON_REMOVAL destroys tasks instead of reparenting to main display
        private const val DISPLAY_FLAG_DESTROY_CONTENT = 1 shl 8
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
            var flags = DISPLAY_FLAG_PUBLIC or DISPLAY_FLAG_OWN_CONTENT_ONLY or DISPLAY_FLAG_PRESENTATION or DISPLAY_FLAG_DESTROY_CONTENT
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                flags = flags or DISPLAY_FLAG_ALWAYS_UNLOCKED or DISPLAY_FLAG_TRUSTED or DISPLAY_FLAG_OWN_DISPLAY_GROUP
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
            injectMethod?.invoke(inputManagerInstance, event, 0)
        } catch (e: Exception) {
            Log.e(TAG, "Input injection failed on display $displayId", e)
        } finally {
            event.recycle()
        }
    }

    override fun execCommand(command: String): String {
        // Handle special internal commands for hotspot control
        if (command == "__HOTSPOT_ON__") return doStartWifiTethering()
        if (command == "__HOTSPOT_OFF__") return doStopWifiTethering()

        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val output = process.inputStream.bufferedReader().readText()
            val error = process.errorStream.bufferedReader().readText()
            process.waitFor()
            if (error.isNotEmpty()) Log.w(TAG, "stderr: $error")
            output
        } catch (e: Exception) {
            Log.e(TAG, "exec failed: $command", e)
            ""
        }
    }

    private fun doStartWifiTethering(): String {
        val log = StringBuilder()
        log.appendLine("=== startWifiTethering ===")

        // Pre-step: Disable carrier DUN provisioning check
        try {
            val r = execCommand("settings put global tether_dun_required 0")
            log.appendLine("DUN bypass: $r")
        } catch (_: Exception) {}

        // Method 1: TetheringManager via new instance with correct package name
        // getSystemService("tethering") caches a TetheringManager with pkg="android",
        // but TetheringService requires pkg to match UID 2000 ("com.android.shell").
        // So we create a fresh TetheringManager via its constructor.
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            try {
                val ctx = shellContext
                if (ctx == null) {
                    log.appendLine("shellContext is null!")
                } else {
                    val tmObj = createTetheringManager()
                    log.appendLine("TetheringManager created: ${tmObj?.javaClass?.name}")

                    if (tmObj != null) {
                        val tmClass = tmObj.javaClass

                        // Build TetheringRequest with entitlement bypass
                        val requestBuilderClass = Class.forName("android.net.TetheringManager\$TetheringRequest\$Builder")
                        val builder = requestBuilderClass
                            .getConstructor(Int::class.javaPrimitiveType)
                            .newInstance(0) // TETHERING_WIFI = 0
                        try {
                            requestBuilderClass.getMethod("setExemptFromEntitlementCheck", Boolean::class.javaPrimitiveType)
                                .invoke(builder, true)
                            log.appendLine("setExemptFromEntitlementCheck: OK")
                        } catch (e: Exception) {
                            log.appendLine("setExemptFromEntitlementCheck: ${e.message}")
                        }
                        try {
                            requestBuilderClass.getMethod("setShouldShowEntitlementUi", Boolean::class.javaPrimitiveType)
                                .invoke(builder, false)
                            log.appendLine("setShouldShowEntitlementUi: OK")
                        } catch (e: Exception) {
                            log.appendLine("setShouldShowEntitlementUi: ${e.message}")
                        }
                        val request = requestBuilderClass.getMethod("build").invoke(builder)
                        log.appendLine("TetheringRequest built: ${request.javaClass.name}")

                        // Create StartTetheringCallback proxy
                        val callbackClass = Class.forName("android.net.TetheringManager\$StartTetheringCallback")
                        val callback = java.lang.reflect.Proxy.newProxyInstance(
                            callbackClass.classLoader,
                            arrayOf(callbackClass)
                        ) { _, method, args ->
                            when (method.name) {
                                "onTetheringStarted" -> Log.i(TAG, "HOTSPOT: started successfully!")
                                "onTetheringFailed" -> Log.e(TAG, "HOTSPOT: failed, error=${args?.getOrNull(0)}")
                            }
                            null
                        }

                        // Try TetheringRequest overload first
                        val requestClass = Class.forName("android.net.TetheringManager\$TetheringRequest")
                        val startMethod = tmClass.methods.find { m ->
                            m.name == "startTethering" && m.parameterTypes.size == 3 &&
                                m.parameterTypes[0] == requestClass
                        }
                        if (startMethod != null) {
                            val executor = tetheringExecutor
                            startMethod.invoke(tmObj, request, executor, callback)
                            log.appendLine("SUCCESS: startTethering(TetheringRequest) invoked")
                            Log.i(TAG, log.toString())
                            return "OK\n$log"
                        }

                        // Fallback: int overload
                        val intMethod = tmClass.methods.find { m ->
                            m.name == "startTethering" && m.parameterTypes.size == 3 &&
                                m.parameterTypes[0] == Int::class.javaPrimitiveType
                        }
                        if (intMethod != null) {
                            val executor = tetheringExecutor
                            intMethod.invoke(tmObj, 0, executor, callback)
                            log.appendLine("SUCCESS: startTethering(int) invoked")
                            Log.i(TAG, log.toString())
                            return "OK\n$log"
                        }

                        log.appendLine("FAIL: no startTethering method found")
                    }
                }
            } catch (e: Exception) {
                log.appendLine("TetheringManager EXCEPTION: ${e.javaClass.simpleName}: ${e.message}")
                val cause = e.cause
                if (cause != null) log.appendLine("  cause: ${cause.javaClass.simpleName}: ${cause.message}")
            }
        }

        log.appendLine("ALL METHODS FAILED")
        Log.e(TAG, log.toString())
        return "FAIL\n$log"
    }

    /**
     * Create a TetheringManager with correct caller package name (com.android.shell)
     * instead of using the cached one from getSystemService which reports "android".
     */
    private fun createTetheringManager(): Any? {
        val ctx = shellContext ?: return null
        if (android.os.Build.VERSION.SDK_INT < 30) return null
        val tmClass = Class.forName("android.net.TetheringManager")
        val smClass = Class.forName("android.os.ServiceManager")
        val getService = smClass.getMethod("getService", String::class.java)
        val binder = getService.invoke(null, "tethering") as? android.os.IBinder ?: return null

        val constructor = tmClass.declaredConstructors.find { c ->
            c.parameterTypes.size == 2 && c.parameterTypes[0] == android.content.Context::class.java
        }
        return if (constructor != null) {
            constructor.isAccessible = true
            constructor.newInstance(ctx, java.util.function.Supplier<android.os.IBinder> { binder })
        } else {
            // Fallback: patch the cached instance
            val tm = ctx.getSystemService("tethering") ?: return null
            try {
                val field = tmClass.getDeclaredField("mCallerPackageName")
                field.isAccessible = true
                field.set(tm, "com.android.shell")
            } catch (_: Exception) {}
            tm
        }
    }

    private fun doStopWifiTethering(): String {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            try {
                val tmObj = createTetheringManager() ?: return "FAIL: no TetheringManager"
                val stopMethod = tmObj.javaClass.getMethod("stopTethering", Int::class.javaPrimitiveType)
                stopMethod.invoke(tmObj, 0)
                Log.i(TAG, "HOTSPOT OFF: TetheringManager.stopTethering() called")
                return "OK"
            } catch (e: Exception) {
                Log.w(TAG, "stopTethering TetheringManager failed", e)
            }
        }

        try {
            val ctx = shellContext ?: return "FAIL: no context"
            val cm = ctx.getSystemService("connectivity") ?: return "FAIL: no CM"
            val stopMethod = cm.javaClass.getMethod("stopTethering", Int::class.javaPrimitiveType)
            stopMethod.invoke(cm, 0)
            Log.i(TAG, "HOTSPOT OFF: ConnectivityManager.stopTethering() called")
            return "OK"
        } catch (e: Exception) {
            Log.e(TAG, "All stopTethering methods failed", e)
            return "FAIL: ${e.message}"
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
            execCommand(cmd)
            Log.i(TAG, "Launched $packageName on display $displayId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch $packageName on display $displayId", e)
        }
    }

    override fun launchAppWithExtraOnDisplay(displayId: Int, packageName: String, extraKey: String, extraValue: String) {
        try {
            val cmd = buildLaunchCommand(displayId, packageName, extraKey, extraValue)
            execCommand(cmd)
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

    // --- WiFi Tethering (Hotspot) control ---

    override fun startWifiTethering(): Boolean {
        Log.i(TAG, "startWifiTethering: attempting to enable hotspot")

        // Pre-step: Disable carrier DUN provisioning check (important for Samsung/carrier devices)
        try {
            execCommand("settings put global tether_dun_required 0")
            Log.i(TAG, "Carrier DUN check disabled")
        } catch (_: Exception) {}

        // Method 1: TetheringManager (Android 11+ / API 30+) — PREFERRED
        // StartTetheringCallback is an INTERFACE with default methods, so Proxy works.
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            try {
                val ctx = shellContext
                if (ctx != null) {
                    val tmObj = ctx.getSystemService("tethering")
                    if (tmObj != null) {
                        val tmClass = tmObj.javaClass

                        // Build TetheringRequest with entitlement bypass
                        val requestBuilderClass = Class.forName("android.net.TetheringManager\$TetheringRequest\$Builder")
                        val builder = requestBuilderClass
                            .getConstructor(Int::class.javaPrimitiveType)
                            .newInstance(0) // TETHERING_WIFI = 0

                        // Bypass carrier entitlement check (critical for Samsung/carrier-locked devices)
                        try {
                            requestBuilderClass.getMethod("setExemptFromEntitlementCheck", Boolean::class.javaPrimitiveType)
                                .invoke(builder, true)
                            Log.i(TAG, "setExemptFromEntitlementCheck(true) set")
                        } catch (_: Exception) {
                            Log.w(TAG, "setExemptFromEntitlementCheck not available on this version")
                        }
                        try {
                            requestBuilderClass.getMethod("setShouldShowEntitlementUi", Boolean::class.javaPrimitiveType)
                                .invoke(builder, false)
                            Log.i(TAG, "setShouldShowEntitlementUi(false) set")
                        } catch (_: Exception) {}

                        val request = requestBuilderClass.getMethod("build").invoke(builder)

                        val callbackClass = Class.forName("android.net.TetheringManager\$StartTetheringCallback")
                        val callback = java.lang.reflect.Proxy.newProxyInstance(
                            callbackClass.classLoader,
                            arrayOf(callbackClass)
                        ) { _, method, args ->
                            when (method.name) {
                                "onTetheringStarted" -> Log.i(TAG, "TetheringManager: hotspot started successfully!")
                                "onTetheringFailed" -> Log.e(TAG, "TetheringManager: hotspot failed, error=${args?.getOrNull(0)}")
                            }
                            null
                        }

                        val startMethod = tmClass.methods.find { m ->
                            m.name == "startTethering" && m.parameterTypes.size == 3
                        }
                        if (startMethod != null) {
                            val executor = tetheringExecutor
                            startMethod.invoke(tmObj, request, executor, callback)
                            Log.i(TAG, "startWifiTethering: TetheringManager.startTethering() invoked")
                            return true
                        } else {
                            Log.w(TAG, "startTethering method not found on TetheringManager")
                        }
                    } else {
                        Log.w(TAG, "getSystemService('tethering') returned null")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "TetheringManager approach failed", e)
            }
        }

        // Method 2: ConnectivityManager.startTethering (Android 7-10)
        // OnStartTetheringCallback is abstract class with empty method bodies — ART allows instantiation
        try {
            val ctx = shellContext
            if (ctx != null) {
                val cm = ctx.getSystemService("connectivity")
                if (cm != null) {
                    val callbackClass = Class.forName("android.net.ConnectivityManager\$OnStartTetheringCallback")
                    val callback = callbackClass.getDeclaredConstructor().let { ctor ->
                        ctor.isAccessible = true
                        ctor.newInstance()
                    }
                    val startMethod = cm.javaClass.getMethod(
                        "startTethering",
                        Int::class.javaPrimitiveType,
                        Boolean::class.javaPrimitiveType,
                        callbackClass
                    )
                    startMethod.invoke(cm, 0 /* TETHERING_WIFI */, false /* no provisioning UI */, callback)
                    Log.i(TAG, "startWifiTethering: ConnectivityManager.startTethering() invoked")
                    return true
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "ConnectivityManager approach failed", e)
        }

        // Method 3: Shell command fallback (Android 11+)
        try {
            val result = execCommand("cmd connectivity tethering wifi enable")
            Log.i(TAG, "startWifiTethering: shell cmd result: $result")
            return true
        } catch (e: Exception) {
            Log.w(TAG, "Shell cmd fallback failed", e)
        }

        Log.e(TAG, "All tethering start methods exhausted")
        return false
    }

    override fun stopWifiTethering(): Boolean {
        Log.i(TAG, "stopWifiTethering: attempting to disable hotspot")

        // Method 1: TetheringManager.stopTethering (Android 11+)
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            try {
                val ctx = shellContext
                if (ctx != null) {
                    val tmObj = ctx.getSystemService("tethering")
                    if (tmObj != null) {
                        val stopMethod = tmObj.javaClass.getMethod(
                            "stopTethering",
                            Int::class.javaPrimitiveType
                        )
                        stopMethod.invoke(tmObj, 0) // TETHERING_WIFI = 0
                        Log.i(TAG, "stopWifiTethering: TetheringManager.stopTethering() called")
                        return true
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "TetheringManager stop failed", e)
            }
        }

        // Method 2: ConnectivityManager.stopTethering
        try {
            val ctx = shellContext
            if (ctx != null) {
                val cm = ctx.getSystemService("connectivity")
                if (cm != null) {
                    val stopMethod = cm.javaClass.getMethod(
                        "stopTethering",
                        Int::class.javaPrimitiveType
                    )
                    stopMethod.invoke(cm, 0)
                    Log.i(TAG, "stopWifiTethering: ConnectivityManager.stopTethering() called")
                    return true
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "ConnectivityManager stop failed", e)
        }

        // Method 3: Shell fallback
        try {
            execCommand("cmd connectivity tethering wifi disable")
            Log.i(TAG, "stopWifiTethering: shell fallback executed")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "All stop tethering methods failed", e)
        }

        return false
    }

    // --- System audio capture via REMOTE_SUBMIX (shell uid has CAPTURE_AUDIO_OUTPUT) ---

    @Volatile
    private var audioCaptureRunning = false
    private var audioCaptureThread: Thread? = null
    private var audioCaptureRecord: AudioRecord? = null

    override fun startSystemAudioCapture(sampleRate: Int, channels: Int): ParcelFileDescriptor? {
        stopSystemAudioCapture()

        return try {
            val channelMask = if (channels == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
            val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
            val bufSize = maxOf(minBuf * 2, 8192)

            val record = AudioRecord(
                MediaRecorder.AudioSource.REMOTE_SUBMIX,
                sampleRate,
                channelMask,
                AudioFormat.ENCODING_PCM_16BIT,
                bufSize
            )

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "REMOTE_SUBMIX AudioRecord failed to initialize (state=${record.state})")
                record.release()
                return null
            }

            val pipe = ParcelFileDescriptor.createPipe()
            val readEnd = pipe[0]
            val writeEnd = pipe[1]

            record.startRecording()
            audioCaptureRecord = record
            audioCaptureRunning = true

            audioCaptureThread = Thread({
                val pcmBuf = ByteArray(3840) // 20ms at 48kHz stereo 16bit
                val output = ParcelFileDescriptor.AutoCloseOutputStream(writeEnd)
                try {
                    while (audioCaptureRunning) {
                        val read = record.read(pcmBuf, 0, pcmBuf.size)
                        if (read > 0) {
                            output.write(pcmBuf, 0, read)
                        } else if (read < 0) {
                            Log.w(TAG, "REMOTE_SUBMIX read error: $read")
                            break
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "REMOTE_SUBMIX capture thread ended", e)
                } finally {
                    try { output.close() } catch (_: Exception) {}
                }
            }, "RemoteSubmix-Capture").also { it.start() }

            Log.i(TAG, "REMOTE_SUBMIX audio capture started: ${sampleRate}Hz, ${channels}ch")
            readEnd
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start REMOTE_SUBMIX audio capture", e)
            null
        }
    }

    override fun stopSystemAudioCapture() {
        audioCaptureRunning = false
        try { audioCaptureRecord?.stop() } catch (_: Exception) {}
        audioCaptureThread?.join(2000)
        audioCaptureThread = null
        try { audioCaptureRecord?.release() } catch (_: Exception) {}
        audioCaptureRecord = null
    }

    override fun destroy() {
        stopSystemAudioCapture()
        virtualDisplays.values.forEach { it.release() }
        virtualDisplays.clear()
        virtualDisplayNames.clear()
        Log.i(TAG, "PrivilegedService destroyed")
    }

    override fun wakeUpDisplay(displayId: Int) {
        Log.i(TAG, "[BUILD:screen-off-v2] wakeUpDisplay($displayId) ENTRY")
        try {
            // Do NOT use PowerManager.wakeUp() — it wakes the physical screen too.
            // Instead, use display-targeted methods that only affect the VD.

            // 1. Send WAKEUP key event to the specific display (does not wake physical screen)
            execCommand("input -d $displayId keyevent 224")

            // 2. Inject user activity on the VD via PowerManager to prevent doze/dream
            try {
                val smClass = Class.forName("android.os.ServiceManager")
                val getService = smClass.getMethod("getService", String::class.java)
                val powerBinder = getService.invoke(null, "power") as android.os.IBinder
                val ipmStub = Class.forName("android.os.IPowerManager\$Stub")
                val pm = ipmStub.getMethod("asInterface", android.os.IBinder::class.java)
                    .invoke(null, powerBinder)
                val now = SystemClock.uptimeMillis()
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
            } catch (_: Exception) {}

            // 3. Inject a no-op touch (down+up at 1,1) to generate user activity on the VD
            try {
                injectInput(displayId, MotionEvent.ACTION_DOWN, 1f, 1f, 0)
                injectInput(displayId, MotionEvent.ACTION_UP, 1f, 1f, 0)
            } catch (_: Exception) {}

            Log.i(TAG, "wakeUpDisplay($displayId): keyevent+userActivity+touch (no PowerManager.wakeUp)")
        } catch (e: Exception) {
            Log.w(TAG, "wakeUpDisplay($displayId) failed", e)
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

    // --- Physical display power control (scrcpy approach) ---

    private val POWER_MODE_OFF = 0
    private val POWER_MODE_NORMAL = 2

    override fun setPhysicalDisplayPower(on: Boolean) {
        Log.i(TAG, "[BUILD:screen-off-v2] setPhysicalDisplayPower($on) ENTRY")
        val mode = if (on) POWER_MODE_NORMAL else POWER_MODE_OFF
        try {
            val scClass = Class.forName("android.view.SurfaceControl")
            val setMethod = scClass.getMethod(
                "setDisplayPowerMode",
                android.os.IBinder::class.java, Int::class.javaPrimitiveType
            )

            val token = getPhysicalDisplayToken(scClass)
            if (token != null) {
                setMethod.invoke(null, token, mode)
                Log.i(TAG, "Physical display power set to ${if (on) "ON" else "OFF"}")
            } else {
                Log.e(TAG, "Could not get physical display token")
            }
        } catch (e: Exception) {
            Log.e(TAG, "setPhysicalDisplayPower failed", e)
        }
    }

    private fun getPhysicalDisplayToken(scClass: Class<*>): android.os.IBinder? {
        // Try Android 10-13: SurfaceControl.getPhysicalDisplayIds() + getPhysicalDisplayToken()
        try {
            val getIds = scClass.getMethod("getPhysicalDisplayIds")
            val getToken = scClass.getMethod("getPhysicalDisplayToken", Long::class.javaPrimitiveType)
            val ids = getIds.invoke(null) as? LongArray
            if (ids != null && ids.isNotEmpty()) {
                return getToken.invoke(null, ids[0]) as? android.os.IBinder
            }
        } catch (_: Exception) {}

        // Try Android 10+: getInternalDisplayToken()
        try {
            val m = scClass.getMethod("getInternalDisplayToken")
            return m.invoke(null) as? android.os.IBinder
        } catch (_: Exception) {}

        // Try Android 14+: DisplayControl from services.jar
        try {
            val classLoaderFactoryClass = Class.forName("com.android.internal.os.ClassLoaderFactory")
            val createClassLoaderMethod = classLoaderFactoryClass.getDeclaredMethod(
                "createClassLoader",
                String::class.java, String::class.java, String::class.java,
                ClassLoader::class.java, Int::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType, String::class.java
            )
            val classLoader = createClassLoaderMethod.invoke(
                null, "/system/framework/services.jar", null, null,
                ClassLoader.getSystemClassLoader(), 0, true, null
            ) as ClassLoader
            val dcClass = classLoader.loadClass("com.android.server.display.DisplayControl")
            try {
                val loadLib = Runtime::class.java.getDeclaredMethod("loadLibrary0", Class::class.java, String::class.java)
                loadLib.isAccessible = true
                loadLib.invoke(Runtime.getRuntime(), dcClass, "android_servers")
            } catch (_: Exception) {}
            val getIds = dcClass.getMethod("getPhysicalDisplayIds")
            val getToken = dcClass.getMethod("getPhysicalDisplayToken", Long::class.javaPrimitiveType)
            val ids = getIds.invoke(null) as? LongArray
            if (ids != null && ids.isNotEmpty()) {
                return getToken.invoke(null, ids[0]) as? android.os.IBinder
            }
        } catch (_: Exception) {}

        // Fallback: getBuiltInDisplay(0)
        try {
            val m = scClass.getMethod("getBuiltInDisplay", Int::class.javaPrimitiveType)
            return m.invoke(null, 0) as? android.os.IBinder
        } catch (_: Exception) {}

        return null
    }
}
