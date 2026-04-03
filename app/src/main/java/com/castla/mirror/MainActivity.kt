package com.castla.mirror

import android.Manifest
import android.app.Activity
import com.castla.mirror.BuildConfig
import android.app.DownloadManager
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
import androidx.core.content.FileProvider
import java.io.File
import com.castla.mirror.server.MirrorServer
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import java.util.regex.Pattern
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
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
import com.castla.mirror.service.HotspotClientDetector
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
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val MIRROR_START_TIMEOUT_MS = 15_000L
        private const val TESLA_VIRTUAL_IP = "100.99.9.9"
        private const val CGNAT_IP = "192.168.43.1"
        private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        private const val SHIZUKU_RELEASES_API = "https://api.github.com/repos/RikkaApps/Shizuku/releases/latest"
        private const val SHIZUKU_APK_FILENAME = "shizuku.apk"
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
    private var teslaAutoDetectEnabled by mutableStateOf(false)
    private var hotspotEnabledByApp = false
    private var teslaBleScanner: TeslaBleScanner? = null

    // Shizuku download state
    private var shizukuDownloadId: Long = -1L
    private var shizukuDownloadProgress by mutableFloatStateOf(-1f) // -1 = not downloading
    private var downloadProgressJob: kotlinx.coroutines.Job? = null

    private lateinit var networkMonitor: NetworkMonitor
    private lateinit var shizukuSetup: ShizukuSetup
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        updateManager = UpdateManagerFactory.create()
        updateManager.checkForUpdate(this)

        networkMonitor = NetworkMonitor(this)
        networkMonitor.startMonitoring()
        streamSettings = StreamSettings.load(this)

        shizukuInstalled = isShizukuInstalled()
        shizukuSetup = ShizukuSetup()
        if (shizukuInstalled) {
            shizukuSetup.init(bindService = false)
        }

        loadAutoDetectState()
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

        // Sync UI streaming state when service stops externally
        // (e.g. notification action, thermal auto-stop, crash)
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
                        updateServerUrl()
                        // Clean up hotspot that was auto-enabled by the app
                        if (hotspotEnabledByApp && shizukuSetup.serviceConnected.value) {
                            hotspotEnabledByApp = false
                            lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                disableHotspot()
                                Log.i(TAG, "Auto-disabled hotspot after external service stop")
                            }
                        }
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
                    onStartMirroring()
                }
            }
        }

        lifecycleScope.launch {
            shizukuSetup.serviceConnected.collect { connected ->
                Log.i(TAG, "Shizuku PrivilegedService connected: $connected")
            }
        }

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                updateManager.ForceUpdateOverlay(this@MainActivity)

                val thermalStatus by (mirrorService?.thermalStatus
                    ?: kotlinx.coroutines.flow.MutableStateFlow(0)).collectAsState()

                if (showSettings) {
                    BackHandler { showSettings = false }
                    SettingsScreen(
                        settings = streamSettings,
                        isStreaming = isStreaming,
                        thermalStatus = thermalStatus,
                        onSettingsChanged = { newSettings ->
                            streamSettings = newSettings
                            StreamSettings.save(this@MainActivity, newSettings)
                        },
                        onBackClick = { showSettings = false }
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
                        onStartClick = { onStartMirroring() },
                        onStopClick = { stopMirrorService() },
                        onSettingsClick = { showSettings = true },
                        onInstallShizuku = { downloadAndInstallShizuku() },
                        onOpenShizuku = { openShizukuApp() },
                        onGrantShizukuPermission = { shizukuSetup.requestPermission() },
                        shizukuDownloadProgress = shizukuDownloadProgress,
                        networkDiagLog = networkDiagLog,
                        teslaAutoDetectEnabled = teslaAutoDetectEnabled,
                        onToggleAutoDetect = {
                            toggleAutoDetect()
                        },
                        thermalStatus = thermalStatus,
                        currentVersion = updateManager.currentVersion,
                        latestVersion = updateManager.latestVersion,
                        updateAvailable = updateManager.updateAvailable,
                        onUpdateClick = { updateManager.startUpdate(this@MainActivity) }
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
            shizukuSetup.init(bindService = false)
        }
        loadAutoDetectState()

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
        networkMonitor.stopMonitoring()
        stopAutoDetect()
        stopTeslaVpn()
        shizukuSetup.release()
        downloadProgressJob?.cancel()
        try { unregisterReceiver(shizukuDownloadReceiver) } catch (_: IllegalArgumentException) {}
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

    private fun downloadAndInstallShizuku() {
        if (shizukuDownloadProgress >= 0f) {
            Toast.makeText(this, "Download already in progress…", Toast.LENGTH_SHORT).show()
            return
        }

        shizukuDownloadProgress = 0f
        Toast.makeText(this, "Fetching latest Shizuku release…", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                // Fetch latest release APK URL from GitHub API
                val url = java.net.URL(SHIZUKU_RELEASES_API)
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                conn.connectTimeout = 10_000
                conn.readTimeout = 10_000
                val json = conn.inputStream.bufferedReader().readText()
                conn.disconnect()

                // Parse APK download URL from assets
                val apkUrl = org.json.JSONObject(json)
                    .getJSONArray("assets")
                    .let { assets ->
                        var downloadUrl: String? = null
                        for (i in 0 until assets.length()) {
                            val asset = assets.getJSONObject(i)
                            val name = asset.getString("name")
                            if (name.endsWith(".apk")) {
                                downloadUrl = asset.getString("browser_download_url")
                                break
                            }
                        }
                        downloadUrl
                    } ?: throw Exception("No APK found in latest release")

                Log.i(TAG, "Shizuku APK URL: $apkUrl")

                runOnUiThread { startShizukuDownload(apkUrl) }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch Shizuku release info", e)
                runOnUiThread {
                    shizukuDownloadProgress = -1f
                    Toast.makeText(this@MainActivity, "Failed to fetch release info: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun startShizukuDownload(apkUrl: String) {
        // Delete any previous APK
        val apkFile = File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), SHIZUKU_APK_FILENAME)
        if (apkFile.exists()) apkFile.delete()

        val request = DownloadManager.Request(Uri.parse(apkUrl))
            .setTitle("Shizuku")
            .setDescription("Downloading Shizuku APK…")
            .setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, SHIZUKU_APK_FILENAME)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)

        val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        shizukuDownloadId = dm.enqueue(request)
        Log.i(TAG, "Shizuku download started: id=$shizukuDownloadId")
        Toast.makeText(this, "Downloading Shizuku…", Toast.LENGTH_SHORT).show()

        // Register completion receiver
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        registerReceiver(shizukuDownloadReceiver, filter, Context.RECEIVER_EXPORTED)

        startDownloadProgressPolling()
    }

    private val shizukuDownloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
            if (id != shizukuDownloadId) return

            downloadProgressJob?.cancel()
            shizukuDownloadProgress = -1f

            try {
                unregisterReceiver(this)
            } catch (_: IllegalArgumentException) {}

            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val query = DownloadManager.Query().setFilterById(id)
            val cursor = dm.query(query)
            if (cursor != null && cursor.moveToFirst()) {
                val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                if (status == DownloadManager.STATUS_SUCCESSFUL) {
                    Log.i(TAG, "Shizuku APK download complete")
                    installShizukuApk()
                } else {
                    Log.e(TAG, "Shizuku download failed with status: $status")
                    Toast.makeText(context, "Download failed. Please try again.", Toast.LENGTH_LONG).show()
                }
                cursor.close()
            }
        }
    }

    private fun startDownloadProgressPolling() {
        downloadProgressJob?.cancel()
        downloadProgressJob = lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            while (shizukuDownloadProgress >= 0f) {
                val query = DownloadManager.Query().setFilterById(shizukuDownloadId)
                val cursor = dm.query(query)
                if (cursor != null && cursor.moveToFirst()) {
                    val bytesDownloaded = cursor.getLong(
                        cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                    )
                    val bytesTotal = cursor.getLong(
                        cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                    )
                    if (bytesTotal > 0) {
                        shizukuDownloadProgress = bytesDownloaded.toFloat() / bytesTotal.toFloat()
                    }
                    cursor.close()
                }
                kotlinx.coroutines.delay(300)
            }
        }
    }

    private fun installShizukuApk() {
        val apkFile = File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), SHIZUKU_APK_FILENAME)
        if (!apkFile.exists()) {
            Log.e(TAG, "Shizuku APK file not found: ${apkFile.absolutePath}")
            Toast.makeText(this, "APK file not found.", Toast.LENGTH_LONG).show()
            return
        }

        val apkUri = FileProvider.getUriForFile(this, "${BuildConfig.APPLICATION_ID}.fileprovider", apkFile)
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(installIntent)
    }

    private fun openShizukuApp() {
        val intent = packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
        if (intent != null) {
            startActivity(intent)
        }
    }

    // ── Tesla Auto-Detect (Hotspot client + BLE) ──────────────────────

    private fun loadAutoDetectState() {
        teslaAutoDetectEnabled = getSharedPreferences("castla_settings", MODE_PRIVATE)
            .getBoolean("auto_detect_enabled", false)
        if (teslaAutoDetectEnabled) {
            startAutoDetect()
        }
    }

    private fun toggleAutoDetect() {
        teslaAutoDetectEnabled = !teslaAutoDetectEnabled
        getSharedPreferences("castla_settings", MODE_PRIVATE).edit()
            .putBoolean("auto_detect_enabled", teslaAutoDetectEnabled).apply()
        if (teslaAutoDetectEnabled) {
            startAutoDetect()
            Toast.makeText(this, getString(R.string.toast_tesla_paired_auto_detect), Toast.LENGTH_SHORT).show()
        } else {
            stopAutoDetect()
            Toast.makeText(this, getString(R.string.toast_tesla_auto_detect_disabled), Toast.LENGTH_SHORT).show()
        }
    }

    private fun startAutoDetect() {
        // BLE scanner — detects Tesla BLE advertisement
        if (teslaBleScanner == null && hasBleScanPermissions()) {
            startBleScanner()
        }
    }

    private fun stopAutoDetect() {
        stopBleScanner()
    }

    private fun startBleScanner() {
        if (teslaBleScanner != null) return
        teslaBleScanner = TeslaBleScanner(this).also {
            it.start {
                TeslaDetectNotifier.showTeslaDetectedNotification(this)
            }
        }
        Log.i(TAG, "BLE scanner started")
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

    private fun clearPreparingState(message: String? = null, stopServiceIfNeeded: Boolean = false) {
        isPreparing = false
        if (stopServiceIfNeeded) {
            try { stopService(Intent(this, MirrorForegroundService::class.java)) } catch (_: Exception) {}
            mirrorService = null
            isStreaming = false
            if (serviceBound || bindRequested) {
                try { unbindService(serviceConnection) } catch (_: IllegalArgumentException) {}
                serviceBound = false
                bindRequested = false
            }
        }
        if (!message.isNullOrBlank()) {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun onStartMirroring() {
        Log.i(TAG, "onStartMirroring called")
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
            clearPreparingState(getString(R.string.toast_error, e.message ?: "screen capture intent failed"))
        }
    }

    private fun startMirrorService(resultCode: Int, data: Intent) {
        // Auto-enable hotspot if setting is on, Shizuku is available, and hotspot is not already active
        val needHotspot = streamSettings.autoHotspot && shizukuSetup.serviceConnected.value
        if (needHotspot) {
            val alreadyActive = findHotspotInterface() != null
            if (alreadyActive) {
                Log.i(TAG, "Hotspot already active — skipping enableHotspot, starting service directly")
                launchMirrorService(resultCode, data)
            } else {
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
                        launchMirrorService(resultCode, data)
                    }
                }
            }
        } else {
            launchMirrorService(resultCode, data)
        }
    }

    private fun launchMirrorService(resultCode: Int, data: Intent) {
        val intent = Intent(this, MirrorForegroundService::class.java).apply {
            putExtra(MirrorForegroundService.EXTRA_RESULT_CODE, resultCode)
            putExtra(MirrorForegroundService.EXTRA_DATA, data)
            putExtra(MirrorForegroundService.EXTRA_MAX_RESOLUTION,
                if (streamSettings.isAutoResolution) 0 else streamSettings.maxResolution.maxHeight)
            putExtra(MirrorForegroundService.EXTRA_FPS, streamSettings.fps) // FPS_AUTO is already 0
            putExtra(MirrorForegroundService.EXTRA_AUDIO, streamSettings.audioEnabled)
            putExtra(MirrorForegroundService.EXTRA_MUTE_LOCAL_AUDIO, streamSettings.muteLocalAudio)
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
            while (mirrorService?.isRunning != true && isPreparing) {
                val elapsed = System.currentTimeMillis() - startTime
                if (elapsed >= MIRROR_START_TIMEOUT_MS) {
                    Log.w(TAG, "Mirror service start timed out after ${elapsed}ms")
                    clearPreparingState(
                        message = getString(R.string.toast_error, "mirroring start timed out"),
                        stopServiceIfNeeded = true
                    )
                    return@launch
                }
                kotlinx.coroutines.delay(100)
            }
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
        val shouldAskHotspot = hotspotEnabledByApp && shizukuSetup.serviceConnected.value

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

        // Ask user whether to turn off hotspot
        if (shouldAskHotspot) {
            android.app.AlertDialog.Builder(this)
                .setTitle(R.string.dialog_hotspot_off_title)
                .setMessage(R.string.dialog_hotspot_off_message)
                .setPositiveButton(R.string.dialog_hotspot_off_yes) { _, _ ->
                    hotspotEnabledByApp = false
                    lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        disableHotspot()
                        runOnUiThread {
                            Toast.makeText(this@MainActivity, getString(R.string.toast_hotspot_disabled), Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                .setNegativeButton(R.string.dialog_hotspot_off_no) { _, _ ->
                    hotspotEnabledByApp = false
                }
                .setCancelable(false)
                .show()
        }
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
    @Suppress("unused") teslaIpReady: Boolean,
    onStartClick: () -> Unit,
    onStopClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onInstallShizuku: () -> Unit,
    onOpenShizuku: () -> Unit,
    onGrantShizukuPermission: () -> Unit = {},
    shizukuDownloadProgress: Float = -1f,
    networkDiagLog: String = "",
    @Suppress("unused") teslaAutoDetectEnabled: Boolean = false,
    @Suppress("unused") onToggleAutoDetect: () -> Unit = {},
    @Suppress("unused") thermalStatus: Int = 0,
    currentVersion: String = "",
    latestVersion: String? = null,
    updateAvailable: Boolean = false,
    onUpdateClick: () -> Unit = {}
) {
    val context = androidx.compose.ui.platform.LocalContext.current
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

            // Version info
            if (currentVersion.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "v$currentVersion",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.5f)
                    )
                    if (updateAvailable && latestVersion != null) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFFFF6B35).copy(alpha = 0.9f))
                                .clickable { onUpdateClick() }
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "v$latestVersion ${stringResource(id = R.string.version_update_available)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    } else if (latestVersion != null) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(id = R.string.version_latest),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF69F0AE).copy(alpha = 0.7f)
                        )
                    }
                }
            }

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

            AnimatedVisibility(visible = !shizukuInstalled || !shizukuRunning || !shizukuPermitted) {
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

                            if (shizukuDownloadProgress >= 0f) {
                                // Download in progress
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    LinearProgressIndicator(
                                        progress = { shizukuDownloadProgress },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(8.dp)
                                            .clip(RoundedCornerShape(4.dp)),
                                        color = Color(0xFFFFB300),
                                        trackColor = Color.White.copy(alpha = 0.2f),
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = "Downloading… ${(shizukuDownloadProgress * 100).toInt()}%",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color(0xFFFFD54F),
                                        modifier = Modifier.fillMaxWidth(),
                                        textAlign = TextAlign.Center
                                    )
                                }
                            } else {
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
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = onOpenShizuku,
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFFFFB300)
                                    )
                                ) {
                                    Text(stringResource(id = R.string.btn_open_shizuku), color = Color.Black, fontWeight = FontWeight.Bold)
                                }
                                OutlinedButton(
                                    onClick = {
                                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://website-ten-sigma-42.vercel.app/setup"))
                                        context.startActivity(intent)
                                    },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = Color(0xFFFFB300)
                                    ),
                                    border = BorderStroke(1.dp, Color(0xFFFFB300))
                                ) {
                                    Text(stringResource(id = R.string.btn_setup_guide), fontWeight = FontWeight.Bold)
                                }
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
                        }
                    }
                }
            }

            if (!shizukuInstalled || !shizukuRunning || !shizukuPermitted) {
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