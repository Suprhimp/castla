package com.castla.mirror.shizuku

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.castla.mirror.diagnostics.DiagnosticEvent
import com.castla.mirror.diagnostics.MirrorDiagnostics
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

sealed class ShizukuState {
    object NotInstalled : ShizukuState()
    object NotRunning : ShizukuState()
    data class Running(val permitted: Boolean) : ShizukuState()
}

class ShizukuSetup {

    companion object {
        private const val TAG = "ShizukuSetup"
        private const val REQUEST_CODE = 1001
        const val USER_SERVICE_VERSION = 107
    }

    private val _state = MutableStateFlow<ShizukuState>(ShizukuState.NotInstalled)
    val state: StateFlow<ShizukuState> = _state

    private val _serviceConnected = MutableStateFlow(false)
    val serviceConnected: StateFlow<Boolean> = _serviceConnected

    var privilegedService: IPrivilegedService? = null
        private set

    private val serviceArgs = Shizuku.UserServiceArgs(
        ComponentName(
            com.castla.mirror.BuildConfig.APPLICATION_ID,
            PrivilegedService::class.java.name
        )
    ).daemon(true).processNameSuffix("privileged").version(USER_SERVICE_VERSION)

    /** Guard to prevent duplicate bind calls while a bind is in progress. */
    private var bindingInProgress = false
    private var userServiceBound = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            privilegedService = IPrivilegedService.Stub.asInterface(binder)
            _serviceConnected.value = true
            bindingInProgress = false
            userServiceBound = true
            Log.i(TAG, "Privileged service connected")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            privilegedService = null
            _serviceConnected.value = false
            bindingInProgress = false
            userServiceBound = false
            Log.i(TAG, "Privileged service disconnected")
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        Log.i(TAG, "Shizuku binder received")
        MirrorDiagnostics.log(DiagnosticEvent.SHIZUKU_BINDER_READY)
        updateState()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        Log.i(TAG, "Shizuku binder dead")
        MirrorDiagnostics.log(DiagnosticEvent.SHIZUKU_BINDER_DEAD)
        privilegedService = null
        _serviceConnected.value = false
        bindingInProgress = false
        _state.value = ShizukuState.NotRunning
    }

    private val permissionResultListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == REQUEST_CODE) {
            val granted = grantResult == PackageManager.PERMISSION_GRANTED
            Log.i(TAG, "Permission result: granted=$granted")
            _state.value = ShizukuState.Running(permitted = granted)
            if (granted) {
                bindPrivilegedService()
            }
        }
    }

    fun init(bindService: Boolean = true) {
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionResultListener)
        updateState()
        // Auto-bind if already permitted
        if (bindService && isAvailable() && hasPermission()) {
            bindPrivilegedService()
        }
    }

    fun attachPrivilegedService(service: IPrivilegedService?) {
        privilegedService = service
        _serviceConnected.value = service != null
        bindingInProgress = false
    }

    fun requestPermission() {
        if (Shizuku.isPreV11()) {
            Log.w(TAG, "Shizuku pre-v11 not supported")
            return
        }
        Shizuku.requestPermission(REQUEST_CODE)
    }

    fun isAvailable(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (_: Exception) {
            false
        }
    }

    fun hasPermission(): Boolean {
        return try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) {
            false
        }
    }

    fun bindPrivilegedService() {
        if (_serviceConnected.value) {
            Log.d(TAG, "bindPrivilegedService skipped: already connected")
            return
        }
        if (bindingInProgress) {
            Log.d(TAG, "bindPrivilegedService skipped: bind already in progress")
            return
        }
        try {
            bindingInProgress = true
            runOnMainSync {
                Shizuku.bindUserService(serviceArgs, serviceConnection)
            }
            userServiceBound = true
            Log.i(TAG, "Binding privileged service...")
        } catch (e: Exception) {
            bindingInProgress = false
            Log.e(TAG, "Failed to bind privileged service", e)
        }
    }

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

    private fun updateState() {
        _state.value = when {
            !isAvailable() -> ShizukuState.NotRunning
            hasPermission() -> ShizukuState.Running(permitted = true)
            else -> ShizukuState.Running(permitted = false)
        }
    }

    /**
     * Execute a shell command via Shizuku's privileged service (ADB-level privileges).
     * Returns the command output, or null on failure.
     */
    fun exec(command: String): String? {
        val service = privilegedService
        if (service == null) {
            Log.w(TAG, "Privileged service not connected for exec")
            // Try to bind if we have permission
            if (isAvailable() && hasPermission()) {
                bindPrivilegedService()
            }
            return null
        }
        return try {
            val output = service.execCommand(command)
            output
        } catch (e: android.os.DeadObjectException) {
            Log.w(TAG, "Privileged service dead during exec, marking null")
            privilegedService = null
            _serviceConnected.value = false
            null
        } catch (e: Exception) {
            Log.e(TAG, "exec failed: $command", e)
            null
        }
    }

    /**
     * Add IP alias via INetd binder (most reliable method with ADB-level access).
     */
    fun addInterfaceAddress(ifName: String, address: String, prefixLength: Int): Boolean {
        val service = privilegedService ?: return false
        return try {
            service.addInterfaceAddress(ifName, address, prefixLength)
        } catch (e: Exception) {
            Log.e(TAG, "addInterfaceAddress failed", e)
            false
        }
    }

    /**
     * Run comprehensive Tesla network setup — tries all methods to add IP alias.
     * Returns diagnostic log.
     */
    fun setupTeslaNetwork(ifName: String, virtualIp: String = "100.99.9.9"): String? {
        val service = privilegedService
        if (service == null) {
            Log.w(TAG, "setupTeslaNetwork: service not connected")
            if (isAvailable() && hasPermission()) {
                bindPrivilegedService()
            }
            return null
        }
        return try {
            service.setupTeslaNetworking(ifName, virtualIp)
        } catch (e: Exception) {
            Log.e(TAG, "setupTeslaNetworking failed", e)
            null
        }
    }

    /**
     * Restart WiFi tethering with CGNAT IP (100.64.0.1/24).
     * Returns diagnostic log, or null if service not connected.
     */
    fun restartTetheringWithCgnat(): String? {
        val service = privilegedService
        if (service == null) {
            Log.w(TAG, "restartTetheringWithCgnat: service not connected")
            if (isAvailable() && hasPermission()) {
                bindPrivilegedService()
            }
            return null
        }
        return try {
            service.restartTetheringWithCgnat()
        } catch (e: Exception) {
            Log.e(TAG, "restartTetheringWithCgnat failed", e)
            "Exception: ${e.message}"
        }
    }

    /**
     * Start WiFi tethering (hotspot) via the privileged service.
     * Returns true if the request was submitted.
     */
    fun startWifiTethering(): Boolean {
        val result = exec("__HOTSPOT_ON__")
        Log.i(TAG, "startWifiTethering result: $result")
        return result != null && result.startsWith("OK")
    }

    fun stopWifiTethering(): Boolean {
        val result = exec("__HOTSPOT_OFF__")
        Log.i(TAG, "stopWifiTethering result: $result")
        return result != null && result.startsWith("OK")
    }

    fun release() {
        if (userServiceBound) {
            try {
                runOnMainSync {
                    Shizuku.unbindUserService(serviceArgs, serviceConnection, true)
                }
            } catch (_: Exception) {}
        }
        privilegedService = null
        _serviceConnected.value = false
        bindingInProgress = false
        userServiceBound = false
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        Shizuku.removeRequestPermissionResultListener(permissionResultListener)
    }
}
