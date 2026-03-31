package com.castla.mirror

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import com.castla.mirror.BuildConfig
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import com.castla.mirror.billing.BillingManager
import com.castla.mirror.billing.LicenseManager
import com.castla.mirror.server.MirrorServer
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import java.util.regex.Pattern
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import android.net.VpnService
import com.castla.mirror.network.NetworkMonitor
import com.castla.mirror.network.NetworkState
import com.castla.mirror.network.TeslaVpnService
import com.castla.mirror.service.MirrorForegroundService
import com.castla.mirror.service.TeslaBleScanner
import com.castla.mirror.service.TeslaDetectNotifier
import com.castla.mirror.shizuku.ShizukuSetup
import com.castla.mirror.ui.SettingsScreen
import com.castla.mirror.ui.MirroringMode
import com.castla.mirror.ui.StreamSettings
import com.castla.mirror.ui.MeshGradientBackground
import com.castla.mirror.ui.glassCard
import com.castla.mirror.update.ForceUpdateDialog
import com.castla.mirror.update.UpdateManager
import com.castla.mirror.update.UpdateManagerFactory
import com.castla.mirror.ads.AdManager
import com.castla.mirror.ads.BannerAd
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val TESLA_VIRTUAL_IP = "100.99.9.9"
        private const val CGNAT_IP = "192.168.43.1"
        private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        // TODO: Replace with your actual website URL where you'll host the Markdown guide
        private const val SHIZUKU_GUIDE_URL = "https://website-ten-sigma-42.vercel.app/setup"
    }

    private var isStreaming by mutableStateOf(false)
    private var isPreparing by mutableStateOf(false)
    private var serverUrl by mutableStateOf("")
    private var currentIp by mutableStateOf("0.0.0.0")
    private var showSettings by mutableStateOf(false)
    private var streamSettings by mutableStateOf(StreamSettings())
    private var shizukuInstalled by mutableStateOf(false)
    private var shizukuRunning by mutableStateOf(false)
    private var shizukuPermitted by mutableStateOf(false)
    private var showShizukuPermissionDialog by mutableStateOf(false)
    private var networkDiagLog by mutableStateOf("")
    private var teslaAssociated by mutableStateOf(false)
    private var teslaAutoDetectEnabled by mutableStateOf(false)
    /** Tracks whether this app turned on the hotspot so we only turn it off if we turned it on */
    private var hotspotEnabledByApp = false
    /** Guard against double-handling CDM association on API 33+ */
    private var cdmAssociationHandled = false
    private var teslaBleScanner: TeslaBleScanner? = null

    private lateinit var networkMonitor: NetworkMonitor
    private lateinit var shizukuSetup: ShizukuSetup
    private lateinit var billingManager: BillingManager
    private lateinit var updateManager: UpdateManager
    private var mirrorService: MirrorForegroundService? = null
    private var serviceBound = false
    private var bindRequested = false
    private var teslaIpReady by mutableStateOf(false)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as MirrorForegroundService.LocalBinder
            mirrorService = localBinder.service
            if (!isStreaming) {
                isStreaming = localBinder.service.isRunning
            }
            val serviceWasAlreadyRunning = localBinder.service.isRunning
            if (streamSettings.mirroringMode == MirroringMode.FULL_SCREEN && !serviceWasAlreadyRunning) {
                localBinder.service.setBrowserConnectionListener { connected ->
                    if (connected) runOnUiThread { moveTaskToBack(true) }
                }
            }

            localBinder.service.setPurchaseRequestListener {
                val intent = Intent(this@MainActivity, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra("start_billing", true)
                }
                startActivity(intent)
            }
            updateServerUrl()
            serviceBound = true
            bindRequested = false
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            mirrorService = null
            serviceBound = false
        }
    }

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        Log.i(TAG, "MediaProjection result: code=${result.resultCode}, data=${result.data != null}")
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            Toast.makeText(this, getString(R.string.toast_screen_capture_granted), Toast.LENGTH_SHORT).show()
            startMirrorService(result.resultCode, result.data!!)
        } else {
            isPreparing = false
            Toast.makeText(this, getString(R.string.toast_screen_capture_denied, result.resultCode), Toast.LENGTH_LONG).show()
            Log.w(TAG, "Screen capture denied or failed")
        }
    }

    private val audioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        requestScreenCapture()
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        proceedAfterNotificationPermission()
    }

    private val startupPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        Log.i(TAG, "Startup permissions: $results")
    }

    private val blePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            startBleScanner()
        } else {
            Log.w(TAG, "BLE scan permission denied — BLE fallback won't be available")
        }
    }

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startTeslaVpn()
        } else {
            Log.w(TAG, "VPN permission denied")
            Toast.makeText(this, getString(R.string.toast_vpn_permission_required), Toast.LENGTH_LONG).show()
        }
    }

    private val cdmAssociationLauncher: ActivityResultLauncher<IntentSenderRequest> =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                handleCdmAssociationResult(result.data!!)
            } else {
                Log.w(TAG, "CDM association cancelled or failed: code=${result.resultCode}")
                Toast.makeText(this, getString(R.string.toast_tesla_pairing_cancelled), Toast.LENGTH_SHORT).show()
            }
        }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        LicenseManager.init(this)
        billingManager = BillingManager(this)
        billingManager.init()

        updateManager = UpdateManagerFactory.create()
        updateManager.checkForUpdate(this)

        networkMonitor = NetworkMonitor(this)
        networkMonitor.startMonitoring()
        streamSettings = StreamSettings.load(this)

        shizukuInstalled = isShizukuInstalled()
        shizukuSetup = ShizukuSetup()
        if (shizukuInstalled) {
            shizukuSetup.init()
        }

        refreshCdmState()
        requestStartupPermissions()

        // Handle intent extra to open settings (e.g. from screenshot automation)
        if (intent?.getBooleanExtra("open_settings", false) == true) {
            showSettings = true
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                networkMonitor.state.collect { state ->
                    when (state) {
                        is NetworkState.Connected -> {
                            currentIp = state.ip
                            updateServerUrl()
                        }
                        is NetworkState.Disconnected -> {
                            currentIp = "0.0.0.0"
                            updateServerUrl()
                        }
                    }
                }
            }
        }

        // Sync UI streaming state when service stops externally (e.g. notification action)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                MirrorForegroundService.serviceRunningFlow.collect { running ->
                    if (!running && isStreaming) {
                        isStreaming = false
                        isPreparing = false
                        mirrorService = null
                        if (serviceBound || bindRequested) {
                            try { unbindService(serviceConnection) } catch (_: IllegalArgumentException) {}
                            serviceBound = false
                            bindRequested = false
                        }
                        // Tear down hotspot if the app enabled it (thermal auto-stop, crash, etc.)
                        if (hotspotEnabledByApp && shizukuSetup.serviceConnected.value) {
                            hotspotEnabledByApp = false
                            lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                disableHotspot()
                            }
                        }
                        updateServerUrl()
                    }
                }
            }
        }


        lifecycleScope.launch {
            shizukuSetup.state.collect { shizukuState ->
                Log.i(TAG, "Shizuku state: $shizukuState")
                shizukuRunning = shizukuState is com.castla.mirror.shizuku.ShizukuState.Running
                val wasPermitted = shizukuPermitted
                shizukuPermitted = shizukuState is com.castla.mirror.shizuku.ShizukuState.Running && shizukuState.permitted
                // Auto-continue mirroring after Shizuku permission granted
                if (!wasPermitted && shizukuPermitted && showShizukuPermissionDialog) {
                    showShizukuPermissionDialog = false
                    onStartMirroringAfterAd()
                }
            }
        }

        lifecycleScope.launch {
            shizukuSetup.serviceConnected.collect { connected ->
                Log.i(TAG, "Shizuku PrivilegedService connected: $connected")
            }
        }

        setContent {
            val isPremium by LicenseManager.isPremium.collectAsState()
            val freeCredits by AdManager.freeCredits.collectAsState()

            MaterialTheme(colorScheme = darkColorScheme()) {
                updateManager.ForceUpdateOverlay(this@MainActivity)

                if (showSettings) {
                    val thermalStatus by (mirrorService?.thermalStatus
                        ?: kotlinx.coroutines.flow.MutableStateFlow(0)).collectAsState()
                    SettingsScreen(
                        settings = streamSettings,
                        isStreaming = isStreaming,
                        isPremium = isPremium,
                        thermalStatus = thermalStatus,
                        onSettingsChanged = { newSettings ->
                            streamSettings = newSettings
                            StreamSettings.save(this@MainActivity, newSettings)
                        },
                        onBackClick = { showSettings = false },
                        onUpgradeClick = { billingManager.launchPurchaseFlow(this@MainActivity) }
                    )
                } else {
                    CastlaScreen(
                        isStreaming = isStreaming,
                        isPreparing = isPreparing,
                        serverUrl = serverUrl,
                        shizukuInstalled = shizukuInstalled,
                        shizukuRunning = shizukuRunning,
                        shizukuPermitted = shizukuPermitted,
                        teslaIpReady = teslaIpReady,
                        isPremium = isPremium,
                        freeCredits = freeCredits,
                        onStartClick = { onStartMirroring() },
                        onStopClick = { stopMirrorService() },
                        onSettingsClick = { showSettings = true },
                        onUpgradeClick = { billingManager.launchPurchaseFlow(this@MainActivity) },
                        onWatchAdClick = {
                            AdManager.watchAdForCredits(this@MainActivity) {}
                        },
                        onInstallShizuku = { openShizukuInstallGuide() },
                        onOpenShizuku = { openShizukuApp() },
                        onGrantShizukuPermission = { shizukuSetup.requestPermission() },
                        networkDiagLog = networkDiagLog,
                        teslaAutoDetectEnabled = teslaAutoDetectEnabled,
                        onToggleAutoDetect = {
                            if (teslaAssociated) disassociateTesla() else associateTesla()
                        }
                    )
                }
            }
        }

        if (intent?.getBooleanExtra("start_mirroring", false) == true && !isStreaming) {
            Log.i(TAG, "Start mirroring triggered from widget (cold launch)")
            onStartMirroring()
        }
        
        handleNewIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleNewIntent(intent)
    }
    
    private fun handleNewIntent(intent: Intent?) {
        if (intent == null) return
        
        if (intent.getBooleanExtra("start_billing", false)) {
            Log.i(TAG, "Purchase flow triggered from browser banner")
            wakeUpScreen()
            billingManager.launchPurchaseFlow(this)
        }
        if (intent.getBooleanExtra("start_mirroring", false) && !isStreaming) {
            Log.i(TAG, "Start mirroring triggered from widget")
            onStartMirroring()
        }
        if (intent.getBooleanExtra("open_settings", false)) {
            showSettings = true
        }
    }
    
    private fun wakeUpScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
            keyguardManager.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
            )
        }
    }

    override fun onResume() {
        super.onResume()
        updateManager.onResume(this)
    }

    override fun onStart() {
        super.onStart()
        val wasInstalled = shizukuInstalled
        shizukuInstalled = isShizukuInstalled()
        if (shizukuInstalled && !wasInstalled) {
            shizukuSetup.init()
        }
        refreshCdmState()

        // Restart BLE scanner if auto-detect is on and CDM presence not available
        if (teslaAutoDetectEnabled && Build.VERSION.SDK_INT < Build.VERSION_CODES.S
            && teslaBleScanner == null && hasBleScanPermissions()) {
            startBleScanner()
        }

        if (!serviceBound && !bindRequested) {
            val intent = Intent(this, MirrorForegroundService::class.java)
            bindRequested = bindService(intent, serviceConnection, 0)
        }
    }

    override fun onStop() {
        if (serviceBound || bindRequested) {
            try { unbindService(serviceConnection) } catch (_: IllegalArgumentException) {}
            serviceBound = false
            bindRequested = false
        }
        super.onStop()
    }

    override fun onDestroy() {
        updateManager.destroy()
        billingManager.destroy()
        networkMonitor.stopMonitoring()
        stopBleScanner()
        stopTeslaVpn()
        shizukuSetup.release()
        super.onDestroy()
    }

    private fun updateServerUrl() {
        val cellularIp = getCellularIpv4Address()
        val hotspotIp = currentIp

        val ip = when {
            cellularIp != null && !cellularIp.startsWith("10.") -> cellularIp
            hotspotIp != "0.0.0.0" && hotspotIp.isNotEmpty() -> hotspotIp
            else -> "0.0.0.0"
        }

        if (ip != "0.0.0.0") {
            val sslipDomain = ip.replace('.', '-') + ".sslip.io"
            serverUrl = "http://${sslipDomain}:${MirrorServer.DEFAULT_PORT}"
        } else {
            serverUrl = "http://${ip}:${MirrorServer.DEFAULT_PORT}"
        }
    }

    private fun getCellularIpv4Address(): String? {
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                val name = iface.name.lowercase()
                if (name.contains("wlan") || name.contains("swlan") || name.contains("ap")) continue

                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress && addr.address.size == 4) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get cellular IP", e)
        }
        return null
    }

    private fun tryStartTeslaVpn() {
        if (teslaIpReady) return

        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            Log.i(TAG, "Requesting VPN permission")
            vpnPermissionLauncher.launch(prepareIntent)
        } else {
            startTeslaVpn()
        }
    }

    private fun startTeslaVpn() {
        val intent = Intent(this, TeslaVpnService::class.java)
        startService(intent)
        teslaIpReady = true
        updateServerUrl()
        Log.i(TAG, "Tesla VPN started with IP $TESLA_VIRTUAL_IP")

        if (shizukuSetup.serviceConnected.value) {
            lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                setupNetworkForTesla()
            }
        }
    }

    private fun setupNetworkForTesla() {
        Log.i(TAG, "setupNetworkForTesla: restarting tethering with CGNAT IP")

        val result = shizukuSetup.restartTetheringWithCgnat()
        if (result == null) {
            Log.w(TAG, "restartTetheringWithCgnat returned null — service not connected")
            return
        }
        Log.i(TAG, "CGNAT tethering result:\n$result")
        networkDiagLog = result

        val verify = shizukuSetup.exec("ip addr show") ?: ""
        if (verify.contains(CGNAT_IP)) {
            Log.i(TAG, "SUCCESS: CGNAT IP $CGNAT_IP is active!")
            networkDiagLog += "\n*** SUCCESS: CGNAT tethering active! ***"
            stopService(Intent(this, TeslaVpnService::class.java))
            teslaIpReady = true
            runOnUiThread { updateServerUrl() }
        } else {
            Log.w(TAG, "$CGNAT_IP NOT found — keeping VPN as fallback")
            networkDiagLog += "\n*** CGNAT tethering NOT active — VPN fallback ***"
        }
    }

    /**
     * Enable WiFi tethering (hotspot) via Shizuku's privileged service.
     * Uses TetheringManager/ConnectivityManager Java API (most reliable).
     */
    private suspend fun enableHotspot(): Boolean {
        if (!shizukuSetup.serviceConnected.value) {
            Log.w(TAG, "enableHotspot: Shizuku service not connected")
            return false
        }
        val success = shizukuSetup.startWifiTethering()
        Log.i(TAG, "enableHotspot: startWifiTethering returned $success")
        if (success) {
            // Give tethering time to initialize
            kotlinx.coroutines.delay(2000)
        }
        return success
    }

    /**
     * Disable WiFi tethering (hotspot) via Shizuku's privileged service.
     */
    private fun disableHotspot() {
        if (!shizukuSetup.serviceConnected.value) {
            Log.w(TAG, "disableHotspot: Shizuku service not connected")
            return
        }
        val success = shizukuSetup.stopWifiTethering()
        Log.i(TAG, "disableHotspot: stopWifiTethering returned $success")
    }

    private fun findHotspotInterface(): String? {
        val candidates = listOf("swlan0", "wlan1", "ap0", "softap0")
        val result = shizukuSetup.exec("ip -o addr show") ?: return null
        for (name in candidates) {
            if (result.contains(name)) return name
        }
        return null
    }

    private fun stopTeslaVpn() {
        stopService(Intent(this, TeslaVpnService::class.java))
        teslaIpReady = false
        updateServerUrl()
    }

    private fun isShizukuInstalled(): Boolean {
        return try {
            packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun openShizukuInstallGuide() {
        // Open the setup guide on our website (Play Store is no longer available for Shizuku)
        try {
            val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse(SHIZUKU_GUIDE_URL))
            startActivity(webIntent)
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.toast_could_not_open_browser), Toast.LENGTH_LONG).show()
        }
    }

    private fun openShizukuApp() {
        val intent = packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
        if (intent != null) {
            startActivity(intent)
        }
    }

    private fun refreshCdmState() {
        val hasAssociations: Boolean
        try {
            val cdm = getSystemService(Context.COMPANION_DEVICE_SERVICE) as? CompanionDeviceManager
            if (cdm == null) {
                Log.w(TAG, "CompanionDeviceManager not available")
                teslaAssociated = false
                teslaAutoDetectEnabled = false
                return
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val assocs = cdm.myAssociations
                hasAssociations = assocs.isNotEmpty()
                if (hasAssociations) {
                    for (assoc in assocs) {
                        val mac = assoc.deviceMacAddress?.toString()?.uppercase()
                        if (mac != null) {
                            try {
                                cdm.startObservingDevicePresence(mac)
                            } catch (e: Exception) {
                                Log.w(TAG, "Re-observe failed for $mac", e)
                            }
                        }
                    }
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                @Suppress("DEPRECATION")
                val addrs = cdm.associations
                hasAssociations = addrs.isNotEmpty()
                for (addr in addrs) {
                    try {
                        cdm.startObservingDevicePresence(addr)
                    } catch (e: Exception) {
                        Log.w(TAG, "Re-observe failed for $addr", e)
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                hasAssociations = cdm.associations.isNotEmpty()
            }
        } catch (e: Exception) {
            Log.e(TAG, "CDM initialization failed (emulator?)", e)
            teslaAssociated = false
            teslaAutoDetectEnabled = false
            return
        }
        teslaAssociated = hasAssociations
        teslaAutoDetectEnabled = hasAssociations
        // BLE scanner only needed on pre-S where CDM can't observe device presence
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            if (hasAssociations && teslaBleScanner == null && hasBleScanPermissions()) {
                startBleScanner()
            } else if (!hasAssociations) {
                stopBleScanner()
            }
        } else {
            // CDM handles presence detection — stop BLE if running
            stopBleScanner()
        }
        Log.i(TAG, "CDM associations: teslaAssociated=$teslaAssociated")
    }

    private fun associateTesla() {
        // Filter to Tesla vehicles only (BT name starts with "Tesla ")
        val teslaPattern = java.util.regex.Pattern.compile("^Tesla .*", java.util.regex.Pattern.CASE_INSENSITIVE)
        val deviceFilter = BluetoothDeviceFilter.Builder()
            .setNamePattern(teslaPattern)
            .build()

        val request = AssociationRequest.Builder()
            .addDeviceFilter(deviceFilter)
            .setSingleDevice(false)
            .build()

        val cdm = getSystemService(Context.COMPANION_DEVICE_SERVICE) as CompanionDeviceManager

        cdmAssociationHandled = false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            cdm.associate(request, mainExecutor, object : CompanionDeviceManager.Callback() {
                override fun onAssociationPending(intentSender: android.content.IntentSender) {
                    cdmAssociationLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
                }

                override fun onAssociationCreated(associationInfo: android.companion.AssociationInfo) {
                    if (cdmAssociationHandled) return
                    cdmAssociationHandled = true
                    Log.i(TAG, "CDM association created directly: id=${associationInfo.id}")
                    onTeslaAssociated(associationInfo.id)
                }

                override fun onFailure(error: CharSequence?) {
                    Log.e(TAG, "CDM association failed: $error")
                    Toast.makeText(this@MainActivity, getString(R.string.toast_tesla_pairing_failed, error), Toast.LENGTH_LONG).show()
                }
            })
        } else {
            @Suppress("DEPRECATION")
            cdm.associate(request, object : CompanionDeviceManager.Callback() {
                @Deprecated("Deprecated in API 33")
                override fun onDeviceFound(chooserLauncher: android.content.IntentSender) {
                    cdmAssociationLauncher.launch(IntentSenderRequest.Builder(chooserLauncher).build())
                }

                override fun onAssociationPending(intentSender: android.content.IntentSender) {
                    cdmAssociationLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
                }

                override fun onAssociationCreated(associationInfo: android.companion.AssociationInfo) {
                    Log.i(TAG, "CDM association created: id=${associationInfo.id}")
                    onTeslaAssociated(associationInfo.id)
                }

                override fun onFailure(error: CharSequence?) {
                    Log.e(TAG, "CDM association failed: $error")
                    Toast.makeText(this@MainActivity, getString(R.string.toast_tesla_pairing_failed, error), Toast.LENGTH_LONG).show()
                }
            }, null)
        }
    }

    private fun handleCdmAssociationResult(data: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (cdmAssociationHandled) {
                Log.i(TAG, "CDM association already handled via onAssociationCreated, skipping ActivityResult")
                return
            }
            cdmAssociationHandled = true
            val associationInfo = data.getParcelableExtra(
                CompanionDeviceManager.EXTRA_ASSOCIATION,
                android.companion.AssociationInfo::class.java
            )
            if (associationInfo != null) {
                onTeslaAssociated(associationInfo.id)
            }
        } else {
            @Suppress("DEPRECATION")
            val device = data.getParcelableExtra<BluetoothDevice>(CompanionDeviceManager.EXTRA_DEVICE)
            if (device != null) {
                try {
                    Log.i(TAG, "CDM legacy association: ${device.name} [${device.address}]")
                    val cdm = getSystemService(Context.COMPANION_DEVICE_SERVICE) as CompanionDeviceManager
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        cdm.startObservingDevicePresence(device.address)
                    }
                    teslaAssociated = true
                    teslaAutoDetectEnabled = true
                    Toast.makeText(this, getString(R.string.toast_tesla_paired_device, device.name), Toast.LENGTH_SHORT).show()
                } catch (e: SecurityException) {
                    Log.w(TAG, "CDM legacy association: SecurityException accessing device", e)
                    refreshCdmState()
                    if (teslaAssociated) {
                        Toast.makeText(this, getString(R.string.toast_tesla_paired), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun onTeslaAssociated(associationId: Int) {
        val cdm = getSystemService(Context.COMPANION_DEVICE_SERVICE) as CompanionDeviceManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val assoc = cdm.myAssociations.find { it.id == associationId }
            val macAddress = assoc?.deviceMacAddress?.toString()?.uppercase()
            if (macAddress != null) {
                cdm.startObservingDevicePresence(macAddress)
                Log.i(TAG, "Observing device presence for $macAddress (assocId=$associationId)")
            } else {
                Log.w(TAG, "No MAC address in association $associationId")
                runOnUiThread {
                    Toast.makeText(this, getString(R.string.toast_bluetooth_permission_missing), Toast.LENGTH_LONG).show()
                }
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            @Suppress("DEPRECATION")
            val addresses = cdm.associations
            for (addr in addresses) {
                cdm.startObservingDevicePresence(addr)
                Log.i(TAG, "Observing device presence for $addr")
            }
        }
        teslaAssociated = true
        teslaAutoDetectEnabled = true
        // BLE scanner only needed on pre-S where CDM can't observe device presence
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            if (hasBleScanPermissions()) {
                startBleScanner()
            } else {
                requestBleScanPermissions()
            }
        }
        runOnUiThread {
            Toast.makeText(this, getString(R.string.toast_tesla_paired_auto_detect), Toast.LENGTH_SHORT).show()
        }
    }

    private fun disassociateTesla() {
        val cdm = getSystemService(Context.COMPANION_DEVICE_SERVICE) as CompanionDeviceManager
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                for (assoc in cdm.myAssociations) {
                    cdm.disassociate(assoc.id)
                    Log.i(TAG, "Disassociated CDM id=${assoc.id}")
                }
            } else {
                @Suppress("DEPRECATION")
                for (addr in cdm.associations) {
                    @Suppress("DEPRECATION")
                    cdm.disassociate(addr)
                    Log.i(TAG, "Disassociated CDM addr=$addr")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "CDM disassociate failed", e)
        }
        teslaAssociated = false
        teslaAutoDetectEnabled = false
        stopBleScanner()
        Toast.makeText(this, getString(R.string.toast_tesla_auto_detect_disabled), Toast.LENGTH_SHORT).show()
    }

    // ── BLE Fallback Scanner (runs alongside CDM) ─────────────────────

    private fun startBleScanner() {
        if (teslaBleScanner != null) return
        teslaBleScanner = TeslaBleScanner(this).also {
            it.start {
                TeslaDetectNotifier.showTeslaDetectedNotification(this)
            }
        }
        Log.i(TAG, "BLE fallback scanner started")
    }

    private fun stopBleScanner() {
        teslaBleScanner?.stop()
        teslaBleScanner = null
    }

    private fun hasBleScanPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        } else {
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestBleScanPermissions() {
        val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        blePermissionLauncher.launch(needed)
    }

    private fun requestStartupPermissions() {
        val needed = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.RECORD_AUDIO)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.BLUETOOTH_SCAN)
            }
        }

        if (needed.isNotEmpty()) {
            Log.i(TAG, "Requesting startup permissions: $needed")
            startupPermissionLauncher.launch(needed.toTypedArray())
        }
    }

    private fun onStartMirroring() {
        Log.i(TAG, "onStartMirroring called")

        // Credit system: if no credits, show ad first; otherwise deduct and proceed
        AdManager.onMirroringStart(this) {
            onStartMirroringAfterAd()
        }
    }

    private fun onStartMirroringAfterAd() {
        isPreparing = true
        Log.i(TAG, "isPreparing=true (starting permission flow)")

        // Check if Shizuku is running but permission not granted
        if (shizukuInstalled && shizukuRunning && !shizukuPermitted) {
            Log.i(TAG, "Shizuku running but not permitted, requesting permission")
            showShizukuPermissionDialog = true
            shizukuSetup.requestPermission()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.i(TAG, "Requesting POST_NOTIFICATIONS permission")
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }

        proceedAfterNotificationPermission()
    }

    private fun proceedAfterNotificationPermission() {
        if (streamSettings.audioEnabled &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.i(TAG, "Requesting RECORD_AUDIO permission")
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }

        requestScreenCapture()
    }

    private fun requestScreenCapture() {
        try {
            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val captureIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                projectionManager.createScreenCaptureIntent(
                    MediaProjectionConfig.createConfigForDefaultDisplay()
                )
            } else {
                projectionManager.createScreenCaptureIntent()
            }
            Log.i(TAG, "Launching screen capture consent dialog")
            Toast.makeText(this, getString(R.string.toast_requesting_screen_capture), Toast.LENGTH_SHORT).show()
            mediaProjectionLauncher.launch(captureIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch screen capture intent", e)
            Toast.makeText(this, getString(R.string.toast_error, e.message), Toast.LENGTH_LONG).show()
        }
    }

    private fun startMirrorService(resultCode: Int, data: Intent) {
        // Auto-enable hotspot if setting is on and Shizuku is available
        if (streamSettings.autoHotspot && shizukuSetup.serviceConnected.value) {
            Toast.makeText(this, getString(R.string.toast_hotspot_enabling), Toast.LENGTH_SHORT).show()
            lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                val success = enableHotspot()
                hotspotEnabledByApp = success
                runOnUiThread {
                    if (success) {
                        Toast.makeText(this@MainActivity, getString(R.string.toast_hotspot_enabled), Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@MainActivity, getString(R.string.toast_hotspot_failed), Toast.LENGTH_SHORT).show()
                    }
                    updateServerUrl()
                }
            }
        }

        val intent = Intent(this, MirrorForegroundService::class.java).apply {
            putExtra(MirrorForegroundService.EXTRA_RESULT_CODE, resultCode)
            putExtra(MirrorForegroundService.EXTRA_DATA, data)
            putExtra(MirrorForegroundService.EXTRA_MAX_RESOLUTION, streamSettings.maxResolution.maxHeight)
            putExtra(MirrorForegroundService.EXTRA_FPS, streamSettings.fps)
            putExtra(MirrorForegroundService.EXTRA_AUDIO, streamSettings.audioEnabled)
            putExtra(MirrorForegroundService.EXTRA_MIRRORING_MODE, streamSettings.mirroringMode.name)
            putExtra(MirrorForegroundService.EXTRA_TARGET_PACKAGE, streamSettings.targetAppPackage)
        }
        startForegroundService(intent)
        if (serviceBound || bindRequested) {
            try { unbindService(serviceConnection) } catch (_: IllegalArgumentException) {}
            serviceBound = false
            bindRequested = false
        }
        bindRequested = bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        isStreaming = true
        Log.i(TAG, "isStreaming=true, isPreparing=$isPreparing (service started)")

        // 서비스 바인드 + 서버 실행 확인 후 최소 2초 뒤에 preparing 해제
        lifecycleScope.launch {
            val startTime = System.currentTimeMillis()
            // 서비스가 실제로 바인드되고 서버가 실행될 때까지 대기
            while (mirrorService?.isRunning != true && isPreparing) {
                kotlinx.coroutines.delay(100)
            }
            // 최소 표시 시간 보장 (2초)
            val elapsed = System.currentTimeMillis() - startTime
            val remaining = 2000L - elapsed
            if (remaining > 0) {
                kotlinx.coroutines.delay(remaining)
            }
            if (isPreparing) {
                isPreparing = false
                Log.i(TAG, "isPreparing=false (service ready, elapsed=${System.currentTimeMillis() - startTime}ms)")
            }
        }
    }

    private fun stopMirrorService() {
        // Auto-disable hotspot if we enabled it
        if (hotspotEnabledByApp && shizukuSetup.serviceConnected.value) {
            hotspotEnabledByApp = false
            lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                disableHotspot()
                runOnUiThread {
                    Toast.makeText(this@MainActivity, getString(R.string.toast_hotspot_disabled), Toast.LENGTH_SHORT).show()
                }
            }
        }

        if (serviceBound || bindRequested) {
            try { unbindService(serviceConnection) } catch (_: IllegalArgumentException) {}
            serviceBound = false
            bindRequested = false
        }
        stopService(Intent(this, MirrorForegroundService::class.java))
        mirrorService = null
        isStreaming = false
        isPreparing = false
        updateServerUrl()
        com.castla.mirror.widget.MirrorWidgetProvider.updateAllWidgets(this)
    }
}

@Composable
fun CastlaScreen(
    isStreaming: Boolean,
    isPreparing: Boolean = false,
    serverUrl: String,
    shizukuInstalled: Boolean,
    shizukuRunning: Boolean,
    shizukuPermitted: Boolean = false,
    teslaIpReady: Boolean,
    isPremium: Boolean = false,
    freeCredits: Int = 0,
    onStartClick: () -> Unit,
    onStopClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onUpgradeClick: () -> Unit = {},
    onWatchAdClick: () -> Unit = {},
    onInstallShizuku: () -> Unit,
    onOpenShizuku: () -> Unit,
    onGrantShizukuPermission: () -> Unit = {},
    networkDiagLog: String = "",
    teslaAutoDetectEnabled: Boolean = false,
    onToggleAutoDetect: () -> Unit = {}
) {
    MeshGradientBackground {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(48.dp))

            Text(
                text = stringResource(id = R.string.app_name),
                fontSize = 42.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Color.White
            )

            Text(
                text = stringResource(id = R.string.subtitle_tesla_screen_mirroring),
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.7f)
            )

            Spacer(modifier = Modifier.height(16.dp))

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White.copy(alpha = 0.1f))
                    .clickable { onSettingsClick() }
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = Icons.Default.Settings, contentDescription = "Settings", tint = Color.White, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(id = R.string.btn_settings), color = Color.White, fontWeight = FontWeight.Bold)
                }
            }

            if (!isPremium) {
                Spacer(modifier = Modifier.height(24.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .glassCard()
                        .padding(20.dp)
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = stringResource(id = R.string.title_castla_pro),
                            fontSize = 22.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color(0xFFFFD700)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(id = R.string.desc_castla_pro_features),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.8f),
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = onUpgradeClick,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFFFD700)
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = stringResource(id = R.string.btn_upgrade_pro),
                                color = Color.Black,
                                fontWeight = FontWeight.ExtraBold
                            )
                        }
                    }
                }
            }

            // Free mirroring credits card (free users only)
            if (!isPremium) {
                Spacer(modifier = Modifier.height(16.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .glassCard()
                        .padding(20.dp)
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = stringResource(id = R.string.title_free_mirroring),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = if (freeCredits > 0)
                                stringResource(id = R.string.desc_free_credits, freeCredits)
                            else
                                stringResource(id = R.string.desc_no_credits),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (freeCredits > 0) Color(0xFF69F0AE) else Color.White.copy(alpha = 0.6f),
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = onWatchAdClick,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF00BCD4)
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = stringResource(id = R.string.btn_watch_ad),
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .glassCard()
                    .padding(20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (teslaAutoDetectEnabled) stringResource(id = R.string.title_tesla_auto_detect_on) else stringResource(id = R.string.title_tesla_auto_detect),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (teslaAutoDetectEnabled) Color(0xFF69F0AE) else Color.White
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (teslaAutoDetectEnabled)
                                stringResource(id = R.string.desc_tesla_auto_detect_on)
                            else
                                stringResource(id = R.string.desc_tesla_auto_detect_off),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.6f)
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Switch(
                        checked = teslaAutoDetectEnabled,
                        onCheckedChange = { onToggleAutoDetect() },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = Color(0xFF69F0AE),
                            uncheckedThumbColor = Color.White.copy(alpha = 0.7f),
                            uncheckedTrackColor = Color.White.copy(alpha = 0.2f),
                            uncheckedBorderColor = Color.Transparent
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                if (isPreparing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = Color(0xFFFFB300),
                        strokeWidth = 2.dp
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(if (isStreaming) Color(0xFF69F0AE) else Color.White.copy(alpha = 0.5f))
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = when {
                        isPreparing -> stringResource(id = R.string.status_preparing)
                        isStreaming -> stringResource(id = R.string.status_streaming_active)
                        else -> stringResource(id = R.string.status_ready_to_stream)
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = when {
                        isPreparing -> Color(0xFFFFB300)
                        isStreaming -> Color(0xFF69F0AE)
                        else -> Color.White.copy(alpha = 0.7f)
                    },
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            AnimatedVisibility(visible = isStreaming) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .glassCard()
                        .padding(24.dp)
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = stringResource(id = R.string.title_open_tesla_browser),
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(16.dp))

                        Text(
                            text = serverUrl,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color(0xFF69F0AE),
                            textAlign = TextAlign.Center
                        )

                    }
                }
            }

            if (isStreaming) {
                Spacer(modifier = Modifier.height(24.dp))
            }

            AnimatedVisibility(visible = !teslaIpReady) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color(0xFF2D2000).copy(alpha = 0.8f))
                        .border(1.dp, Color(0xFFFFB300).copy(alpha = 0.3f), RoundedCornerShape(24.dp))
                        .padding(20.dp)
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = stringResource(id = R.string.title_tesla_setup_required),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color(0xFFFFB300)
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        if (!shizukuInstalled) {
                            Text(
                                text = stringResource(id = R.string.desc_shizuku_install_required),
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color(0xFFFFD54F),
                                lineHeight = 20.sp
                            )
                            Spacer(modifier = Modifier.height(16.dp))

                            Button(
                                onClick = onInstallShizuku,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFFFFB300)
                                )
                            ) {
                                Text(stringResource(id = R.string.btn_how_to_install_shizuku), color = Color.Black, fontWeight = FontWeight.Bold)
                            }
                        } else if (!shizukuRunning) {
                            Text(
                                text = stringResource(id = R.string.title_shizuku_setup_steps),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFFFD54F)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = stringResource(id = R.string.desc_shizuku_setup_steps),
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFFFD54F),
                                lineHeight = 20.sp
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(
                                onClick = onOpenShizuku,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFFFFB300)
                                )
                            ) {
                                Text(stringResource(id = R.string.btn_open_shizuku), color = Color.Black, fontWeight = FontWeight.Bold)
                            }
                        } else if (!shizukuPermitted) {
                            Text(
                                text = stringResource(id = R.string.desc_shizuku_permission_required),
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color(0xFFFFD54F),
                                lineHeight = 20.sp
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(
                                onClick = onGrantShizukuPermission,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFFFF6D00)
                                )
                            ) {
                                Text(stringResource(id = R.string.btn_grant_shizuku_permission), color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        } else {
                            Text(
                                text = stringResource(id = R.string.status_shizuku_running_setup),
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color(0xFFFFD54F),
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }

            if (!teslaIpReady) {
                Spacer(modifier = Modifier.height(24.dp))
            }

            Button(
                onClick = if (isStreaming) onStopClick else onStartClick,
                enabled = !isPreparing || isStreaming,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp),
                shape = RoundedCornerShape(20.dp),
                colors = when {
                    isPreparing && !isStreaming -> ButtonDefaults.buttonColors(
                        disabledContainerColor = Color.White.copy(alpha = 0.3f),
                        disabledContentColor = Color.Black.copy(alpha = 0.5f)
                    )
                    isStreaming -> ButtonDefaults.buttonColors(containerColor = Color(0xFFFF5252))
                    else -> ButtonDefaults.buttonColors(containerColor = Color.White)
                }
            ) {
                if (isPreparing && !isStreaming) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = Color.Black.copy(alpha = 0.5f),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                }
                Text(
                    text = when {
                        isPreparing && !isStreaming -> stringResource(id = R.string.status_preparing)
                        isStreaming -> stringResource(id = R.string.btn_stop_mirroring)
                        else -> stringResource(id = R.string.btn_start_mirroring)
                    },
                    fontSize = 20.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = if (isStreaming) Color.White else Color.Black
                )
            }

            if (!isStreaming) {
                Spacer(modifier = Modifier.height(32.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .glassCard()
                        .padding(20.dp)
                ) {
                    Column {
                        Text(
                            text = stringResource(id = R.string.title_how_to_use),
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = stringResource(id = R.string.desc_how_to_use),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.7f),
                            lineHeight = 24.sp
                        )
                    }
                }
            }

            if (networkDiagLog.isNotEmpty()) {
                val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
                Spacer(modifier = Modifier.height(24.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.Black.copy(alpha = 0.4f))
                        .padding(16.dp)
                ) {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(id = R.string.title_network_setup_log),
                                style = MaterialTheme.typography.titleSmall,
                                color = Color(0xFF00BCD4),
                                fontWeight = FontWeight.Bold
                            )
                            TextButton(onClick = {
                                clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(networkDiagLog))
                            }) {
                                Text(stringResource(id = R.string.btn_copy), fontSize = 12.sp, color = Color(0xFF00BCD4), fontWeight = FontWeight.Bold)
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        SelectionContainer {
                            Text(
                                text = networkDiagLog,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFB0BEC5),
                                fontSize = 11.sp,
                                lineHeight = 16.sp,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                            )
                        }
                    }
                }
            }

                Spacer(modifier = Modifier.height(48.dp))
            }

            // Banner ad at the bottom (free users only)
            BannerAd()
        }
    }
}

@Composable
fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.7f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = Color.White,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f).padding(start = 16.dp)
        )
    }
}