package com.castla.mirror.shizuku

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

    override fun execCommand(command: String): String {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val output = process.inputStream.bufferedReader().readText()
            val error = process.errorStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            Log.i(TAG, "exec '$command' → exit=$exitCode")
            if (error.isNotEmpty()) Log.w(TAG, "stderr: $error")
            output
        } catch (e: Exception) {
            Log.e(TAG, "exec failed: $command", e)
            ""
        }
    }

    // Returns error message or null on success
    private fun tryAddInterfaceAddress(ifName: String, address: String, prefixLength: Int): String? {
        val netd = getNetdService()
            ?: return "getNetdService() returned null"

        // If we got a proper INetd proxy, use the method directly
        try {
            val addMethod = netd.javaClass.getMethod(
                "interfaceAddAddress",
                String::class.java, String::class.java, Int::class.javaPrimitiveType
            )
            addMethod.invoke(netd, ifName, address, prefixLength)
            Log.i(TAG, "INetd.interfaceAddAddress($ifName, $address/$prefixLength) succeeded")
            return null
        } catch (_: NoSuchMethodException) {
            Log.i(TAG, "No interfaceAddAddress method, trying binder transact")
        } catch (e: Exception) {
            val cause = e.cause?.message ?: e.message ?: "unknown"
            Log.e(TAG, "INetd.interfaceAddAddress failed: $cause", e)
            return cause
        }

        // Fallback: use raw binder transact (transaction 13 = interfaceAddAddress in INetd.aidl)
        if (netd is android.os.IBinder) {
            try {
                val data = android.os.Parcel.obtain()
                val reply = android.os.Parcel.obtain()
                try {
                    data.writeInterfaceToken("android.net.INetd")
                    data.writeString(ifName)
                    data.writeString(address)
                    data.writeInt(prefixLength)
                    val ok = netd.transact(13, data, reply, 0)
                    Log.i(TAG, "transact(13) INetd descriptor=android.net.INetd result=$ok")
                    reply.readException()
                    Log.i(TAG, "interfaceAddAddress via transact succeeded!")
                    return null
                } finally {
                    data.recycle()
                    reply.recycle()
                }
            } catch (e: Exception) {
                Log.w(TAG, "transact android.net.INetd failed: ${e.message}")
            }

            // Try with different descriptor
            try {
                val data = android.os.Parcel.obtain()
                val reply = android.os.Parcel.obtain()
                try {
                    data.writeInterfaceToken("android.system.net.netd.INetd")
                    data.writeString(ifName)
                    data.writeString(address)
                    data.writeInt(prefixLength)
                    val ok = netd.transact(13, data, reply, 0)
                    Log.i(TAG, "transact(13) INetd descriptor=android.system.net.netd.INetd result=$ok")
                    reply.readException()
                    Log.i(TAG, "interfaceAddAddress via transact succeeded!")
                    return null
                } finally {
                    data.recycle()
                    reply.recycle()
                }
            } catch (e: Exception) {
                Log.w(TAG, "transact android.system.net.netd.INetd failed: ${e.message}")
            }
        }

        return "No method to call interfaceAddAddress"
    }

    override fun addInterfaceAddress(ifName: String, address: String, prefixLength: Int): Boolean {
        return tryAddInterfaceAddress(ifName, address, prefixLength) == null
    }

    override fun removeInterfaceAddress(ifName: String, address: String, prefixLength: Int): Boolean {
        return try {
            val netd = getNetdService()
            if (netd != null) {
                val removeMethod = netd.javaClass.getMethod(
                    "interfaceDelAddress",
                    String::class.java, String::class.java, Int::class.javaPrimitiveType
                )
                removeMethod.invoke(netd, ifName, address, prefixLength)
                Log.i(TAG, "INetd.interfaceDelAddress($ifName, $address/$prefixLength) succeeded")
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "INetd.interfaceDelAddress failed", e)
            false
        }
    }

    private fun getNetdService(): Any? {
        val smClass = Class.forName("android.os.ServiceManager")

        val serviceNames = listOf(
            "android.system.net.netd.INetd/default",
            "netd"
        )

        // Method 1: getService
        // Method 2: waitForDeclaredService (for native AIDL services on Android 12+)
        // Method 3: checkService
        val methodNames = listOf("waitForDeclaredService", "getService", "checkService")

        for (serviceName in serviceNames) {
            for (methodName in methodNames) {
                try {
                    val method = smClass.getMethod(methodName, String::class.java)
                    val binder = method.invoke(null, serviceName) as? android.os.IBinder
                    if (binder != null) {
                        Log.i(TAG, "Got binder via $methodName('$serviceName')")
                        try {
                            Log.i(TAG, "  descriptor: ${binder.interfaceDescriptor}")
                        } catch (_: Exception) {}

                        // Try Stub classes
                        for (stubName in listOf(
                            "android.system.net.netd.INetd\$Stub",
                            "android.net.INetd\$Stub"
                        )) {
                            try {
                                val stubClass = Class.forName(stubName)
                                val asInterface = stubClass.getMethod("asInterface", android.os.IBinder::class.java)
                                val service = asInterface.invoke(null, binder)
                                if (service != null) {
                                    Log.i(TAG, "Got INetd: method=$methodName service=$serviceName stub=$stubName")
                                    return service
                                }
                            } catch (_: ClassNotFoundException) {
                            } catch (e: Exception) {
                                Log.w(TAG, "Stub $stubName failed: ${e.message}")
                            }
                        }

                        // If no Stub class found, try using binder directly via transact
                        Log.i(TAG, "No stub matched, trying direct binder transact")
                        return binder // Return raw binder, caller will use transact
                    }
                } catch (_: NoSuchMethodException) {
                } catch (e: Exception) {
                    Log.w(TAG, "$methodName('$serviceName') failed: ${e.message}")
                }
            }
        }

        Log.e(TAG, "No INetd service found via any method")
        return null
    }

    override fun setupTeslaNetworking(ifName: String, virtualIp: String): String {
        val log = StringBuilder()
        fun log(msg: String) {
            Log.i(TAG, msg)
            log.appendLine(msg)
        }

        log("=== Tesla Network Setup (uid=${android.os.Process.myUid()}) ===")

        // Check if IP already on target interface
        val addrs = execCommand("ip addr show dev $ifName")
        if (addrs.contains(virtualIp)) {
            log("$virtualIp already on $ifName — done!")
            return log.toString()
        }

        // Method 1: INetd.interfaceAddAddress (binder, netd runs as root)
        log("--- Method 1: INetd binder ---")
        // Log netd binder descriptor to find correct class
        try {
            val smClass = Class.forName("android.os.ServiceManager")
            val getService = smClass.getMethod("getService", String::class.java)
            val binder = getService.invoke(null, "netd") as? android.os.IBinder
            if (binder != null) {
                log("netd descriptor: ${binder.interfaceDescriptor}")
            } else {
                log("netd binder: null")
            }
        } catch (e: Exception) {
            log("netd binder check: ${e.message}")
        }

        val netdError = tryAddInterfaceAddress(ifName, virtualIp, 32)
        if (netdError == null) {
            log("INetd SUCCESS")
            return log.toString()
        }
        log("INetd error: $netdError")

        // List INetd available methods for debugging
        val netd = getNetdService()
        if (netd != null) {
            try {
                val methods = netd.javaClass.methods
                    .filter { it.name.lowercase().contains("interface") || it.name.lowercase().contains("addr") }
                    .map { "${it.name}(${it.parameterTypes.map { p -> p.simpleName }.joinToString(",")})" }
                log("INetd methods: ${methods.joinToString(", ")}")
            } catch (e: Exception) {
                log("Could not list methods: ${e.message}")
            }
        }

        // Method 2: ip addr add (netlink)
        log("--- Method 2: ip addr add ---")
        val ipResult = execCommand("ip addr add $virtualIp/32 dev $ifName 2>&1")
        log("result: $ipResult")

        // Method 3: iptables DNAT (redirect packets for virtualIp to hotspot IP)
        log("--- Method 3: iptables DNAT ---")
        val hotspotIp = addrs.lines()
            .firstOrNull { it.contains("inet ") && !it.contains("inet6") }
            ?.trim()?.split(" ")?.getOrNull(1)?.split("/")?.firstOrNull()
            ?: "10.22.128.243"
        val ipt1 = execCommand("iptables -t nat -A PREROUTING -d $virtualIp -p tcp --dport 8080 -j DNAT --to-destination $hotspotIp:8080 2>&1")
        log("iptables DNAT: $ipt1")
        val ipt2 = execCommand("iptables -I INPUT -d $virtualIp -j ACCEPT 2>&1")
        log("iptables INPUT ACCEPT: $ipt2")

        // Method 4: Try INetd.firewallSetInterfaceRule
        log("--- Method 4: INetd firewall ---")
        try {
            val netd = getNetdService()
            if (netd != null) {
                // FIREWALL_RULE_ALLOW = 1
                val fwMethod = netd.javaClass.getMethod(
                    "firewallSetInterfaceRule", String::class.java, Int::class.javaPrimitiveType
                )
                fwMethod.invoke(netd, ifName, 1)
                log("firewallSetInterfaceRule($ifName, ALLOW) ok")
            }
        } catch (e: Exception) {
            log("firewall failed: ${e.cause?.message ?: e.message}")
        }

        // === DIAGNOSTICS: why external packets don't arrive ===
        log("--- Diagnostics ---")

        // rp_filter values
        val rpSwlan = execCommand("cat /proc/sys/net/ipv4/conf/$ifName/rp_filter").trim()
        val rpTun = execCommand("cat /proc/sys/net/ipv4/conf/tun0/rp_filter 2>/dev/null").trim()
        val rpAll = execCommand("cat /proc/sys/net/ipv4/conf/all/rp_filter").trim()
        log("rp_filter: $ifName=$rpSwlan tun0=$rpTun all=$rpAll")

        // accept_local values
        val alSwlan = execCommand("cat /proc/sys/net/ipv4/conf/$ifName/accept_local").trim()
        val alTun = execCommand("cat /proc/sys/net/ipv4/conf/tun0/accept_local 2>/dev/null").trim()
        val alAll = execCommand("cat /proc/sys/net/ipv4/conf/all/accept_local").trim()
        log("accept_local: $ifName=$alSwlan tun0=$alTun all=$alAll")

        // ip_forward
        val fwd = execCommand("cat /proc/sys/net/ipv4/ip_forward").trim()
        log("ip_forward: $fwd")

        // iptables rules (reading might work)
        val iptInput = execCommand("iptables -L INPUT -n 2>&1").trim()
        log("iptables INPUT:\n$iptInput")

        val iptForward = execCommand("iptables -L FORWARD -n 2>&1").trim()
        log("iptables FORWARD:\n$iptForward")

        val iptNat = execCommand("iptables -t nat -L PREROUTING -n 2>&1").trim()
        log("iptables nat PREROUTING:\n$iptNat")

        // Local routing for the virtual IP
        val localRoute = execCommand("ip route show table local | grep $virtualIp").trim()
        log("local route: $localRoute")

        // Test local connectivity
        val curlTest = execCommand("curl -s -o /dev/null -w '%{http_code}' --connect-timeout 2 http://$virtualIp:8080/ 2>&1").trim()
        log("curl $virtualIp:8080 from shizuku: $curlTest")

        // Final interface state
        val finalAddrs = execCommand("ip addr show dev $ifName").trim()
        log("final $ifName: ${finalAddrs.lines().filter { it.contains("inet") }.joinToString(" | ")}")

        val tunAddrs = execCommand("ip addr show dev tun0 2>/dev/null").trim()
        log("tun0: ${tunAddrs.lines().filter { it.contains("inet") }.joinToString(" | ")}")

        return log.toString()
    }

    override fun restartTetheringWithCgnat(): String {
        val log = StringBuilder()
        fun log(msg: String) {
            Log.i(TAG, msg)
            log.appendLine(msg)
        }

        log("=== Restart Tethering with CGNAT (uid=${android.os.Process.myUid()}) ===")

        // Step 1: Find the TetheringManager (system service, hidden API)
        try {
            // Get ConnectivityManager's tethering service via reflection
            // On Android 11+ TetheringManager is a separate system service
            val tmClass = Class.forName("android.net.TetheringManager")
            log("TetheringManager class found")

            // Get instance via Context.getSystemService or direct binder
            val tetheringManager = getTetheringManager(tmClass)
            if (tetheringManager == null) {
                log("ERROR: Could not obtain TetheringManager instance")
                return log.toString()
            }
            log("TetheringManager instance obtained")

            // Step 2: Build TetheringRequest with CGNAT IP
            val requestClass = Class.forName("android.net.TetheringManager\$TetheringRequest")
            val builderClass = Class.forName("android.net.TetheringManager\$TetheringRequest\$Builder")
            log("TetheringRequest.Builder class found")

            // TetheringRequest.Builder(int type) — TETHERING_WIFI = 0
            val builderCtor = builderClass.getConstructor(Int::class.javaPrimitiveType)
            val builder = builderCtor.newInstance(0) // TETHERING_WIFI = 0
            log("Builder created for TETHERING_WIFI")

            // setLocalIpv4Address(LinkAddress)
            val linkAddressClass = Class.forName("android.net.LinkAddress")
            val linkAddrCtor = linkAddressClass.getConstructor(String::class.java)
            val cgnatAddr = linkAddrCtor.newInstance("100.64.0.1/24")
            log("LinkAddress created: 100.64.0.1/24")

            // Try setLocalIpv4Address (Android 11-12)
            var setAddrSuccess = false
            try {
                val setLocalIpv4 = builderClass.getMethod("setLocalIpv4Address", linkAddressClass)
                setLocalIpv4.invoke(builder, cgnatAddr)
                setAddrSuccess = true
                log("setLocalIpv4Address(100.64.0.1/24) set")
            } catch (e: NoSuchMethodException) {
                log("setLocalIpv4Address not found, trying alternative...")
            }

            // Try setStaticIpv4Addresses (Android 13+)
            if (!setAddrSuccess) {
                try {
                    val clientAddr = linkAddrCtor.newInstance("100.64.0.2/24")
                    val setStatic = builderClass.getMethod(
                        "setStaticIpv4Addresses",
                        linkAddressClass, linkAddressClass
                    )
                    setStatic.invoke(builder, cgnatAddr, clientAddr)
                    setAddrSuccess = true
                    log("setStaticIpv4Addresses(100.64.0.1/24, 100.64.0.2/24) set")
                } catch (e: NoSuchMethodException) {
                    log("setStaticIpv4Addresses not found either")
                }
            }

            if (!setAddrSuccess) {
                // List available Builder methods for debugging
                val methods = builderClass.methods.map { "${it.name}(${it.parameterTypes.joinToString(",") { p -> p.simpleName }})" }
                log("Builder methods: ${methods.joinToString(", ")}")
                log("ERROR: No method to set custom IP address on TetheringRequest")
                return log.toString()
            }

            // Exempt from entitlement check (carrier restrictions)
            try {
                val setExempt = builderClass.getMethod("setExemptFromEntitlementCheck", Boolean::class.javaPrimitiveType)
                setExempt.invoke(builder, true)
                log("setExemptFromEntitlementCheck(true) set")
            } catch (_: NoSuchMethodException) {
                log("setExemptFromEntitlementCheck not available")
            }

            try {
                val setShowUi = builderClass.getMethod("setShouldShowEntitlementUi", Boolean::class.javaPrimitiveType)
                setShowUi.invoke(builder, false)
                log("setShouldShowEntitlementUi(false) set")
            } catch (_: NoSuchMethodException) {
                log("setShouldShowEntitlementUi not available")
            }

            // List all Builder methods for debugging
            val builderMethods = builderClass.methods
                .filter { it.declaringClass == builderClass }
                .map { "${it.name}(${it.parameterTypes.joinToString(",") { p -> p.simpleName }})" }
            log("Builder methods: ${builderMethods.joinToString(", ")}")

            // Build the request
            val buildMethod = builderClass.getMethod("build")
            val request = buildMethod.invoke(builder)
            log("TetheringRequest built: ${request?.javaClass?.name}")

            // Dump request fields for debugging
            try {
                for (field in request!!.javaClass.declaredFields) {
                    field.isAccessible = true
                    val value = field.get(request)
                    if (value != null) {
                        log("  request.${field.name} = $value")
                    }
                }
            } catch (_: Exception) {}

            // Step 3: Stop current tethering first
            log("--- Stopping current tethering ---")
            val isConnectorForStop = tetheringManager.javaClass.name.contains("ITetheringConnector")
            try {
                if (isConnectorForStop) {
                    // ITetheringConnector.stopTethering(int type, String callerPkg, IIntResultListener, int attributionUid)
                    val stopLatch = java.util.concurrent.CountDownLatch(1)
                    val stopListener = object : android.os.Binder() {
                        override fun onTransact(code: Int, data: android.os.Parcel, reply: android.os.Parcel?, flags: Int): Boolean {
                            if (code == 1) {
                                data.enforceInterface("android.net.IIntResultListener")
                                val rc = data.readInt()
                                Log.i(TAG, "stopTethering result: $rc")
                                stopLatch.countDown()
                                return true
                            }
                            return super.onTransact(code, data, reply, flags)
                        }
                    }
                    val listenerStub = Class.forName("android.net.IIntResultListener\$Stub")
                    val asIface = listenerStub.getMethod("asInterface", android.os.IBinder::class.java)
                    val listenerProxy = asIface.invoke(null, stopListener)

                    val stopMethod = tetheringManager.javaClass.methods.first { it.name == "stopTethering" }
                    val pCount = stopMethod.parameterCount
                    when (pCount) {
                        4 -> stopMethod.invoke(tetheringManager, 0, "com.castla.mirror", listenerProxy, 0)
                        3 -> stopMethod.invoke(tetheringManager, 0, "com.castla.mirror", listenerProxy)
                        else -> stopMethod.invoke(tetheringManager, 0)
                    }
                    log("ITetheringConnector.stopTethering called")
                    stopLatch.await(5, java.util.concurrent.TimeUnit.SECONDS)
                } else {
                    val stopMethod = tmClass.getMethod("stopTethering", Int::class.javaPrimitiveType)
                    stopMethod.invoke(tetheringManager, 0)
                    log("TetheringManager.stopTethering(WIFI) called")
                }
                Thread.sleep(2000)
                log("Waited 2s for tethering to stop")
            } catch (e: Exception) {
                log("stopTethering failed: ${e.cause?.message ?: e.message}")
            }

            // Step 4: Start tethering with CGNAT request
            log("--- Starting tethering with CGNAT ---")

            // startTethering(TetheringRequest, Executor, StartTetheringCallback)
            // We need to create a callback
            val callbackClass = Class.forName("android.net.TetheringManager\$StartTetheringCallback")

            // Create a dynamic proxy for the callback
            val resultHolder = arrayOfNulls<String>(1)
            val latch = java.util.concurrent.CountDownLatch(1)

            val callback = java.lang.reflect.Proxy.newProxyInstance(
                callbackClass.classLoader,
                arrayOf(callbackClass)
            ) { _, method, args ->
                when (method.name) {
                    "onTetheringStarted" -> {
                        Log.i(TAG, "Tethering started successfully!")
                        resultHolder[0] = "SUCCESS"
                        latch.countDown()
                    }
                    "onTetheringFailed" -> {
                        val errorCode = args?.firstOrNull() ?: "unknown"
                        Log.e(TAG, "Tethering failed: $errorCode")
                        resultHolder[0] = "FAILED: $errorCode"
                        latch.countDown()
                    }
                    else -> {}
                }
                null
            }

            // Use a direct executor (run callback on same thread)
            val directExecutor = java.util.concurrent.Executor { it.run() }

            // Determine if we have a real TetheringManager or an ITetheringConnector
            val isConnector = tetheringManager.javaClass.name.contains("ITetheringConnector")
            log("Instance type: ${tetheringManager.javaClass.name} (isConnector=$isConnector)")

            if (isConnector) {
                // ITetheringConnector.startTethering(TetheringRequestParcel, String callerPkg, IIntResultListener, int attributionUid)
                log("Using ITetheringConnector.startTethering directly")

                // Convert TetheringRequest to TetheringRequestParcel
                val parcelClass = try {
                    Class.forName("android.net.TetheringRequestParcel")
                } catch (_: ClassNotFoundException) {
                    // On some versions, TetheringRequest IS the parcel
                    request!!.javaClass
                }

                // Try to get the parcel from the request
                val parcel = try {
                    val getParcel = request!!.javaClass.getMethod("getParcel")
                    getParcel.invoke(request)
                } catch (_: NoSuchMethodException) {
                    try {
                        // Direct field access
                        val field = request!!.javaClass.getDeclaredField("mBuilderParcel")
                        field.isAccessible = true
                        field.get(request)
                    } catch (_: Exception) {
                        request // Use request directly
                    }
                }
                log("Parcel type: ${parcel?.javaClass?.name}")

                // Find startTethering on connector
                var connStartMethod: java.lang.reflect.Method? = null
                for (m in tetheringManager.javaClass.methods) {
                    if (m.name == "startTethering") {
                        log("Connector.startTethering(${m.parameterTypes.joinToString(",") { it.simpleName }})")
                        connStartMethod = m
                    }
                }

                if (connStartMethod != null) {
                    val params = connStartMethod.parameterTypes
                    // ITetheringConnector.startTethering(TetheringRequestParcel, String, IIntResultListener, int)
                    // Create IIntResultListener
                    val listenerClass = Class.forName("android.net.IIntResultListener")
                    val listenerStub = Class.forName("android.net.IIntResultListener\$Stub")

                    val listener = object : android.os.Binder() {
                        override fun onTransact(code: Int, data: android.os.Parcel, reply: android.os.Parcel?, flags: Int): Boolean {
                            if (code == 1) { // onResult
                                data.enforceInterface("android.net.IIntResultListener")
                                val resultCode = data.readInt()
                                Log.i(TAG, "IIntResultListener.onResult: $resultCode")
                                if (resultCode == 0) { // TETHER_ERROR_NO_ERROR
                                    resultHolder[0] = "SUCCESS"
                                } else {
                                    resultHolder[0] = "FAILED: error=$resultCode"
                                }
                                latch.countDown()
                                return true
                            }
                            return super.onTransact(code, data, reply, flags)
                        }
                    }

                    // Wrap as IIntResultListener
                    val asInterface = listenerStub.getMethod("asInterface", android.os.IBinder::class.java)
                    val listenerProxy = asInterface.invoke(null, listener)

                    when (params.size) {
                        4 -> connStartMethod.invoke(tetheringManager, parcel, "com.castla.mirror", listenerProxy, 0)
                        3 -> connStartMethod.invoke(tetheringManager, parcel, "com.castla.mirror", listenerProxy)
                        else -> {
                            log("Unexpected param count: ${params.size}")
                            return log.toString()
                        }
                    }
                    log("ITetheringConnector.startTethering called")
                } else {
                    log("ERROR: No startTethering on connector")
                    return log.toString()
                }
            } else {
                // Real TetheringManager — use its startTethering(TetheringRequest, Executor, Callback)
                var startMethod: java.lang.reflect.Method? = null
                for (m in tmClass.methods) {
                    if (m.name == "startTethering" && m.parameterCount == 3) {
                        val params = m.parameterTypes
                        log("Found startTethering(${params.joinToString(",") { it.simpleName }})")
                        if (params[0].isAssignableFrom(request!!.javaClass)) {
                            startMethod = m
                            break
                        }
                    }
                }

                if (startMethod == null) {
                    log("ERROR: No matching startTethering method")
                    return log.toString()
                }

                startMethod.invoke(tetheringManager, request, directExecutor, callback)
                log("TetheringManager.startTethering called")
            }

            log("Waiting for callback...")

            // Wait up to 10 seconds
            val completed = latch.await(10, java.util.concurrent.TimeUnit.SECONDS)
            if (completed) {
                log("Tethering result: ${resultHolder[0]}")
            } else {
                log("Tethering callback timeout (10s)")
            }

            // Step 5: Verify the new IP
            Thread.sleep(1000)
            val addrs = execCommand("ip addr show")
            val relevantLines = addrs.lines().filter {
                it.contains("100.64") || it.contains("swlan") || it.contains("wlan") || it.contains("ap0")
            }
            log("--- Verification ---")
            for (line in relevantLines) {
                log("  $line")
            }

            if (addrs.contains("100.64.0.1")) {
                log("*** SUCCESS: CGNAT IP 100.64.0.1 is active! ***")
                return log.toString()
            } else {
                log("*** WARNING: 100.64.0.1 not found in interface addresses ***")
                log("TetheringManager approach failed, trying cmd fallback...")
            }

        } catch (e: Exception) {
            log("EXCEPTION: ${e.javaClass.simpleName}: ${e.cause?.message ?: e.message}")
            Log.e(TAG, "restartTetheringWithCgnat failed", e)
        }

        // Fallback: Try shell commands to configure tethering
        log("")
        log("=== Fallback: Shell commands ===")

        // Method A: cmd connectivity tethering
        val cmdTether = execCommand("cmd connectivity tethering wifi 2>&1").trim()
        log("cmd connectivity tethering wifi: $cmdTether")

        // Method B: Try to directly modify the hotspot IP via ip commands
        // Find current hotspot interface
        val ifaceResult = execCommand("ip -o addr show | grep -E 'swlan|wlan|ap0' | head -1").trim()
        log("Hotspot interface: $ifaceResult")
        val iface = ifaceResult.split(":").getOrNull(1)?.trim()?.split(" ")?.firstOrNull() ?: "swlan0"

        // Try to add CGNAT IP as secondary address
        val addResult = execCommand("ip addr add 100.64.0.1/24 dev $iface 2>&1").trim()
        log("ip addr add 100.64.0.1/24 dev $iface: $addResult")

        // Verify
        val finalAddrs = execCommand("ip addr show dev $iface 2>&1").trim()
        log("Final $iface addresses:")
        for (line in finalAddrs.lines().filter { it.contains("inet") }) {
            log("  $line")
        }

        if (finalAddrs.contains("100.64.0.1")) {
            log("*** SUCCESS via ip addr add! ***")
        } else {
            log("*** All methods failed ***")

            // Method C: Try using ndc
            val ndcResult = execCommand("ndc interface setcfg $iface 100.64.0.1 24 up 2>&1").trim()
            log("ndc setcfg: $ndcResult")

            // Method D: Try svc wifi
            val svcResult = execCommand("svc wifi 2>&1").trim()
            log("svc wifi help: $svcResult")
        }

        return log.toString()
    }

    /**
     * Get TetheringManager instance via system context (standard Shizuku pattern).
     */
    private fun getTetheringManager(tmClass: Class<*>): Any? {
        // Get tethering binder first — needed for all approaches
        val smClass = Class.forName("android.os.ServiceManager")
        val getService = smClass.getMethod("getService", String::class.java)
        val binder = getService.invoke(null, "tethering") as? android.os.IBinder
        if (binder == null) {
            Log.e(TAG, "No tethering binder found")
            return null
        }
        Log.i(TAG, "Got tethering binder via ServiceManager")

        // Method 1: Construct TetheringManager with Looper prepared
        try {
            // Prepare looper on this thread if needed (ActivityThread.systemMain needs it)
            if (android.os.Looper.myLooper() == null) {
                android.os.Looper.prepare()
                Log.i(TAG, "Looper prepared on current thread")
            }

            val atClass = Class.forName("android.app.ActivityThread")
            // Use currentActivityThread() instead of systemMain() — less side effects
            val currentAt = try {
                val currentMethod = atClass.getMethod("currentActivityThread")
                currentMethod.invoke(null)
            } catch (_: Exception) { null }

            val at = currentAt ?: run {
                val systemMain = atClass.getMethod("systemMain")
                systemMain.invoke(null)
            }

            val getSystemContext = atClass.getMethod("getSystemContext")
            val context = getSystemContext.invoke(at) as android.content.Context
            Log.i(TAG, "Got system context via ActivityThread")

            // Now construct TetheringManager with real context and binder supplier
            val supplierClass = Class.forName("java.util.function.Supplier")
            val supplier = java.util.function.Supplier { binder }
            val ctor = tmClass.getDeclaredConstructor(
                android.content.Context::class.java, supplierClass
            )
            ctor.isAccessible = true
            val tm = ctor.newInstance(context, supplier)
            Log.i(TAG, "Created TetheringManager via (Context, Supplier) ctor")
            return tm
        } catch (e: Exception) {
            Log.w(TAG, "ActivityThread+Supplier approach failed: ${e.cause?.message ?: e.message}")
        }

        // Method 2: Try with null context but real binder (some implementations accept this)
        try {
            val supplierClass = Class.forName("java.util.function.Supplier")
            val supplier = java.util.function.Supplier { binder }
            for (ctor in tmClass.declaredConstructors) {
                val params = ctor.parameterTypes
                Log.i(TAG, "TM ctor: ${params.joinToString(",") { it.simpleName }}")
                if (params.size == 2 && params[1] == supplierClass) {
                    ctor.isAccessible = true
                    // Pass a minimal context stub
                    val tm = ctor.newInstance(null, supplier)
                    Log.i(TAG, "Created TetheringManager with null context")
                    return tm
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Null context approach failed: ${e.message}")
        }

        // Method 3: Use ITetheringConnector directly — call its startTethering
        try {
            val connectorStub = Class.forName("android.net.ITetheringConnector\$Stub")
            val asInterface = connectorStub.getMethod("asInterface", android.os.IBinder::class.java)
            val connector = asInterface.invoke(null, binder)
            Log.i(TAG, "Falling back to ITetheringConnector directly")
            return connector
        } catch (e: Exception) {
            Log.w(TAG, "ITetheringConnector fallback failed: ${e.message}")
        }

        return null
    }

    override fun isAlive(): Boolean = true

    override fun destroy() {
        virtualDisplays.values.forEach { it.release() }
        virtualDisplays.clear()
        Log.i(TAG, "PrivilegedService destroyed")
    }
}
