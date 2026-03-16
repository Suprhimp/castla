package com.castla.mirror

import android.Manifest
import android.app.Activity
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import com.castla.mirror.shizuku.ShizukuSetup
import com.castla.mirror.ui.SettingsScreen
import com.castla.mirror.ui.StreamSettings
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val TESLA_VIRTUAL_IP = "100.99.9.9"
        private const val CGNAT_IP = "100.64.0.1"
        private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        private const val SHIZUKU_APK_URL = "https://github.com/RikkaApps/Shizuku/releases/download/v13.5.4/shizuku-v13.5.4.r1049.0e53409-release.apk"
        private const val SHIZUKU_APK_NAME = "shizuku.apk"
    }

    private var isStreaming by mutableStateOf(false)
    private var serverUrl by mutableStateOf("")
    private var currentIp by mutableStateOf("0.0.0.0")
    private var sessionPin by mutableStateOf("")
    private var showSettings by mutableStateOf(false)
    private var streamSettings by mutableStateOf(StreamSettings())
    private var shizukuInstalled by mutableStateOf(false)
    private var shizukuRunning by mutableStateOf(false)
    private var shizukuDownloading by mutableStateOf(false)
    private var downloadProgress by mutableStateOf(0) // 0-100
    private var downloadStatusText by mutableStateOf("")
    private var downloadId: Long = -1
    private var networkDiagLog by mutableStateOf("")
    private var downloadPollingJob: kotlinx.coroutines.Job? = null

    private lateinit var networkMonitor: NetworkMonitor
    private lateinit var shizukuSetup: ShizukuSetup
    private var mirrorService: MirrorForegroundService? = null
    private var serviceBound = false
    private var bindRequested = false
    private var teslaIpReady by mutableStateOf(false)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as MirrorForegroundService.LocalBinder
            mirrorService = localBinder.service
            sessionPin = localBinder.service.sessionPin ?: ""
            // Only update isStreaming from service if we didn't just request a start.
            // The service pipeline may still be initializing (isRunning == false) even
            // though we already set isStreaming = true in startMirrorService().
            if (!isStreaming) {
                isStreaming = localBinder.service.isRunning
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
            Toast.makeText(this, "Screen capture granted!", Toast.LENGTH_SHORT).show()
            startMirrorService(result.resultCode, result.data!!)
        } else {
            Toast.makeText(this, "Screen capture denied (code=${result.resultCode})", Toast.LENGTH_LONG).show()
            Log.w(TAG, "Screen capture denied or failed")
        }
    }

    private val audioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Proceed to screen capture regardless — audio will silently fail if denied
        requestScreenCapture()
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Proceed regardless — notification may not show but service still works
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
            Toast.makeText(this, "VPN permission required for Tesla connection", Toast.LENGTH_LONG).show()
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        networkMonitor = NetworkMonitor(this)
        networkMonitor.startMonitoring()
        streamSettings = StreamSettings.load(this)

        // Register download completion receiver
        registerReceiver(downloadReceiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), RECEIVER_EXPORTED)

        // Initialize Shizuku for Tesla IP alias
        shizukuInstalled = isShizukuInstalled()
        shizukuSetup = ShizukuSetup()
        if (shizukuInstalled) {
            shizukuSetup.init()
        }

        // Request required permissions on app start
        requestStartupPermissions()

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

        // Listen for Shizuku state changes
        lifecycleScope.launch {
            shizukuSetup.state.collect { shizukuState ->
                Log.i(TAG, "Shizuku state: $shizukuState")
                shizukuRunning = shizukuState is com.castla.mirror.shizuku.ShizukuState.Running
            }
        }

        // Listen for PrivilegedService connection
        lifecycleScope.launch {
            shizukuSetup.serviceConnected.collect { connected ->
                Log.i(TAG, "Shizuku PrivilegedService connected: $connected")
            }
        }

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                if (showSettings) {
                    SettingsScreen(
                        settings = streamSettings,
                        isStreaming = isStreaming,
                        onSettingsChanged = { newSettings ->
                            streamSettings = newSettings
                            StreamSettings.save(this@MainActivity, newSettings)
                        },
                        onBackClick = { showSettings = false }
                    )
                } else {
                    CastlaScreen(
                        isStreaming = isStreaming,
                        serverUrl = serverUrl,
                        currentIp = currentIp,
                        pin = sessionPin,
                        shizukuInstalled = shizukuInstalled,
                        shizukuRunning = shizukuRunning,
                        teslaIpReady = teslaIpReady,
                        onStartClick = { onStartMirroring() },
                        onStopClick = { stopMirrorService() },
                        onSettingsClick = { showSettings = true },
                        shizukuDownloading = shizukuDownloading,
                        downloadProgress = downloadProgress,
                        downloadStatusText = downloadStatusText,
                        onInstallShizuku = { downloadAndInstallShizuku() },
                        onOpenShizuku = { openShizukuApp() },
                        networkDiagLog = networkDiagLog
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Re-check Shizuku after returning from install/setup
        val wasInstalled = shizukuInstalled
        shizukuInstalled = isShizukuInstalled()
        if (shizukuInstalled && !wasInstalled) {
            shizukuSetup.init()
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
        try { unregisterReceiver(downloadReceiver) } catch (_: Exception) {}
        networkMonitor.stopMonitoring()
        stopTeslaVpn()
        shizukuSetup.release()
        super.onDestroy()
    }

    private fun updateServerUrl() {
        // Use sslip.io wildcard DNS to wrap the private IP in a domain name.
        // Tesla browser blocks direct private IP navigation but allows domain names
        // that resolve to private IPs via DNS.
        val ip = currentIp
        if (ip != "0.0.0.0" && ip.isNotEmpty()) {
            val sslipDomain = ip.replace('.', '-') + ".sslip.io"
            serverUrl = "http://${sslipDomain}:8080"
        } else {
            serverUrl = "http://${ip}:8080"
        }
    }

    private fun tryStartTeslaVpn() {
        if (teslaIpReady) return

        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            Log.i(TAG, "Requesting VPN permission")
            vpnPermissionLauncher.launch(prepareIntent)
        } else {
            // Already have VPN permission
            startTeslaVpn()
        }
    }

    private fun startTeslaVpn() {
        val intent = Intent(this, TeslaVpnService::class.java)
        startService(intent)
        teslaIpReady = true
        updateServerUrl()
        Log.i(TAG, "Tesla VPN started with IP $TESLA_VIRTUAL_IP")

        // If Shizuku service is already connected, set up networking immediately
        if (shizukuSetup.serviceConnected.value) {
            lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                setupNetworkForTesla()
            }
        }
        // Otherwise, the serviceConnected collector above will trigger setupNetworkForTesla
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

        // Verify: check if 100.64.0.1 is now on a hotspot interface
        val verify = shizukuSetup.exec("ip addr show") ?: ""
        if (verify.contains(CGNAT_IP)) {
            Log.i(TAG, "SUCCESS: CGNAT IP $CGNAT_IP is active!")
            networkDiagLog += "\n*** SUCCESS: CGNAT tethering active! ***"
            // Stop VPN — not needed, phone is directly reachable on 100.64.0.1
            stopService(Intent(this, TeslaVpnService::class.java))
            teslaIpReady = true
            runOnUiThread { updateServerUrl() }
        } else {
            Log.w(TAG, "$CGNAT_IP NOT found — keeping VPN as fallback")
            networkDiagLog += "\n*** CGNAT tethering NOT active — VPN fallback ***"
        }
    }

    private fun findHotspotInterface(): String? {
        // Samsung: swlan0, Pixel: wlan1, Generic: ap0
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

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
            if (id != downloadId) return

            shizukuDownloading = false
            downloadPollingJob?.cancel()
            downloadProgress = 0
            downloadStatusText = ""
            val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
            val query = DownloadManager.Query().setFilterById(id)
            val cursor = dm.query(query)

            if (cursor.moveToFirst()) {
                val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                if (cursor.getInt(statusIndex) == DownloadManager.STATUS_SUCCESSFUL) {
                    Log.i(TAG, "Shizuku APK downloaded, starting install")
                    installShizukuApk()
                } else {
                    Toast.makeText(context, "Download failed", Toast.LENGTH_LONG).show()
                }
            }
            cursor.close()
        }
    }

    private fun downloadAndInstallShizuku() {
        if (shizukuDownloading) return

        // Delete old APK if exists
        val apkFile = java.io.File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), SHIZUKU_APK_NAME)
        if (apkFile.exists()) {
            // Already downloaded — just install
            installShizukuApk()
            return
        }

        shizukuDownloading = true
        Toast.makeText(this, "Downloading Shizuku...", Toast.LENGTH_SHORT).show()

        val request = DownloadManager.Request(android.net.Uri.parse(SHIZUKU_APK_URL))
            .setTitle("Shizuku")
            .setDescription("Downloading Shizuku for Tesla connection")
            .setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, SHIZUKU_APK_NAME)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)

        val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
        downloadId = dm.enqueue(request)
        Log.i(TAG, "Shizuku download started: id=$downloadId")
        startDownloadProgressPolling()
    }

    private fun startDownloadProgressPolling() {
        downloadPollingJob?.cancel()
        downloadPollingJob = lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
            while (shizukuDownloading) {
                try {
                    val query = DownloadManager.Query().setFilterById(downloadId)
                    val cursor = dm.query(query)
                    if (cursor.moveToFirst()) {
                        val statusIdx = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                        val totalIdx = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                        val downloadedIdx = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                        val reasonIdx = cursor.getColumnIndex(DownloadManager.COLUMN_REASON)

                        val status = cursor.getInt(statusIdx)
                        val total = cursor.getLong(totalIdx)
                        val downloaded = cursor.getLong(downloadedIdx)

                        when (status) {
                            DownloadManager.STATUS_RUNNING -> {
                                if (total > 0) {
                                    downloadProgress = ((downloaded * 100) / total).toInt()
                                    val mb = downloaded / (1024.0 * 1024.0)
                                    val totalMb = total / (1024.0 * 1024.0)
                                    downloadStatusText = String.format("%.1f / %.1f MB", mb, totalMb)
                                } else {
                                    downloadStatusText = "Connecting..."
                                }
                            }
                            DownloadManager.STATUS_PENDING -> {
                                downloadStatusText = "Waiting..."
                            }
                            DownloadManager.STATUS_PAUSED -> {
                                val reason = cursor.getInt(reasonIdx)
                                downloadStatusText = "Paused (reason=$reason)"
                            }
                            DownloadManager.STATUS_FAILED -> {
                                val reason = cursor.getInt(reasonIdx)
                                downloadStatusText = "Failed (error=$reason)"
                                shizukuDownloading = false
                            }
                            DownloadManager.STATUS_SUCCESSFUL -> {
                                downloadProgress = 100
                                downloadStatusText = "Complete"
                            }
                        }
                    }
                    cursor.close()
                } catch (e: Exception) {
                    Log.w(TAG, "Download polling error", e)
                }
                kotlinx.coroutines.delay(500)
            }
        }
    }

    private fun installShizukuApk() {
        val apkFile = java.io.File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), SHIZUKU_APK_NAME)
        if (!apkFile.exists()) {
            Log.e(TAG, "APK file not found: ${apkFile.absolutePath}")
            return
        }

        val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", apkFile)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    private fun openShizukuApp() {
        val intent = packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
        if (intent != null) {
            startActivity(intent)
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

        if (needed.isNotEmpty()) {
            Log.i(TAG, "Requesting startup permissions: $needed")
            startupPermissionLauncher.launch(needed.toTypedArray())
        }
    }

    private fun onStartMirroring() {
        Log.i(TAG, "onStartMirroring called")

        // Step 1: Notification permission (Android 13+) — needed for foreground service
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
        // Step 2: Audio permission (if enabled)
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
        // Step 3: Screen capture consent
        try {
            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val captureIntent = projectionManager.createScreenCaptureIntent()
            Log.i(TAG, "Launching screen capture consent dialog")
            Toast.makeText(this, "Requesting screen capture...", Toast.LENGTH_SHORT).show()
            mediaProjectionLauncher.launch(captureIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch screen capture intent", e)
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun startMirrorService(resultCode: Int, data: Intent) {
        val intent = Intent(this, MirrorForegroundService::class.java).apply {
            putExtra(MirrorForegroundService.EXTRA_RESULT_CODE, resultCode)
            putExtra(MirrorForegroundService.EXTRA_DATA, data)
            putExtra(MirrorForegroundService.EXTRA_WIDTH, streamSettings.resolution.width)
            putExtra(MirrorForegroundService.EXTRA_HEIGHT, streamSettings.resolution.height)
            putExtra(MirrorForegroundService.EXTRA_BITRATE, streamSettings.bitrate)
            putExtra(MirrorForegroundService.EXTRA_FPS, streamSettings.fps)
            putExtra(MirrorForegroundService.EXTRA_AUDIO, streamSettings.audioEnabled)
        }
        startForegroundService(intent)
        if (serviceBound || bindRequested) {
            try { unbindService(serviceConnection) } catch (_: IllegalArgumentException) {}
            serviceBound = false
            bindRequested = false
        }
        bindRequested = bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        isStreaming = true
    }

    private fun stopMirrorService() {
        if (serviceBound || bindRequested) {
            try { unbindService(serviceConnection) } catch (_: IllegalArgumentException) {}
            serviceBound = false
            bindRequested = false
        }
        stopService(Intent(this, MirrorForegroundService::class.java))
        mirrorService = null
        isStreaming = false
        sessionPin = ""
        updateServerUrl()
    }
}

@Composable
fun CastlaScreen(
    isStreaming: Boolean,
    serverUrl: String,
    currentIp: String,
    pin: String,
    shizukuInstalled: Boolean,
    shizukuRunning: Boolean,
    teslaIpReady: Boolean,
    shizukuDownloading: Boolean = false,
    downloadProgress: Int = 0,
    downloadStatusText: String = "",
    onStartClick: () -> Unit,
    onStopClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onInstallShizuku: () -> Unit,
    onOpenShizuku: () -> Unit,
    networkDiagLog: String = ""
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(48.dp))

            Text(
                text = "Castla",
                fontSize = 36.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            Text(
                text = "Tesla Screen Mirroring",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            TextButton(onClick = onSettingsClick) {
                Text("Settings")
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Status indicator
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(if (isStreaming) Color(0xFF4CAF50) else Color(0xFF757575))
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isStreaming) "Streaming Active" else "Ready to Stream",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (isStreaming) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            if (isStreaming) {
                // PIN display
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "Open Tesla Browser",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(16.dp))

                        // URL
                        Text(
                            text = serverUrl,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center
                        )

                        Spacer(modifier = Modifier.height(20.dp))

                        Text(
                            text = "PIN",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))

                        // Large PIN display
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            pin.forEach { digit ->
                                Card(
                                    shape = RoundedCornerShape(12.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.primary
                                    )
                                ) {
                                    Text(
                                        text = digit.toString(),
                                        fontSize = 36.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
            }

            // Shizuku setup card
            if (!teslaIpReady) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFF2D2000)
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Text(
                            text = "Tesla Setup Required",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFFFB300)
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        if (!shizukuInstalled) {
                            Text(
                                text = "Tesla browser needs Shizuku to connect.\nTap below to download and install automatically.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFFFD54F),
                                lineHeight = 18.sp
                            )
                            Spacer(modifier = Modifier.height(12.dp))

                            if (shizukuDownloading) {
                                // Progress bar + status
                                LinearProgressIndicator(
                                    progress = downloadProgress / 100f,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(8.dp)
                                        .clip(RoundedCornerShape(4.dp)),
                                    color = Color(0xFFFFB300),
                                    trackColor = Color(0xFF5D4600),
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = if (downloadStatusText.isNotEmpty()) downloadStatusText else "Downloading...",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color(0xFFFFD54F)
                                    )
                                    Text(
                                        text = "${downloadProgress}%",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFFFFB300)
                                    )
                                }
                            } else {
                                Button(
                                    onClick = onInstallShizuku,
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(8.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFFFFB300)
                                    )
                                ) {
                                    Text("Install Shizuku", color = Color.Black)
                                }
                            }
                        } else if (!shizukuRunning) {
                            Text(
                                text = "Shizuku Setup Steps:",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFFFD54F)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "1. Settings > About Phone > Software Info\n" +
                                    "   > Tap Build Number 7 times\n" +
                                    "2. Settings > Developer Options\n" +
                                    "   > Wireless Debugging > ON\n" +
                                    "3. Open Shizuku > \"Start via Wireless Debugging\"",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFFFD54F),
                                lineHeight = 18.sp
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Button(
                                onClick = onOpenShizuku,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFFFFB300)
                                )
                            ) {
                                Text("Open Shizuku", color = Color.Black)
                            }
                        } else {
                            Text(
                                text = "Shizuku is running. Setting up Tesla connection...",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFFFD54F)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Main action button
            Button(
                onClick = if (isStreaming) onStopClick else onStartClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = if (isStreaming)
                    ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                else
                    ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Text(
                    text = if (isStreaming) "Stop Mirroring" else "Start Mirroring",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            if (!isStreaming) {
                Spacer(modifier = Modifier.height(24.dp))

                // Instructions
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "How to use",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "1. Connect phone and Tesla to the same WiFi\n" +
                                "2. Tap \"Start Mirroring\" above\n" +
                                "3. Open the URL in Tesla's browser\n" +
                                "4. Enter the 4-digit PIN\n" +
                                "5. Your phone screen will appear on Tesla",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 20.sp
                        )
                    }
                }
            }

            // Network diagnostics
            if (networkDiagLog.isNotEmpty()) {
                val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
                Spacer(modifier = Modifier.height(16.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFF1A1A2E)
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Network Setup Log",
                                style = MaterialTheme.typography.titleSmall,
                                color = Color(0xFF00BCD4)
                            )
                            TextButton(onClick = {
                                clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(networkDiagLog))
                            }) {
                                Text("Copy", fontSize = 12.sp, color = Color(0xFF00BCD4))
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        SelectionContainer {
                            Text(
                                text = networkDiagLog,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFB0BEC5),
                                fontSize = 10.sp,
                                lineHeight = 14.sp
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
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
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f).padding(start = 16.dp)
        )
    }
}
