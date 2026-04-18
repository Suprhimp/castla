package com.castla.mirror.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.Surface
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.castla.mirror.R
import com.castla.mirror.widget.MirrorWidgetProvider
import com.castla.mirror.capture.AudioCapture

import com.castla.mirror.capture.JpegEncoder
import com.castla.mirror.capture.ScreenCaptureManager
import com.castla.mirror.capture.VideoEncoder
import com.castla.mirror.capture.VirtualDisplayManager
import com.castla.mirror.input.TouchInjector
import com.castla.mirror.server.MirrorServer
import com.castla.mirror.shizuku.IPrivilegedService
import com.castla.mirror.shizuku.ShizukuSetup
import com.castla.mirror.ott.BrowserResolver
import com.castla.mirror.ott.OttCatalog
import com.castla.mirror.utils.LaunchMode
import com.castla.mirror.policy.AutoScaleDecision
import com.castla.mirror.policy.AutoScaleInput
import com.castla.mirror.policy.AutoScalePolicy
import com.castla.mirror.policy.DisconnectPolicy
import com.castla.mirror.policy.ScreenOffAction
import com.castla.mirror.policy.ScreenOffPolicy
import com.castla.mirror.policy.ScreenOffState
import com.castla.mirror.diagnostics.DiagnosticEvent
import com.castla.mirror.diagnostics.MirrorDiagnostics
import com.castla.mirror.utils.StreamMath
import com.castla.mirror.ui.SplitWebPresentation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

class MirrorForegroundService : Service() {

    companion object {
        private const val TAG = "MirrorService"
        private const val CHANNEL_ID = "castla_mirror"
        private const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "com.castla.mirror.ACTION_STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
        const val EXTRA_MAX_RESOLUTION = "max_resolution"
        const val EXTRA_FPS = "fps"
        const val EXTRA_AUDIO = "audio_enabled"
        const val EXTRA_MUTE_LOCAL_AUDIO = "mute_local_audio"
        const val EXTRA_MIRRORING_MODE = "mirroring_mode"
        const val EXTRA_TARGET_PACKAGE = "target_package"

        /** Observable service running state – UI can collect this to stay in sync. */
        private val _serviceRunningFlow = MutableStateFlow(false)
        val serviceRunningFlow: StateFlow<Boolean> = _serviceRunningFlow

        /** True while the service is actively tearing down the previous session. */
        private val _cleanupInProgressFlow = MutableStateFlow(false)
        val cleanupInProgressFlow: StateFlow<Boolean> = _cleanupInProgressFlow

        /** Current panel-off state — UI observes this for button state. */
        private val _panelOffStateFlow = MutableStateFlow(ScreenOffState.ACTIVE)
        val panelOffStateFlow: StateFlow<ScreenOffState> = _panelOffStateFlow


        var isServiceRunning: Boolean
            get() = _serviceRunningFlow.value
            set(value) { _serviceRunningFlow.value = value }

        var isCleanupInProgress: Boolean
            get() = _cleanupInProgressFlow.value
            set(value) { _cleanupInProgressFlow.value = value }

        @JvmStatic
        var instance: MirrorForegroundService? = null
            private set

        /** Resolution/FPS tiers for auto mode, ordered from most conservative to highest. */
        data class AutoTier(val maxHeight: Int, val fps: Int, val label: String)
        val AUTO_TIERS = listOf(
            AutoTier(720, 30, "720p30"),
            AutoTier(720, 60, "720p60"),
            AutoTier(1080, 30, "1080p30"),
            AutoTier(1080, 60, "1080p60")
        )
        /** Check interval for auto-scale loop */
        private const val AUTO_SCALE_INTERVAL_MS = 10_000L
        /** Initial delay before first auto-scale evaluation */
        private const val AUTO_SCALE_INITIAL_DELAY_MS = 5_000L
        // Grace period constants are now in DisconnectPolicy
        /** Interval for poking the VD awake while the physical screen is off. */
        private const val VD_KEEP_ALIVE_INTERVAL_MS = 30_000L
    }

    /** Binder for local (same-process) binding */
    inner class LocalBinder : Binder() {
        val service: MirrorForegroundService get() = this@MirrorForegroundService
    }

    private val binder = LocalBinder()

    private var mirrorServer: MirrorServer? = null
    private var screenCapture: ScreenCaptureManager? = null
    private var videoEncoder: VideoEncoder? = null
    private var jpegEncoder: JpegEncoder? = null
    private var audioCapture: AudioCapture? = null
    private var audioOrchestrator: AudioCaptureOrchestrator? = null
    private var touchInjector: TouchInjector? = null
    private var virtualDisplayManager: VirtualDisplayManager? = null
    private var shizukuSetup: ShizukuSetup? = null
    private var currentWidth: Int = 0
    private var currentHeight: Int = 0
    private var currentEncoderSurface: android.view.Surface? = null
    private var currentBitrate: Int = 4_000_000
    private var currentFps: Int = 30
    private var currentMaxHeight: Int = 720
    private var mirroringMode: String = "FULL_SCREEN"
    private var targetPackage: String = ""
    private var browserConnectionListener: ((Boolean) -> Unit)? = null
    @Volatile private var stopRequested = false
    @Volatile private var cleanupCompleted = false
    private var serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var resizeJob: Job? = null
    private var pendingBrowserDisconnectJob: Job? = null
    private var browserConnected = false
    private var currentVdApp: String = "com.android.settings" // what's running on main VD
    private var currentWebUrl: String? = null
    private var currentWebSplitMode: Boolean = false
    private var activeSplitUrl: String? = null
    private var activeSplitComponent: String? = null
    private var currentSecondaryApp: String = ""
    private var currentSecondaryWebUrl: String? = null
    private var secondaryVideoEncoder: VideoEncoder? = null
    private var secondaryJpegEncoder: JpegEncoder? = null
    private var secondaryTouchInjector: TouchInjector? = null
    private var secondaryDisplayId: Int = -1
    private var secondaryWidth: Int = 0
    private var secondaryHeight: Int = 0
    private var secondaryRequestedWidth: Int = 0
    private var secondaryRequestedHeight: Int = 0
    private var currentCodecMode: String = "h264"
    private var savedMediaVolume: Int = -1
    private val mainHandler = Handler(Looper.getMainLooper())
    private var splitPresentation: SplitWebPresentation? = null
    private var singleVdSplit: Boolean = false

    // ABR (Adaptive Bitrate) state
    private var targetBitrate: Int = 4_000_000
    private var lastCongestionTimeMs = 0L
    private var abrJob: Job? = null
    // Thermal throttling: stores original bitrate before thermal reduction for restoration
    private var preThermalTargetBitrate: Int = 0
    // Thermal fps/resolution overrides — applied by rebuildPipeline when non-null
    private var thermalFpsOverride: Int? = null
    private var thermalMaxHeight: Int? = null
    // Display density scale (0.7 = default small, lower = more compact UI / more content)
    private var dpiScale: Float = 0.7f
    // Guard against concurrent Shizuku binding attempts
    @Volatile private var shizukuSetupInProgress = false
    private var shizukuBindRetryCount = 0
    private val SHIZUKU_MAX_RETRIES = 2

    // Auto mode: dynamically adjusts resolution/fps based on conditions.
    // When true, the service starts conservatively (720p/30fps) and steps up
    // only when thermal/network/decoder conditions are all healthy.
    private var autoResolution: Boolean = false
    private var autoFps: Boolean = false
    private var autoScaleJob: Job? = null
    // Current auto-selected tier — index into AUTO_TIERS
    private var autoTierIndex: Int = 0
    // Stability counter: number of consecutive healthy check intervals
    private var autoStableCount: Int = 0
    // Browser quality report — updated asynchronously from control socket
    @Volatile private var lastQualityDroppedFrames: Int = 0
    @Volatile private var lastQualityAvgDelayMs: Double = 0.0
    @Volatile private var lastQualityBacklogDrops: Int = 0

    // WakeLocks to keep streaming alive when screen is off
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    // Deferred pipeline state: heavy capture/encoding starts only when browser connects
    private var pendingAudioEnabled = false
    private var pendingMuteLocalAudio = false
    private var deferredAudioStartJob: Job? = null

    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null
    private var screenOffReceiver: BroadcastReceiver? = null
    private var vdKeepAliveJob: Job? = null
    private val screenOffPolicy = ScreenOffPolicy()
    private val keyguardManager by lazy {
        getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
    }

    val isRunning: Boolean
        get() = mirrorServer != null

    /** Whether this device supports panel-off (false after first failure). */
    val isPanelOffSupported: Boolean
        get() = screenOffPolicy.isPanelOffSupported

    /**
     * Turn off the physical display panel while keeping mirroring alive.
     * Called from UI button — NOT from power button / ACTION_SCREEN_OFF.
     * @return true if panel-off was initiated, false if preconditions not met
     */
    fun turnPanelOffForMirroring(): Boolean {
        if (!isRunning) {
            Log.w(TAG, "turnPanelOffForMirroring: service not running")
            return false
        }
        if (!browserConnected) {
            Log.w(TAG, "turnPanelOffForMirroring: browser not connected")
            return false
        }
        if (virtualDisplayManager?.hasVirtualDisplay() != true) {
            Log.w(TAG, "turnPanelOffForMirroring: no active virtual display")
            return false
        }
        if (screenOffPolicy.isScreenOff) {
            Log.d(TAG, "turnPanelOffForMirroring: already off")
            return true
        }

        // Ensure wake locks are held before turning panel off
        acquireWakeLocks()

        val action = screenOffPolicy.onScreenOff(panelOffSupported = true)
        logScreenState("Panel OFF requested via button (action=$action)")
        executeScreenOffAction(action)
        _panelOffStateFlow.value = screenOffPolicy.state
        return screenOffPolicy.state == ScreenOffState.PANEL_OFF_ACTIVE
    }

    /**
     * Restore the physical display panel.
     * Called from UI button, or automatically on cleanup.
     */
    fun restorePhysicalPanel() {
        if (!screenOffPolicy.isScreenOff) return
        val action = screenOffPolicy.onScreenOn()
        logScreenState("Panel ON requested via button (action=$action)")
        executeScreenOnAction(action)
        _panelOffStateFlow.value = screenOffPolicy.state
    }

    /** Current thermal throttle level exposed to the UI. 0 = normal, higher = hotter. */
    private val _thermalStatus = MutableStateFlow(0)
    val thermalStatus: StateFlow<Int> = _thermalStatus

    fun setBrowserConnectionListener(listener: ((Boolean) -> Unit)?) {
        browserConnectionListener = listener
        mirrorServer?.setBrowserConnectionListener(listener)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        instance = this
        isServiceRunning = true
        isCleanupInProgress = false
        createNotificationChannel()
        observeAppLaunchRequests()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            thermalListener = PowerManager.OnThermalStatusChangedListener { status ->
                handleThermalStatusChange(status)
            }
            pm.addThermalStatusListener(mainExecutor, thermalListener!!)
        }

        // Keep virtual display alive when phone screen turns off
        screenOffReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent?) {
                when (intent?.action) {
                    android.content.Intent.ACTION_SCREEN_OFF -> {
                        Log.i(TAG, "Screen OFF detected — using scrcpy approach")
                        onPhoneScreenOff()
                        // Check keyguard shortly after screen off (keyguard engages with a small delay)
                        mainHandler.postDelayed({
                            if (keyguardManager.isKeyguardLocked) {
                                MirrorDiagnostics.log(DiagnosticEvent.KEYGUARD_LOCKED)
                            }
                        }, 500)
                    }
                    android.content.Intent.ACTION_SCREEN_ON -> {
                        Log.i(TAG, "Screen ON detected")
                        onPhoneScreenOn()
                    }
                    android.content.Intent.ACTION_USER_PRESENT -> {
                        MirrorDiagnostics.log(DiagnosticEvent.KEYGUARD_UNLOCKED)
                    }
                }
            }
        }
        val filter = android.content.IntentFilter().apply {
            addAction(android.content.Intent.ACTION_SCREEN_OFF)
            addAction(android.content.Intent.ACTION_SCREEN_ON)
            addAction(android.content.Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(screenOffReceiver, filter)
    }
    
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun handleThermalStatusChange(status: Int) {
        _thermalStatus.value = status

        // Save original bitrate on first thermal event for later restoration
        if (preThermalTargetBitrate == 0 && targetBitrate > 0) {
            preThermalTargetBitrate = targetBitrate
        }

        when (status) {
            PowerManager.THERMAL_STATUS_CRITICAL,
            PowerManager.THERMAL_STATUS_EMERGENCY -> {
                Log.w(TAG, "Thermal status CRITICAL/EMERGENCY ($status) — warning only, continuing")
                android.os.Handler(mainLooper).post {
                    android.widget.Toast.makeText(
                        this,
                        getString(R.string.toast_thermal_warning),
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }
            PowerManager.THERMAL_STATUS_SEVERE -> {
                Log.w(TAG, "Thermal status SEVERE ($status) - Throttling encoder heavily + fps/resolution")
                val newBitrate = (preThermalTargetBitrate * 0.4).toInt().coerceAtLeast(500_000)
                currentBitrate = newBitrate
                targetBitrate = newBitrate
                videoEncoder?.setBitrate(currentBitrate)
                jpegEncoder?.setFps(8)
                // Stop audio on SEVERE to reduce CPU load
                Log.w(TAG, "Thermal SEVERE — stopping audio capture to reduce CPU load")
                audioOrchestrator?.stop()
                // Drop fps to 15 and cap resolution at 720p
                thermalFpsOverride = 15
                thermalMaxHeight = 720
                // Reset auto tier to most conservative
                autoTierIndex = 0
                autoStableCount = 0
                if (browserConnected) {
                    serviceScope.launch { rebuildPipeline(currentWidth, currentHeight, force = true) }
                }
            }
            PowerManager.THERMAL_STATUS_MODERATE -> {
                Log.w(TAG, "Thermal status MODERATE ($status) - Throttling encoder + fps drop to 20")
                val newBitrate = (preThermalTargetBitrate * 0.6).toInt().coerceAtLeast(500_000)
                currentBitrate = newBitrate
                targetBitrate = newBitrate
                videoEncoder?.setBitrate(currentBitrate)
                jpegEncoder?.setFps(12)
                // Drop fps to 20
                thermalFpsOverride = 20
                thermalMaxHeight = null
                // Reset auto tier to most conservative
                autoTierIndex = 0
                autoStableCount = 0
                if (browserConnected) {
                    serviceScope.launch { rebuildPipeline(currentWidth, currentHeight, force = true) }
                }
            }
            PowerManager.THERMAL_STATUS_LIGHT -> {
                Log.i(TAG, "Thermal status LIGHT ($status) - Preemptive throttling")
                val newBitrate = (preThermalTargetBitrate * 0.85).toInt().coerceAtLeast(500_000)
                currentBitrate = newBitrate
                targetBitrate = newBitrate
                videoEncoder?.setBitrate(currentBitrate)
                // Clear fps/resolution overrides at LIGHT
                thermalFpsOverride = null
                thermalMaxHeight = null
            }
            PowerManager.THERMAL_STATUS_NONE -> {
                Log.i(TAG, "Thermal status NONE ($status) - Restoring full bitrate and fps")
                thermalFpsOverride = null
                thermalMaxHeight = null
                if (preThermalTargetBitrate > 0) {
                    targetBitrate = preThermalTargetBitrate
                    currentBitrate = preThermalTargetBitrate
                    videoEncoder?.setBitrate(currentBitrate)
                    jpegEncoder?.setFps(15)
                    // Rebuild to restore original fps/resolution
                    if (browserConnected) {
                        serviceScope.launch { rebuildPipeline(currentWidth, currentHeight, force = true) }
                    }
                }
            }
        }

        // Broadcast thermal status to browser for playback profile auto-switching
        broadcastThermalStatus(status)
    }

    private fun broadcastThermalStatus(status: Int) {
        val level = when (status) {
            PowerManager.THERMAL_STATUS_SEVERE,
            PowerManager.THERMAL_STATUS_CRITICAL,
            PowerManager.THERMAL_STATUS_EMERGENCY -> "severe"
            PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
            PowerManager.THERMAL_STATUS_LIGHT -> "light"
            else -> "none"
        }
        val json = JSONObject().apply {
            put("type", "thermalStatus")
            put("level", level)
        }.toString()
        mirrorServer?.broadcastControlMessage(json)
    }

    private fun acquireWakeLocks() {
        try {
            releaseWakeLocks() // Release any existing locks first to prevent leaks
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            // Use wake lock with timeout (e.g. 4 hours) as safety mechanism
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Castla::StreamingWakeLock").apply {
                setReferenceCounted(false)
                acquire(14400000)
            }
            
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Castla::StreamingWifiLock").apply {
                setReferenceCounted(false)
                acquire() // WiFi lock doesn't support timeout
            }
            Log.i(TAG, "WakeLocks acquired (CPU & WiFi will stay awake)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire wake locks", e)
        }
    }
    
    private fun releaseWakeLocks() {
        try {
            wakeLock?.takeIf { it.isHeld }?.release()
            wifiLock?.takeIf { it.isHeld }?.release()
            wakeLock = null
            wifiLock = null
            Log.i(TAG, "WakeLocks released")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release wake locks", e)
        }
    }

    private var isCurrentAppVideo = false
    private var isSecondaryAppVideo = false
    private var lastBitrateChangeMs = 0L

    private fun observeAppLaunchRequests() {
        serviceScope.launch {
            com.castla.mirror.utils.AppLaunchBus.events.collect { request ->
                val component = if (request.className != null) {
                    "${request.packageName}/${request.className}"
                } else {
                    request.packageName
                }
                Log.i(TAG, "VD launch request: $component (video=${request.isVideoApp}, mode=${request.launchMode})")

                when (request.launchMode) {
                    LaunchMode.EXTERNAL_BROWSER_URL -> {
                        val displayId = virtualDisplayManager?.getDisplayId() ?: -1
                        val url = request.url ?: return@collect
                        dismissSplitPresentation(clearState = true)
                        if (request.splitMode && canLaunchPrimarySplitTask()) {
                            launchSplitExternalBrowserTarget(displayId, url, request.sourceAppPackage, request.allowEmbeddedFallback)
                        } else {
                            launchExternalBrowserTarget(displayId, url, request.sourceAppPackage, request.allowEmbeddedFallback)
                        }
                    }
                    LaunchMode.INTERNAL_WEBVIEW -> {
                        val displayId = virtualDisplayManager?.getDisplayId() ?: -1
                        dismissSplitPresentation(clearState = true)
                        val activityClassName = component.substringAfter('/', "com.castla.mirror.ui.WebBrowserActivity")
                        val url = request.url ?: request.intentExtra ?: return@collect
                        if (request.splitMode && canLaunchPrimarySplitTask()) {
                            launchSplitWebTarget(activityClassName, displayId, url)
                        } else {
                            launchFullscreenWebTarget(activityClassName, displayId, url)
                        }
                    }
                    LaunchMode.STANDARD_APP -> {
                        if (request.intentExtra != null) {
                            // Legacy path: intentExtra means web mode
                            val displayId = virtualDisplayManager?.getDisplayId() ?: -1
                            dismissSplitPresentation(clearState = true)
                            val activityClassName = component.substringAfter('/', "com.castla.mirror.ui.WebBrowserActivity")
                            if (request.splitMode && canLaunchPrimarySplitTask()) {
                                launchSplitWebTarget(activityClassName, displayId, request.intentExtra)
                            } else {
                                launchFullscreenWebTarget(activityClassName, displayId, request.intentExtra)
                            }
                        } else {
                            dismissSplitPresentation(clearState = true)
                            if (request.splitMode && canLaunchPrimarySplitTask()) {
                                launchSplitStandardTarget(component)
                            } else {
                                launchFullscreenStandardTarget(component)
                            }
                        }
                    }
                }

                // OTT bitrate boost: 1.2x (cap 15Mbps), debounced 500ms, disabled under thermal
                val now = android.os.SystemClock.elapsedRealtime()
                if (request.isVideoApp != isCurrentAppVideo && now - lastBitrateChangeMs > 500) {
                    isCurrentAppVideo = request.isVideoApp
                    lastBitrateChangeMs = now

                    val baseTargetBitrate = com.castla.mirror.utils.StreamMath.calculateBaseBitrate(currentWidth, currentHeight)
                    val thermalActive = _thermalStatus.value >= PowerManager.THERMAL_STATUS_LIGHT
                    targetBitrate = if (isCurrentAppVideo && !thermalActive) com.castla.mirror.utils.StreamMath.calculateOttBitrate(baseTargetBitrate) else baseTargetBitrate

                    if (currentBitrate > targetBitrate || (now - lastCongestionTimeMs > 2000)) {
                         currentBitrate = targetBitrate
                         videoEncoder?.setBitrate(currentBitrate)
                    }
                    Log.i(TAG, "OTT app detected=${isCurrentAppVideo} — target bitrate set to ${targetBitrate / 1000}kbps")

                    // OTT tier boost: jump to at least 720p60 when video app starts
                    if (autoResolution || autoFps) {
                        val boostTier = AutoScalePolicy.ottMinTier(
                            currentTierIndex = autoTierIndex,
                            isVideoApp = isCurrentAppVideo,
                            thermalStatus = _thermalStatus.value,
                            tierCount = AUTO_TIERS.size
                        )
                        if (boostTier != null) {
                            autoTierIndex = boostTier
                            autoStableCount = 0
                            applyAutoTier()
                            notifyAutoTierChange("ott_boost")
                            Log.i(TAG, "OTT tier boost — jumped to ${AUTO_TIERS[autoTierIndex].label}")
                        }
                    }

                    // OTT playback profile: switch client to smooth for more buffering,
                    // or restore when leaving video app
                    val profileMsg = JSONObject().apply {
                        put("type", "ottProfileHint")
                        put("active", isCurrentAppVideo)
                    }.toString()
                    mirrorServer?.broadcastControlMessage(profileMsg)
                    Log.i(TAG, "OTT profile hint: active=$isCurrentAppVideo")
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            requestStopAsync("notification_action")
            return START_NOT_STICKY
        }

        val notification = createNotification()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        @Suppress("DEPRECATION")
        val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            intent?.getParcelableExtra(EXTRA_DATA)
        }

        if (resultCode == Int.MIN_VALUE || data == null) {
            Log.e(TAG, "Invalid MediaProjection data (resultCode=$resultCode, data=$data)")
            stopSelf()
            return START_NOT_STICKY
        }

        Log.i(TAG, "onStartCommand: resultCode=$resultCode, hasData=true")

        val rawMaxHeight = intent!!.getIntExtra(EXTRA_MAX_RESOLUTION, 0)
        autoResolution = rawMaxHeight == 0
        currentMaxHeight = if (autoResolution) 720 else rawMaxHeight

        val rawFps = intent.getIntExtra(EXTRA_FPS, 0)
        autoFps = rawFps == 0
        val settingsFps = if (autoFps) 30 else rawFps

        Log.i(TAG, "Mode: autoRes=$autoResolution autoFps=$autoFps initialMaxHeight=$currentMaxHeight initialFps=$settingsFps")
        val audioEnabled = intent.getBooleanExtra(EXTRA_AUDIO, false)
        val muteLocalAudio = intent.getBooleanExtra(EXTRA_MUTE_LOCAL_AUDIO, false)
        mirroringMode = intent.getStringExtra(EXTRA_MIRRORING_MODE) ?: "FULL_SCREEN"
        targetPackage = intent.getStringExtra(EXTRA_TARGET_PACKAGE) ?: ""

        startPipeline(resultCode, data, settingsFps, audioEnabled, muteLocalAudio)

        return START_NOT_STICKY
    }

    private fun onNetworkCongestion() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastCongestionTimeMs > 500) { 
            lastCongestionTimeMs = now
            val minBitrate = 500_000
            currentBitrate = (currentBitrate * 0.8).toInt().coerceAtLeast(minBitrate)
            videoEncoder?.setBitrate(currentBitrate)
            Log.w(TAG, "ABR: Network congestion detected! Dropping bitrate to ${currentBitrate / 1000}kbps")
        }
    }

    private fun requestStopAsync(reason: String) {
        if (stopRequested) {
            Log.i(TAG, "Stop already requested, ignoring duplicate: $reason")
            return
        }
        stopRequested = true
        Log.i(TAG, "Async stop requested: $reason")

        try {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to stop foreground notification cleanly", e)
        }

        Thread {
            performCleanup(reason)
            mainHandler.post {
                MirrorWidgetProvider.updateAllWidgets(this)
                stopSelf()
            }
        }.start()
    }

    private fun logScreenState(event: String) {
        val keyguardLocked = keyguardManager.isKeyguardLocked
        val deviceLocked = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1)
            keyguardManager.isDeviceLocked else keyguardLocked
        val vdId = virtualDisplayManager?.getDisplayId() ?: -1
        Log.i(TAG, "[BUILD:screen-off-v3] $event — " +
                "state=${screenOffPolicy.state}, keyguardLocked=$keyguardLocked, deviceLocked=$deviceLocked, " +
                "browserConnected=$browserConnected, serverConnected=${mirrorServer?.isBrowserConnected()}, " +
                "wakeLockHeld=${wakeLock?.isHeld == true}, vdId=$vdId, panelOffSupported=${screenOffPolicy.isPanelOffSupported}")
    }

    private fun onPhoneScreenOff() {
        MirrorDiagnostics.log(DiagnosticEvent.SCREEN_OFF)
        val action = screenOffPolicy.onScreenOff(panelOffSupported = screenOffPolicy.isPanelOffSupported)
        logScreenState("Screen OFF (action=$action)")
        executeScreenOffAction(action)
        _panelOffStateFlow.value = screenOffPolicy.state
    }

    private fun onPhoneScreenOn() {
        MirrorDiagnostics.log(DiagnosticEvent.SCREEN_ON)
        val action = screenOffPolicy.onScreenOn()
        logScreenState("Screen ON (action=$action)")
        executeScreenOnAction(action)
        _panelOffStateFlow.value = screenOffPolicy.state

        // Cancel any pending (extended) grace job — we re-evaluate immediately
        cancelPendingBrowserDisconnect("screen_on")

        val stillConnected = mirrorServer?.isBrowserConnected() == true
        if (!stillConnected && browserConnected && !isCleanupInProgress) {
            Log.i(TAG, "Screen ON — browser gone while screen was off, executing deferred teardown")
            serviceScope.launch {
                onBrowserDisconnected()
                browserConnectionListener?.invoke(false)
            }
        }
    }

    private fun executeScreenOffAction(action: ScreenOffAction) {
        when (action) {
            ScreenOffAction.TURN_PANEL_OFF -> {
                val vdm = virtualDisplayManager
                if (vdm == null) {
                    Log.w(TAG, "Panel-off requested but no VirtualDisplayManager — falling back")
                    val fallback = screenOffPolicy.onPanelOffResult(success = false)
                    executeScreenOffAction(fallback)
                    return
                }
                val success = vdm.setPhysicalDisplayPower(false)
                Log.i(TAG, "Physical panel OFF result: success=$success")
                val fallback = screenOffPolicy.onPanelOffResult(success)
                if (fallback != ScreenOffAction.NONE) {
                    executeScreenOffAction(fallback)
                }
            }
            ScreenOffAction.START_KEEP_ALIVE -> {
                startVdKeepAlive()
            }
            ScreenOffAction.NONE -> {}
            else -> Log.w(TAG, "Unexpected screen-off action: $action")
        }
    }

    private fun executeScreenOnAction(action: ScreenOffAction) {
        when (action) {
            ScreenOffAction.RESTORE_PANEL -> {
                stopVdKeepAlive()
                val restored = virtualDisplayManager?.setPhysicalDisplayPower(true) ?: false
                Log.i(TAG, "Physical panel restored: success=$restored")
            }
            ScreenOffAction.STOP_KEEP_ALIVE -> {
                stopVdKeepAlive()
            }
            ScreenOffAction.NONE -> {}
            else -> Log.w(TAG, "Unexpected screen-on action: $action")
        }
    }

    private fun startVdKeepAlive() {
        stopVdKeepAlive()
        val vdm = virtualDisplayManager ?: run {
            Log.w(TAG, "VD keep-alive skipped — no VirtualDisplayManager")
            return
        }
        vdKeepAliveJob = serviceScope.launch {
            Log.i(TAG, "[BUILD:screen-off-v3] VD keep-alive starting (interval=${VD_KEEP_ALIVE_INTERVAL_MS}ms, vdId=${vdm.getDisplayId()})")
            vdm.keepDisplayAwake()
            while (true) {
                kotlinx.coroutines.delay(VD_KEEP_ALIVE_INTERVAL_MS)
                vdm.keepDisplayAwake()
            }
        }
    }

    private fun stopVdKeepAlive() {
        vdKeepAliveJob?.cancel()
        vdKeepAliveJob = null
    }

    @Synchronized
    private fun performCleanup(reason: String) {
        if (cleanupCompleted) {
            Log.i(TAG, "Cleanup already completed, skipping: $reason")
            return
        }
        cleanupCompleted = true // set immediately under lock to prevent reentrant race
        Log.i(TAG, "Performing cleanup: $reason")
        MirrorDiagnostics.endSession(reason)
        isCleanupInProgress = true

        // Always restore physical display panel on cleanup — safety net
        if (screenOffPolicy.state == ScreenOffState.PANEL_OFF_ACTIVE ||
            screenOffPolicy.state == ScreenOffState.PANEL_OFF_PENDING) {
            try {
                virtualDisplayManager?.setPhysicalDisplayPower(true)
                Log.i(TAG, "Physical display restored to ON during cleanup")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to restore physical display", e)
            }
        }
        screenOffPolicy.reset()
        _panelOffStateFlow.value = ScreenOffState.ACTIVE

        try { screenOffReceiver?.let { unregisterReceiver(it) } } catch (_: Exception) {}
        screenOffReceiver = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                thermalListener?.let { pm.removeThermalStatusListener(it) }
            } catch (_: Exception) {}
        }
        releaseWakeLocks()
        stopVdKeepAlive()

        // Stop audio capture FIRST to prevent AudioCapture-Opus thread crash.
        // If the app crashes while VD is alive, system_server tries to launch home
        // on the orphaned VD and hits a NPE → phone reboots.
        audioOrchestrator?.stop()

        try { resizeJob?.cancel() } catch (_: Exception) {}
        try { abrJob?.cancel() } catch (_: Exception) {}
        try { autoScaleJob?.cancel() } catch (_: Exception) {}
        try { serviceScope.cancel() } catch (_: Exception) {}
        try { compositionDispatcher.close() } catch (_: Exception) {}

        dismissSplitPresentation(clearState = true)
        releaseSecondaryPipeline(clearState = true)
        try { removeAllVdTasks() } catch (e: Exception) { Log.w(TAG, "Failed to remove VD tasks", e) }
        try { pendingBrowserDisconnectJob?.cancel() } catch (_: Exception) {}
        pendingBrowserDisconnectJob = null
        try { virtualDisplayManager?.release() } catch (e: Exception) { Log.w(TAG, "Failed to release virtual display manager", e) }
        try { shizukuSetup?.release() } catch (e: Exception) { Log.w(TAG, "Failed to release shizuku setup", e) }
        try { screenCapture?.release() } catch (e: Exception) { Log.w(TAG, "Failed to release screen capture", e) }
        try { videoEncoder?.release() } catch (e: Exception) { Log.w(TAG, "Failed to release video encoder", e) }
        try { jpegEncoder?.release() } catch (e: Exception) { Log.w(TAG, "Failed to release jpeg encoder", e) }
        try { touchInjector?.release() } catch (e: Exception) { Log.w(TAG, "Failed to release touch injector", e) }
        try { mirrorServer?.stop() } catch (e: Exception) { Log.w(TAG, "Failed to stop mirror server", e) }

        virtualDisplayManager = null
        shizukuSetup = null
        screenCapture = null
        videoEncoder = null
        jpegEncoder = null
        touchInjector = null
        mirrorServer = null

        instance = null
        isCleanupInProgress = false
        isServiceRunning = false
        Log.i(TAG, "Cleanup completed: $reason")
    }

    private fun startAbrLoop() {
        abrJob?.cancel()
        abrJob = serviceScope.launch {
            while (isServiceRunning && browserConnected) {
                kotlinx.coroutines.delay(2000)
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastCongestionTimeMs >= 2000 && currentBitrate < targetBitrate) {
                    currentBitrate = (currentBitrate * 1.1).toInt().coerceAtMost(targetBitrate)
                    videoEncoder?.setBitrate(currentBitrate)
                    Log.i(TAG, "ABR: Network stable. Increasing bitrate to ${currentBitrate / 1000}kbps")
                }
            }
        }
    }

    /**
     * Auto-scale loop: periodically checks conditions and steps resolution/fps
     * up or down. Only runs when autoResolution or autoFps is true.
     *
     * Upscale requires AutoScalePolicy.UPSCALE_THRESHOLD consecutive stable intervals.
     * Downscale on thermal is immediate (handled by thermal handler, not here).
     * This loop handles the gradual upscale when conditions improve.
     */
    private fun startAutoScaleLoop() {
        if (!autoResolution && !autoFps) return
        autoScaleJob?.cancel()
        autoTierIndex = 0  // start at most conservative tier
        autoStableCount = 0
        autoScaleJob = serviceScope.launch {
            // Short initial stabilization, then evaluate immediately
            kotlinx.coroutines.delay(AUTO_SCALE_INITIAL_DELAY_MS)
            while (isServiceRunning && browserConnected) {
                evaluateAutoScale()
                kotlinx.coroutines.delay(AUTO_SCALE_INTERVAL_MS)
            }
        }
    }

    private fun evaluateAutoScale() {
        val now = android.os.SystemClock.elapsedRealtime()
        val input = AutoScaleInput(
            thermalStatus = _thermalStatus.value,
            networkStable = now - lastCongestionTimeMs >= AUTO_SCALE_INTERVAL_MS,
            browserHealthy = AutoScalePolicy.isBrowserHealthy(
                lastQualityDroppedFrames, lastQualityBacklogDrops, lastQualityAvgDelayMs
            ),
            currentTierIndex = autoTierIndex,
            stableCount = autoStableCount,
            tierCount = AUTO_TIERS.size
        )

        when (val decision = AutoScalePolicy.evaluate(input)) {
            is AutoScaleDecision.DropToTier -> {
                autoTierIndex = decision.tierIndex
                autoStableCount = 0
                applyAutoTier()
                notifyAutoTierChange(decision.reason)
                Log.i(TAG, "AutoScale: ${decision.reason} — dropped to ${AUTO_TIERS[autoTierIndex].label}")
            }
            is AutoScaleDecision.StepDown -> {
                autoTierIndex = decision.newTierIndex
                autoStableCount = 0
                applyAutoTier()
                notifyAutoTierChange(decision.reason)
                Log.i(TAG, "AutoScale: ${decision.reason} — stepped down to ${AUTO_TIERS[autoTierIndex].label}")
            }
            is AutoScaleDecision.StepUp -> {
                autoTierIndex = decision.newTierIndex
                autoStableCount = 0
                applyAutoTier()
                notifyAutoTierChange("stable")
                Log.i(TAG, "AutoScale: stable — stepped up to ${AUTO_TIERS[autoTierIndex].label}")
            }
            is AutoScaleDecision.Hold -> {
                autoStableCount = decision.newStableCount
            }
            AutoScaleDecision.Block -> {
                autoStableCount = 0
            }
        }
    }

    private fun notifyAutoTierChange(reason: String) {
        val tier = AUTO_TIERS[autoTierIndex]
        val json = JSONObject().apply {
            put("type", "autoTierChange")
            put("tier", tier.label)
            put("reason", reason)
        }.toString()
        mirrorServer?.broadcastControlMessage(json)
    }

    private fun applyAutoTier() {
        val tier = AUTO_TIERS[autoTierIndex]
        // Only apply auto values for settings that are in auto mode
        if (autoResolution) currentMaxHeight = tier.maxHeight
        if (autoFps) currentFps = tier.fps
        // Trigger pipeline rebuild with new settings
        if (browserConnected && currentWidth > 0 && currentHeight > 0) {
            serviceScope.launch {
                rebuildPipeline(currentWidth, currentHeight, force = true)
            }
        }
    }

    private fun startPipeline(
        resultCode: Int,
        data: Intent,
        fps: Int,
        audioEnabled: Boolean,
        muteLocalAudio: Boolean = false
    ) {
        try {
            MirrorDiagnostics.onSessionStart()

            // Only initialize projection and server — defer encoder/capture until browser connects
            screenCapture = ScreenCaptureManager(this).also {
                it.initProjection(resultCode, data)
            }

            val rawWidth = screenCapture!!.captureWidth
            val rawHeight = screenCapture!!.captureHeight
            val effectiveMaxHeight = effectiveMaxHeightForRequest(rawHeight)

            var width = rawWidth
            var height = rawHeight

            if (height > effectiveMaxHeight) {
                val scale = effectiveMaxHeight.toFloat() / height
                height = effectiveMaxHeight
                width = (width * scale).toInt()
            }

            width = (width + 15) and 15.inv()
            height = (height + 15) and 15.inv()

            currentWidth = width
            currentHeight = height
            currentFps = fps
            pendingAudioEnabled = audioEnabled
            pendingMuteLocalAudio = muteLocalAudio

            audioOrchestrator = AudioCaptureOrchestrator(object : AudioCaptureOrchestrator.Actions {
                override fun startCapture(codec: String?) {
                    val projection = screenCapture?.getMediaProjection() ?: run {
                        Log.w(TAG, "Audio requested but MediaProjection not available")
                        return
                    }
                    audioCapture = AudioCapture(projection, shizukuSetup?.privilegedService).also { audio ->
                        if (codec == "pcm") {
                            audio.startPcmOnly { audioData -> mirrorServer?.broadcastAudio(audioData) }
                        } else {
                            audio.start { audioData -> mirrorServer?.broadcastAudio(audioData) }
                        }
                    }
                    Log.i(TAG, "Audio capture started (codec=${codec ?: "default"})")
                }
                override fun stopCapture() {
                    try { audioCapture?.stop() } catch (_: Exception) {}
                    audioCapture = null
                }
                override fun applyMute(shouldMute: Boolean) {
                    if (shouldMute) muteMediaVolume() else restoreMediaVolume()
                }
                override fun grantAudioPermission() {
                    tryGrantAudioCapturePermission()
                }
                override fun scheduleDeferredStart(delayMs: Long): Any? {
                    val job = serviceScope.launch(Dispatchers.IO) {
                        kotlinx.coroutines.delay(delayMs)
                        audioOrchestrator?.onDeferredTimerExpired()
                    }
                    deferredAudioStartJob = job
                    return job
                }
                override fun cancelDeferredStart(handle: Any?) {
                    (handle as? Job)?.cancel()
                    if (deferredAudioStartJob == handle) deferredAudioStartJob = null
                }
            })

            touchInjector = TouchInjector(width, height)

            mirrorServer = MirrorServer(this).also { server ->
                server.setNetworkCongestionListener { onNetworkCongestion() }
                server.setTouchListener { event ->
                    if (event.pane == "secondary") {
                        secondaryTouchInjector?.onTouchEvent(event)
                    } else {
                        touchInjector?.onTouchEvent(event)
                    }
                    if (event.action == "up") {
                        lastTouchPane = event.pane
                        checkImeAndNotifyBrowser()
                    }
                }
                server.setCodecModeListener { mode -> onCodecModeRequest(mode) }
                server.setViewportChangeListener { pane, w, h, layoutMode ->
                    singleVdSplit = layoutMode == "browser_only_split" || layoutMode == "freeform_split"
                    if (pane == "secondary") {
                        if (singleVdSplit) {
                            Log.d(TAG, "Ignoring secondary viewport in single-VD split mode")
                        } else {
                            onSecondaryViewportChange(w, h)
                        }
                    } else {
                        onViewportChange(w, h)
                    }
                }
                server.setTextInputListener { text -> injectText(text) }
                server.setKeyEventListener { keyCode -> injectKeyEvent(keyCode) }
                server.setCompositionUpdateListener { bs, text -> injectCompositionUpdate(bs, text) }
                server.setAudioCodecListener { codec -> onAudioCodecRequest(codec) }
                server.setAudioSocketConnectedListener { audioOrchestrator?.onAudioSocketConnected() }
                server.setGoHomeListener {
                    Log.i(TAG, "Navigating to home requested by Web Launcher")
                    val previousApp = currentVdApp
                    dismissSplitPresentation(clearState = true)
                    if (!singleVdSplit) {
                        releaseSecondaryPipeline(clearState = true)
                    }
                    // Force-stop BEFORE going home so the old app's screen
                    // is removed before the home animation starts
                    forceStopAppIfNeeded(previousApp)
                    if (virtualDisplayManager?.hasVirtualDisplay() == true) {
                        virtualDisplayManager?.launchHomeOnDisplay()
                    } else {
                        Log.w(TAG, "Skipping HOME launch: virtual display is not active")
                    }
                    currentVdApp = "HOME"
                    currentWebUrl = null
                    clearSplitState()
                }
                server.setAppLaunchListener { pkgName, componentName, splitMode, pane ->
                    launchAppFromWebLauncher(pkgName, componentName, splitMode, pane)
                }

                server.setCloseSplitListener {
                    Log.i(TAG, "Close split requested — restoring primary fullscreen")
                    closeFreeformSplit()
                }

                server.setDisplayDensityListener { scale ->
                    Log.i(TAG, "Display density scale changed to $scale")
                    dpiScale = scale
                    // Only update DPI on the existing VD — do NOT force-rebuild
                    // the pipeline. A force rebuild uses currentWidth/currentHeight
                    // which may be stale full-screen dimensions, overriding a
                    // concurrent split viewport resize.
                    val vdm = virtualDisplayManager
                    if (vdm != null && vdm.hasVirtualDisplay() && currentWidth > 0 && currentHeight > 0) {
                        val dpi = computeVirtualDisplayDpi(currentWidth, currentHeight)
                        vdm.resizeDisplay(vdm.getDisplayId(), currentWidth, currentHeight, dpi)
                        Log.i(TAG, "Updated VD DPI to $dpi (scale=$scale, size=${currentWidth}x${currentHeight})")
                    }
                }

                server.setQualityReportListener { dropped, avgDelay, backlogDrops ->
                    lastQualityDroppedFrames = dropped
                    lastQualityAvgDelayMs = avgDelay
                    lastQualityBacklogDrops = backlogDrops
                }


                server.setBrowserConnectionListener { connected ->
                    if (connected) {
                        cancelPendingBrowserDisconnect("browser_reconnected")
                        if (!browserConnected) {
                            browserConnected = true
                            onBrowserConnected()
                        }
                        browserConnectionListener?.invoke(true)
                    } else if (browserConnected) {
                        scheduleBrowserDisconnect()
                    } else {
                        browserConnectionListener?.invoke(false)
                    }
                }

                server.start(0)
                Log.i(TAG, "Server started on port ${MirrorServer.DEFAULT_PORT} — waiting for browser")
            }

            Log.i(TAG, "Pipeline initialized (idle): ${width}x${height}, audio=$audioEnabled")
            MirrorWidgetProvider.updateAllWidgets(this)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start pipeline", e)
            stopSelf()
        }
    }
    
    // ActiveLaunchSession tracks what's currently running on the virtual display
    private enum class SessionMode { STANDARD_APP, EXTERNAL_BROWSER, INTERNAL_WEBVIEW }
    private data class ActiveLaunchSession(
        val mode: SessionMode,
        val launchTarget: String,
        val url: String? = null,
        val sourceAppPackage: String? = null,
        val browserPackage: String? = null
    )
    private var activeSession: ActiveLaunchSession? = null
    private var activeSplitSession: ActiveLaunchSession? = null

    private fun internalComponentName(activityClassName: String): String {
        return if (activityClassName.contains('/')) activityClassName else "$packageName/$activityClassName"
    }

    private fun clearSecondaryState() {
        currentSecondaryApp = ""
        currentSecondaryWebUrl = null
        secondaryWidth = 0
        secondaryHeight = 0
        secondaryRequestedWidth = 0
        secondaryRequestedHeight = 0
    }

    private fun secondaryBitrate(width: Int, height: Int): Int {
        return com.castla.mirror.utils.StreamMath.calculateSecondaryBitrate(width, height)
    }

    /**
     * Rebalance primary/secondary bitrates when split OTT state changes.
     * Video pane gets boosted bitrate, companion pane gets reduced bitrate.
     * When no split is active, falls back to standard OTT or base bitrate.
     */
    private fun rebalanceSplitBitrates() {
        val thermalActive = _thermalStatus.value >= PowerManager.THERMAL_STATUS_LIGHT
        val hasSplit = secondaryDisplayId >= 0 && secondaryWidth > 0
        val now = android.os.SystemClock.elapsedRealtime()
        val canApply = now - lastCongestionTimeMs > 2000

        if (hasSplit && (isCurrentAppVideo || isSecondaryAppVideo) && !thermalActive) {
            // Split with at least one video pane: rebalance
            val primaryBps = if (isCurrentAppVideo)
                StreamMath.calculateSplitVideoBitrate(currentWidth, currentHeight)
            else
                StreamMath.calculateSplitCompanionBitrate(currentWidth, currentHeight)

            val secondaryBps = if (isSecondaryAppVideo)
                StreamMath.calculateSplitVideoBitrate(secondaryWidth, secondaryHeight)
            else
                StreamMath.calculateSplitCompanionBitrate(secondaryWidth, secondaryHeight)

            targetBitrate = primaryBps
            if (canApply || currentBitrate > primaryBps) {
                currentBitrate = primaryBps
                videoEncoder?.setBitrate(currentBitrate)
            }
            secondaryVideoEncoder?.setBitrate(secondaryBps)
            Log.i(TAG, "Split rebalance: primary=${primaryBps / 1000}kbps(video=${isCurrentAppVideo}) secondary=${secondaryBps / 1000}kbps(video=${isSecondaryAppVideo})")
        } else {
            // No split or no video: standard bitrate logic
            val baseBitrate = StreamMath.calculateBaseBitrate(currentWidth, currentHeight)
            targetBitrate = if (isCurrentAppVideo && !thermalActive)
                StreamMath.calculateOttBitrate(baseBitrate)
            else
                baseBitrate
            if (canApply || currentBitrate > targetBitrate) {
                currentBitrate = targetBitrate
                videoEncoder?.setBitrate(currentBitrate)
            }
            // Restore secondary to default if active
            if (hasSplit) {
                val secBitrate = StreamMath.calculateSecondaryBitrate(secondaryWidth, secondaryHeight)
                secondaryVideoEncoder?.setBitrate(secBitrate)
            }
            Log.i(TAG, "Bitrate set: primary=${targetBitrate / 1000}kbps (video=${isCurrentAppVideo}, split=$hasSplit)")
        }
    }

    private fun computeVirtualDisplayDpi(width: Int, height: Int): Int {
        val baseDpi = StreamMath.calculateDpi(minOf(width, height))
        return StreamMath.applyDensityScale(baseDpi, dpiScale)
    }


    private fun releaseSecondaryPipeline(clearState: Boolean = false) {
        if (secondaryDisplayId >= 0) {
            virtualDisplayManager?.releaseSecondaryVirtualDisplay(secondaryDisplayId)
            secondaryDisplayId = -1
        }
        secondaryVideoEncoder?.release()
        secondaryVideoEncoder = null
        secondaryJpegEncoder?.release()
        secondaryJpegEncoder = null
        mirrorServer?.setKeyframeRequester("secondary") {}
        secondaryTouchInjector?.release()
        secondaryTouchInjector = null
        if (isSecondaryAppVideo) {
            isSecondaryAppVideo = false
            rebalanceSplitBitrates()
        }
        if (clearState) {
            clearSecondaryState()
            clearSplitState()
            // Trigger primary VD resize to fullscreen on next viewport update
            Log.i(TAG, "Secondary pipeline released — primary will resize to fullscreen on next viewport")
        }
    }

    private fun rebuildSecondaryPipeline(targetWidth: Int, targetHeight: Int) {
        if (targetWidth <= 0 || targetHeight <= 0) return
        val effectiveMaxHeight = effectiveMaxHeightForRequest(targetHeight, isSecondaryPane = true)
        Log.i(
            TAG,
            "Rebuilding secondary pipeline requested=${targetWidth}x${targetHeight} effectiveMaxHeight=$effectiveMaxHeight"
        )
        var width = targetWidth
        var height = targetHeight
        if (height > effectiveMaxHeight) {
            val scale = effectiveMaxHeight.toFloat() / height
            height = effectiveMaxHeight
            width = (width * scale).toInt()
        }
        width = (width + 15) and 15.inv()
        height = (height + 15) and 15.inv()
        if (width < 320 || height < 320) return
        if (virtualDisplayManager?.isBound() != true) return
        val hasMatchingPipeline = secondaryDisplayId >= 0 && secondaryWidth == width && secondaryHeight == height &&
            ((currentCodecMode == "mjpeg" && secondaryJpegEncoder != null) || (currentCodecMode != "mjpeg" && secondaryVideoEncoder != null))
        if (hasMatchingPipeline) {
            Log.d(TAG, "Secondary pipeline already matches ${width}x${height}, skipping rebuild")
            return
        }

        val existingSecondaryVd = secondaryDisplayId >= 0

        // Release encoder only (NOT the VD) so we can resize
        secondaryVideoEncoder?.release()
        secondaryVideoEncoder = null
        secondaryJpegEncoder?.release()
        secondaryJpegEncoder = null
        mirrorServer?.setKeyframeRequester("secondary") {}

        val surface = if (currentCodecMode == "mjpeg") {
            val jpeg = JpegEncoder(width, height, fps = 15, quality = 65)
            val inputSurface = jpeg.createInputSurface()
            jpeg.start { frameData, isKeyFrame -> mirrorServer?.broadcastFrame(frameData, isKeyFrame, "secondary") }
            secondaryJpegEncoder = jpeg
            inputSurface
        } else {
            val encoder = VideoEncoder(width, height, secondaryBitrate(width, height), currentFps)
            val inputSurface = encoder.createInputSurface()
            encoder.onSpsPps = { spsPps -> mirrorServer?.broadcastSpsPps(spsPps, "secondary") }
            encoder.start { frameData, isKeyFrame -> mirrorServer?.broadcastFrame(frameData, isKeyFrame, "secondary") }
            mirrorServer?.setKeyframeRequester("secondary") { encoder.requestKeyFrame() }
            secondaryVideoEncoder = encoder
            inputSurface
        }

        val dpi = computeVirtualDisplayDpi(width, height)

        if (existingSecondaryVd) {
            // Resize existing secondary VD gradually to avoid activity recreation
            virtualDisplayManager?.getPrivilegedService()?.setSurface(secondaryDisplayId, surface)
            virtualDisplayManager?.resizeDisplay(secondaryDisplayId, width, height, dpi)
            secondaryWidth = width
            secondaryHeight = height
            secondaryTouchInjector?.updateDimensions(width, height)
            Log.i(TAG, "Gradually resized secondary VD $secondaryDisplayId to ${width}x${height}")
        } else {
            // First creation
            val newDisplayId = virtualDisplayManager?.createSecondaryVirtualDisplay(width, height, dpi, surface) ?: -1
            if (newDisplayId < 0) {
                releaseSecondaryPipeline(clearState = false)
                return
            }
            secondaryDisplayId = newDisplayId
            secondaryWidth = width
            secondaryHeight = height
            secondaryTouchInjector = (secondaryTouchInjector ?: TouchInjector(width, height)).also { injector ->
                injector.updateDimensions(width, height)
                injector.setVirtualDisplayInjector { action, x, y, pointerId ->
                    try {
                        shizukuSetup?.privilegedService?.injectInput(secondaryDisplayId, action, x, y, pointerId)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to inject secondary input on display $secondaryDisplayId", e)
                    }
                }
            }
            restoreSecondaryVdContent()
        }
        mirrorServer?.broadcastControlMessage(JSONObject().apply {
            put("type", "resolutionChanged")
            put("pane", "secondary")
            put("width", secondaryWidth)
            put("height", secondaryHeight)
        }.toString())
    }

    private fun onSecondaryViewportChange(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        secondaryRequestedWidth = width
        secondaryRequestedHeight = height
        serviceScope.launch(Dispatchers.IO) {
            rebuildSecondaryPipeline(width, height)
        }
    }

    private fun restoreSecondaryVdContent() {
        if (secondaryDisplayId < 0 || currentSecondaryApp.isBlank()) return
        if (currentSecondaryApp.startsWith("$packageName/")) {
            val activityClassName = currentSecondaryApp.substringAfter('/').let { className ->
                if (className.startsWith('.')) "$packageName$className" else className
            }
            launchOwnActivityOnDisplay(
                activityClassName = activityClassName,
                displayId = secondaryDisplayId,
                url = currentSecondaryWebUrl ?: "https://m.youtube.com",
                splitMode = true,
                applySplitBounds = false
            )
            return
        }
        if (currentSecondaryWebUrl != null) {
            launchTargetOnDisplay(
                secondaryDisplayId,
                currentSecondaryApp,
                extraKey = "url",
                extraValue = currentSecondaryWebUrl,
                freeform = false
            )
        } else {
            launchTargetOnDisplay(secondaryDisplayId, currentSecondaryApp, freeform = false)
        }
    }

    private fun launchSecondaryTarget(launchTarget: String, webUrl: String? = null) {
        currentSecondaryApp = normalizeLaunchTarget(launchTarget)
        currentSecondaryWebUrl = webUrl
        Log.i(
            TAG,
            "Queued secondary target app=$currentSecondaryApp url=$currentSecondaryWebUrl displayId=$secondaryDisplayId viewport=${secondaryRequestedWidth}x${secondaryRequestedHeight}"
        )
        if (secondaryDisplayId >= 0) {
            restoreSecondaryVdContent()
        }
    }

    private data class DisplayTaskSnapshot(
        val taskId: Int,
        val mode: String,
        val header: String,
        val body: String
    )

    private fun clearSplitState() {
        currentWebSplitMode = false
        activeSplitUrl = null
        activeSplitComponent = null
        activeSplitSession = null
    }

    private fun closeFreeformSplit() {
        val displayId = virtualDisplayManager?.getDisplayId() ?: -1
        if (displayId < 0) return

        // Remove the split app's task from the VD
        val splitTarget = activeSplitComponent
        if (splitTarget != null) {
            serviceScope.launch(Dispatchers.IO) {
                try {
                    val service = virtualDisplayManager?.getPrivilegedService() ?: return@launch
                    val tasks = parseDisplayTasks(service.execCommand("dumpsys activity activities"), displayId)
                    // Find and remove split task
                    for (task in tasks) {
                        if (task.mode == "freeform" && taskMatchesLaunchTarget(task, splitTarget)) {
                            service.execCommand("am task remove ${task.taskId}")
                            Log.i(TAG, "Removed split task ${task.taskId} ($splitTarget)")
                            break
                        }
                    }
                    // Restore primary to fullscreen bounds
                    val primaryTarget = normalizeLaunchTarget(currentVdApp)
                    val fullBounds = android.graphics.Rect(0, 0, currentWidth, currentHeight)
                    val primaryTaskId = findTaskId(service, displayId, primaryTarget)
                    if (primaryTaskId != null) {
                        service.execCommand("cmd activity task resize $primaryTaskId ${fullBounds.left} ${fullBounds.top} ${fullBounds.right} ${fullBounds.bottom}")
                        Log.i(TAG, "Restored primary task $primaryTaskId to fullscreen")
                    } else {
                        // Re-launch primary fullscreen as fallback
                        virtualDisplayManager?.launchAppOnDisplay(primaryTarget)
                        Log.i(TAG, "Re-launched primary $primaryTarget fullscreen")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to close freeform split", e)
                }
            }
        }

        dismissSplitPresentation(clearState = true)
        clearSplitState()
    }

    private fun hasActiveSplitSession(): Boolean = activeSplitUrl != null || activeSplitComponent != null

    /**
     * Aggressively clean up all tasks from a virtual display before releasing it.
     * Uses multiple strategies to prevent reparenting to the main display:
     * 1. HOME keyevent to push apps to background
     * 2. force-stop third-party packages (atomic, no race condition)
     * 3. am task remove for remaining tasks (our own activities, launchers)
     * 4. Delay to let framework process
     */
    private fun removeAllVdTasks() {
        cleanupDisplay(virtualDisplayManager?.getDisplayId() ?: -1)
        cleanupDisplay(secondaryDisplayId)
    }

    private fun cleanupDisplay(displayId: Int) {
        if (displayId < 0) return
        val service = virtualDisplayManager?.getPrivilegedService() ?: return
        val myPackage = packageName

        try {
            // 1. Send HOME to push everything to background
            service.execCommand("input -d $displayId keyevent 3")

            // 2. Parse tasks and collect third-party packages
            val dumpsys = service.execCommand("dumpsys activity activities")
            val tasks = parseDisplayTasks(dumpsys, displayId)
            val packagesToStop = mutableSetOf<String>()

            for (task in tasks) {
                // Extract package name from task header: "Task{... A=<uid>:<package> ...}"
                val pkgMatch = Regex("A=\\d+:([\\w.]+)").find(task.header)
                val pkg = pkgMatch?.groupValues?.getOrNull(1)
                if (pkg != null && pkg != myPackage
                    && !pkg.startsWith("com.android.launcher")
                    && !pkg.startsWith("com.sec.android.app.launcher")
                    && pkg != "com.android.settings"
                ) {
                    packagesToStop.add(pkg)
                }
            }

            // 3. force-stop third-party apps (atomic — no race condition)
            for (pkg in packagesToStop) {
                service.execCommand("am force-stop $pkg")
                Log.i(TAG, "Force-stopped $pkg from display $displayId")
            }

            // 4. Remove remaining tasks (our own activities, etc.)
            for (task in tasks) {
                service.execCommand("am task remove ${task.taskId}")
                Log.i(TAG, "Removed task ${task.taskId} from display $displayId")
            }

            Log.i(TAG, "Cleaned up display $displayId: ${packagesToStop.size} force-stopped, ${tasks.size} tasks removed")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clean up display $displayId", e)
        }
    }

    /**
     * Force-stop a previously running app when navigating home.
     * Skips system apps, launchers, and our own package.
     */
    private val BROWSER_PACKAGES = setOf(
        "com.android.chrome",
        "com.sec.android.app.sbrowser",
        "org.mozilla.firefox",
        "com.microsoft.emmx"
    )

    private fun forceStopAppIfNeeded(packageName: String) {
        val pkg = packageName.substringBefore('/')
        if (pkg.isBlank()
            || pkg == "HOME"
            || pkg == "com.android.settings"
            || pkg.startsWith("com.android.launcher")
            || pkg.startsWith("com.sec.android.app.launcher")
            || pkg == applicationContext.packageName
        ) return

        try {
            val service = virtualDisplayManager?.getPrivilegedService() ?: return
            // For browser apps, prefer task remove over force-stop to preserve user sessions
            if (BROWSER_PACKAGES.contains(pkg)) {
                val displayId = virtualDisplayManager?.getDisplayId() ?: -1
                val taskId = findTaskId(service, displayId, packageName)
                if (taskId != null) {
                    service.execCommand("am task remove $taskId")
                    Log.i(TAG, "Removed browser task $taskId for $pkg (avoiding force-stop)")
                    return
                }
            }
            service.execCommand("am force-stop $pkg")
            Log.i(TAG, "Force-stopped previous app: $pkg")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to force-stop $pkg", e)
        }
    }

    private fun canLaunchPrimarySplitTask(): Boolean {
        return currentVdApp.isNotBlank() && currentVdApp != "HOME" && currentVdApp != "com.android.settings"
    }

    private fun primaryTaskBounds(): android.graphics.Rect {
        val splitBounds = splitTaskBounds()
        return android.graphics.Rect(0, 0, splitBounds.left, currentHeight)
    }

    private fun escapeShellArg(value: String): String = "'" + value.replace("'", "'\''") + "'"

    private fun resolveLaunchComponent(packageOrComponent: String): String? {
        if (packageOrComponent.contains('/')) return packageOrComponent
        return try {
            val launchIntent = packageManager.getLaunchIntentForPackage(packageOrComponent)
            val component = launchIntent?.component ?: run {
                val intent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    `package` = packageOrComponent
                }
                packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
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

    private fun normalizeLaunchTarget(packageOrComponent: String): String {
        return resolveLaunchComponent(packageOrComponent) ?: packageOrComponent
    }

    private fun buildShellLaunchCommand(
        displayId: Int,
        packageOrComponent: String,
        extraKey: String? = null,
        extraValue: String? = null,
        freeform: Boolean = false
    ): String {
        val resolvedComponent = resolveLaunchComponent(packageOrComponent)
        val launchTarget = resolvedComponent ?: packageOrComponent
        return buildString {
            append("am start -W --display $displayId -f 0x18000000 ")
            if (freeform) {
                append("--windowingMode 5 ")
            }
            if (resolvedComponent != null) {
                append("-n ${escapeShellArg(resolvedComponent)} ")
            } else {
                append("-a android.intent.action.MAIN -c android.intent.category.LAUNCHER ")
                append("-p ${escapeShellArg(launchTarget)} ")
            }
            if (!extraKey.isNullOrEmpty() && extraValue != null) {
                append("--es $extraKey ${escapeShellArg(extraValue)} ")
            }
        }.trim()
    }

    private fun launchTargetOnDisplay(
        displayId: Int,
        packageOrComponent: String,
        extraKey: String? = null,
        extraValue: String? = null,
        freeform: Boolean = false
    ): Boolean {
        if (displayId < 0) return false
        val service = virtualDisplayManager?.getPrivilegedService() ?: return false
        return try {
            val command = buildShellLaunchCommand(displayId, packageOrComponent, extraKey, extraValue, freeform)
            Log.i(TAG, "Executing: $command")
            val result = service.execCommand(command)
            Log.i(TAG, "Launch result for $packageOrComponent: $result")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch $packageOrComponent on display $displayId", e)
            false
        }
    }

    private fun launchFullscreenWebTarget(activityClassName: String, displayId: Int, url: String) {
        clearSplitState()
        // Force-stop the previous app to prevent screen flash during transition
        val previousApp = currentVdApp
        val newTarget = internalComponentName(activityClassName)
        if (previousApp != newTarget) {
            forceStopAppIfNeeded(previousApp)
        }
        launchInternalActivity(activityClassName, displayId, url, splitMode = false)
        currentVdApp = newTarget
        currentWebUrl = url
        currentWebSplitMode = false
        activeSession = ActiveLaunchSession(
            mode = SessionMode.INTERNAL_WEBVIEW,
            launchTarget = newTarget,
            url = url
        )
    }

    private fun launchSplitWebTarget(activityClassName: String, displayId: Int, url: String) {
        if (!canLaunchPrimarySplitTask()) {
            Log.w(TAG, "Split web launch requested without a primary app; falling back to fullscreen")
            launchFullscreenWebTarget(activityClassName, displayId, url)
            return
        }
        relaunchPrimaryTaskForSplit(displayId)
        val componentName = internalComponentName(activityClassName)
        launchInternalActivity(activityClassName, displayId, url, splitMode = true)
        activeSplitUrl = url
        activeSplitComponent = componentName
        activeSplitSession = ActiveLaunchSession(
            mode = SessionMode.INTERNAL_WEBVIEW,
            launchTarget = componentName,
            url = url
        )
    }

    /**
     * Launch an OTT URL in an external browser app on the virtual display.
     * Falls back to internal WebBrowserActivity if no browser is found or launch fails.
     */
    private fun launchExternalBrowserTarget(displayId: Int, url: String, sourceAppPackage: String? = null, allowFallback: Boolean = true) {
        clearSplitState()
        val previousApp = currentVdApp

        val browser = BrowserResolver.resolve(this, url)
        if (browser != null) {
            val command = buildExternalBrowserCommand(displayId, url, browser.componentFlat, freeform = false)
            val service = virtualDisplayManager?.getPrivilegedService()
            if (service == null) {
                Log.w(TAG, "Privileged service unavailable for external browser launch")
                if (allowFallback) {
                    Log.w(TAG, "Falling back to internal WebBrowserActivity for $url")
                    launchFullscreenWebTarget("com.castla.mirror.ui.WebBrowserActivity", displayId, url)
                    activeSession = ActiveLaunchSession(
                        mode = SessionMode.INTERNAL_WEBVIEW,
                        launchTarget = internalComponentName("com.castla.mirror.ui.WebBrowserActivity"),
                        url = url,
                        sourceAppPackage = sourceAppPackage
                    )
                }
                return
            }
            val launched = try {
                Log.i(TAG, "External browser launch: $command")
                service.execCommand(command)
                true
            } catch (e: Exception) {
                Log.e(TAG, "External browser launch failed", e)
                false
            }

            if (launched) {
                // Compare by package name to avoid false mismatch between component and package formats
                val previousPkg = previousApp.substringBefore('/')
                if (previousPkg != browser.packageName) {
                    forceStopAppIfNeeded(previousApp)
                }
                currentVdApp = browser.componentFlat
                currentWebUrl = url
                currentWebSplitMode = false
                activeSession = ActiveLaunchSession(
                    mode = SessionMode.EXTERNAL_BROWSER,
                    launchTarget = browser.componentFlat,
                    url = url,
                    sourceAppPackage = sourceAppPackage,
                    browserPackage = browser.packageName
                )
                Log.i(TAG, "External browser launched successfully: ${browser.componentFlat} -> $url")
                return
            }
        }

        // Fallback to internal WebBrowserActivity
        if (allowFallback) {
            Log.w(TAG, "Falling back to internal WebBrowserActivity for $url")
            launchFullscreenWebTarget("com.castla.mirror.ui.WebBrowserActivity", displayId, url)
            activeSession = ActiveLaunchSession(
                mode = SessionMode.INTERNAL_WEBVIEW,
                launchTarget = internalComponentName("com.castla.mirror.ui.WebBrowserActivity"),
                url = url,
                sourceAppPackage = sourceAppPackage
            )
        }
    }

    /**
     * Launch an OTT URL in an external browser in split/freeform mode.
     * Falls back to internal WebBrowserActivity if no browser is found or launch fails.
     */
    private fun launchSplitExternalBrowserTarget(displayId: Int, url: String, sourceAppPackage: String? = null, allowFallback: Boolean = true) {
        if (!canLaunchPrimarySplitTask()) {
            Log.w(TAG, "Split external browser launch requested without a primary app; falling back to fullscreen")
            launchExternalBrowserTarget(displayId, url, sourceAppPackage, allowFallback)
            return
        }

        val browser = BrowserResolver.resolve(this, url)
        if (browser != null) {
            val service = virtualDisplayManager?.getPrivilegedService()
            if (service == null) {
                Log.w(TAG, "Privileged service unavailable for split external browser launch")
                if (allowFallback) {
                    launchSplitWebTarget("com.castla.mirror.ui.WebBrowserActivity", displayId, url)
                    activeSplitSession = ActiveLaunchSession(
                        mode = SessionMode.INTERNAL_WEBVIEW,
                        launchTarget = internalComponentName("com.castla.mirror.ui.WebBrowserActivity"),
                        url = url,
                        sourceAppPackage = sourceAppPackage
                    )
                }
                return
            }
            relaunchPrimaryTaskForSplit(displayId)
            val command = buildExternalBrowserCommand(displayId, url, browser.componentFlat, freeform = true)
            val launched = try {
                Log.i(TAG, "Split external browser launch: $command")
                service.execCommand(command)
                true
            } catch (e: Exception) {
                Log.e(TAG, "Split external browser launch failed", e)
                false
            }

            if (launched) {
                scheduleSplitTaskResize(displayId, browser.componentFlat)
                activeSplitComponent = browser.componentFlat
                activeSplitUrl = url
                activeSplitSession = ActiveLaunchSession(
                    mode = SessionMode.EXTERNAL_BROWSER,
                    launchTarget = browser.componentFlat,
                    url = url,
                    sourceAppPackage = sourceAppPackage,
                    browserPackage = browser.packageName
                )
                Log.i(TAG, "Split external browser launched successfully: ${browser.componentFlat} -> $url")
                return
            }
        }

        // Fallback to internal WebBrowserActivity in split mode
        if (allowFallback) {
            Log.w(TAG, "Falling back to internal WebBrowserActivity (split) for $url")
            launchSplitWebTarget("com.castla.mirror.ui.WebBrowserActivity", displayId, url)
            activeSplitSession = ActiveLaunchSession(
                mode = SessionMode.INTERNAL_WEBVIEW,
                launchTarget = internalComponentName("com.castla.mirror.ui.WebBrowserActivity"),
                url = url,
                sourceAppPackage = sourceAppPackage
            )
        }
    }

    /**
     * Build an `am start` shell command to launch an external browser with ACTION_VIEW on the virtual display.
     */
    private fun buildExternalBrowserCommand(displayId: Int, url: String, browserComponent: String, freeform: Boolean): String {
        return buildString {
            append("am start -W --display $displayId -f 0x18000000 ")
            if (freeform) {
                append("--windowingMode 5 ")
            }
            append("-a android.intent.action.VIEW ")
            append("-d ${escapeShellArg(url)} ")
            append("-n ${escapeShellArg(browserComponent)} ")
        }.trim()
    }

    private fun launchFullscreenStandardTarget(launchTarget: String) {
        clearSplitState()
        val resolvedTarget = normalizeLaunchTarget(launchTarget)
        // Force-stop the previous app first to prevent its screen from flashing
        // during the transition to the new app
        val previousApp = currentVdApp
        if (previousApp != resolvedTarget) {
            forceStopAppIfNeeded(previousApp)
        }
        val launched = virtualDisplayManager?.launchAppOnDisplay(resolvedTarget) ?: false
        if (!launched && virtualDisplayManager?.hasVirtualDisplay() == false) {
            Log.w(TAG, "Launch failed due to stale display, rebuilding pipeline and retrying")
            rebuildAndRetryLaunch(resolvedTarget)
            return
        }
        currentVdApp = resolvedTarget
        currentWebUrl = null
        activeSession = ActiveLaunchSession(mode = SessionMode.STANDARD_APP, launchTarget = resolvedTarget)
    }

    private fun rebuildAndRetryLaunch(resolvedTarget: String) {
        // Set the target so restoreCurrentVdContent will launch it once VD is ready
        currentVdApp = resolvedTarget
        currentWebUrl = null
        activeSession = ActiveLaunchSession(mode = SessionMode.STANDARD_APP, launchTarget = resolvedTarget)
        serviceScope.launch {
            try {
                rebuildPipeline(currentWidth, currentHeight, force = true)
                // If VD is already available (sync path), launch directly
                val vdm = virtualDisplayManager
                if (vdm != null && vdm.hasVirtualDisplay()) {
                    val retried = vdm.launchAppOnDisplay(resolvedTarget)
                    if (retried) {
                        Log.i(TAG, "Retry launch succeeded for $resolvedTarget after pipeline rebuild")
                    } else {
                        Log.w(TAG, "Retry launch deferred — VD will launch via restoreCurrentVdContent on bind completion")
                    }
                } else {
                    Log.i(TAG, "VD not yet available after rebuild — app will launch when Shizuku binds")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to rebuild pipeline for retry launch", e)
            }
        }
    }

    private fun launchSplitStandardTarget(launchTarget: String) {
        val displayId = virtualDisplayManager?.getDisplayId() ?: -1
        Log.i(TAG, "launchSplitStandardTarget: target=$launchTarget displayId=$displayId currentVdApp=$currentVdApp canSplit=${canLaunchPrimarySplitTask()} currentSize=${currentWidth}x${currentHeight}")
        if (displayId < 0 || !canLaunchPrimarySplitTask()) {
            Log.w(TAG, "Split app launch requested without a primary app; falling back to fullscreen")
            launchFullscreenStandardTarget(launchTarget)
            return
        }
        val resolvedTarget = normalizeLaunchTarget(launchTarget)
        val primaryBounds = primaryTaskBounds()
        val secondaryBounds = splitTaskBounds()
        Log.i(TAG, "Split bounds: primary=$primaryBounds secondary=$secondaryBounds")

        // Step 1: Convert primary to freeform and resize to left
        relaunchPrimaryTaskForSplit(displayId)

        // Step 2: Launch secondary in freeform mode
        val launched = launchTargetOnDisplay(displayId, resolvedTarget, freeform = true)
        if (launched) {
            // Step 3: Resize secondary to right bounds
            scheduleSplitTaskResize(displayId, resolvedTarget)
            activeSplitComponent = resolvedTarget
            activeSplitUrl = null
        }
    }

    private fun relaunchPrimaryTaskForSplit(displayId: Int) {
        if (displayId < 0 || !canLaunchPrimarySplitTask()) return
        val primaryTarget = normalizeLaunchTarget(currentVdApp)
        val primaryPkg = primaryTarget.substringBefore('/')
        val bounds = primaryTaskBounds()
        val service = virtualDisplayManager?.getPrivilegedService() ?: return

        // Try to find and convert existing fullscreen task to freeform
        val existingTaskId = findTaskId(service, displayId, primaryTarget)
        if (existingTaskId != null) {
            val existingMode = parseDisplayTasks(service.execCommand("dumpsys activity activities"), displayId)
                .firstOrNull { it.taskId == existingTaskId }?.mode ?: "unknown"
            if (existingMode == "freeform") {
                // Already freeform, just resize
                service.execCommand("cmd activity task resize $existingTaskId ${bounds.left} ${bounds.top} ${bounds.right} ${bounds.bottom}")
                Log.i(TAG, "Primary task $existingTaskId already freeform, resized to $bounds")
                return
            }
        }

        // Must force-stop and relaunch in freeform mode (fullscreen→freeform can't be done via resize)
        Log.i(TAG, "Force-restarting primary $primaryPkg in freeform mode")
        service.execCommand("am force-stop $primaryPkg")
        val isExternalBrowser = activeSession?.mode == SessionMode.EXTERNAL_BROWSER
        val launched = if (isExternalBrowser && currentWebUrl != null) {
            // Re-launch external browser with the same URL
            val browser = BrowserResolver.resolve(this, currentWebUrl!!)
            if (browser != null) {
                val cmd = buildExternalBrowserCommand(displayId, currentWebUrl!!, browser.componentFlat, freeform = true)
                try { service.execCommand(cmd); true } catch (_: Exception) { false }
            } else {
                launchTargetOnDisplay(displayId, primaryTarget, "url", currentWebUrl!!, freeform = true)
            }
        } else if (primaryTarget.contains("WebBrowserActivity")) {
            launchTargetOnDisplay(displayId, primaryTarget, "url", currentWebUrl ?: "https://m.youtube.com", freeform = true)
        } else {
            launchTargetOnDisplay(displayId, primaryTarget, freeform = true)
        }
        if (launched) {
            // Resize primary to left bounds after launch
            schedulePrimaryTaskResize(displayId, primaryTarget)
        }
    }

    private fun launchInternalActivity(activityClassName: String, displayId: Int, url: String, splitMode: Boolean = false) {
        if (displayId < 0) return

        val launchUrl = if (splitMode && !url.contains("#split=true")) "$url#split=true" else url
        if (splitMode) {
            val launchTarget = internalComponentName(activityClassName)
            val launched = launchTargetOnDisplay(
                displayId,
                launchTarget,
                extraKey = "url",
                extraValue = launchUrl,
                freeform = true
            )
            if (launched) {
                scheduleSplitTaskResize(displayId, launchTarget)
                Log.i(TAG, "Launched $activityClassName on display $displayId via shell split command")
                return
            }
        }

        launchOwnActivityOnDisplay(activityClassName, displayId, launchUrl, splitMode, applySplitBounds = splitMode)
    }

    private fun launchOwnActivityOnDisplay(
        activityClassName: String,
        displayId: Int,
        url: String,
        splitMode: Boolean = false,
        applySplitBounds: Boolean = false
    ) {
        if (displayId < 0) return

        val launchUrl = if (splitMode && !url.contains("#split=true")) "$url#split=true" else url

        val options = android.app.ActivityOptions.makeBasic()
        options.launchDisplayId = displayId
        if (applySplitBounds) {
            options.setLaunchBounds(splitTaskBounds())
        }
        val intent = Intent().apply {
            setClassName(this@MirrorForegroundService, activityClassName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("url", launchUrl)
            putExtra("splitMode", splitMode)
        }
        try {
            startActivity(intent, options.toBundle())
            if (applySplitBounds) {
                scheduleSplitTaskResize(displayId, internalComponentName(activityClassName))
            }
            Log.i(TAG, "Launched $activityClassName on display $displayId via ActivityOptions")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch $activityClassName on display $displayId via ActivityOptions", e)
            val launched = launchTargetOnDisplay(
                displayId,
                internalComponentName(activityClassName),
                "url",
                launchUrl,
                freeform = applySplitBounds
            )
            if (applySplitBounds && launched) {
                scheduleSplitTaskResize(displayId, internalComponentName(activityClassName))
            }
        }
    }

    private fun splitTaskBounds(): android.graphics.Rect {
        val leftWidth = (currentHeight * 9f / 16f).toInt()
            .coerceAtLeast((currentWidth * 0.25f).toInt())
            .coerceAtMost((currentWidth - 320).coerceAtLeast(0))
        return android.graphics.Rect(leftWidth, 0, currentWidth, currentHeight)
    }

    private fun schedulePrimaryTaskResize(displayId: Int, launchTarget: String) {
        scheduleTaskResize(displayId, primaryTaskBounds(), "primary", launchTarget)
    }

    private fun scheduleSplitTaskResize(displayId: Int, launchTarget: String) {
        scheduleTaskResize(displayId, splitTaskBounds(), "split", launchTarget)
    }

    private fun scheduleTaskResize(
        displayId: Int,
        bounds: android.graphics.Rect,
        label: String,
        launchTarget: String
    ) {
        if (displayId < 0 || currentWidth <= 0 || currentHeight <= 0) return
        serviceScope.launch(Dispatchers.IO) {
            repeat(10) { attempt ->
                kotlinx.coroutines.delay(if (attempt == 0) 250L else 400L)
                val service = virtualDisplayManager?.getPrivilegedService() ?: return@launch
                // Use current display ID in case VD was recreated
                val currentDisplayId = virtualDisplayManager?.getDisplayId() ?: displayId
                val taskId = findTaskId(service, currentDisplayId, launchTarget) ?: return@repeat
                service.execCommand("cmd activity task resizeable $taskId 2")
                service.execCommand("cmd activity task resize $taskId ${bounds.left} ${bounds.top} ${bounds.right} ${bounds.bottom}")
                Log.i(TAG, "Resized $label task $taskId on display $currentDisplayId to ${bounds.flattenToString()}")
                return@launch
            }
            Log.w(TAG, "Failed to locate $label task on display ${virtualDisplayManager?.getDisplayId() ?: displayId} for resizing")
        }
    }

    private fun findTaskId(service: IPrivilegedService, displayId: Int, launchTarget: String): Int? {
        val dumpsys = service.execCommand("dumpsys activity activities")
        val tasks = parseDisplayTasks(dumpsys, displayId)
        Log.d(TAG, "findTaskId: display=$displayId target=$launchTarget found ${tasks.size} tasks: ${tasks.map { "id=${it.taskId} mode=${it.mode}" }}")
        val match = tasks.firstOrNull { taskMatchesLaunchTarget(it, launchTarget) }
        if (match != null) {
            Log.d(TAG, "findTaskId: matched task ${match.taskId} (mode=${match.mode})")
        } else if (tasks.isNotEmpty()) {
            Log.d(TAG, "findTaskId: no match for candidates=${launchTargetCandidates(launchTarget)}")
            tasks.forEach { Log.d(TAG, "  task ${it.taskId}: ${it.header.take(120)}") }
        }
        return match?.taskId
    }

    private fun parseDisplayTasks(dumpsys: String?, displayId: Int): List<DisplayTaskSnapshot> {
        if (dumpsys.isNullOrBlank()) return emptyList()
        val startMarker = "Display #$displayId (activities from top to bottom):"
        val startIndex = dumpsys.indexOf(startMarker)
        if (startIndex < 0) return emptyList()
        val remaining = dumpsys.substring(startIndex + startMarker.length)
        val nextDisplay = Regex("\nDisplay #\\d+ \\(activities from top to bottom\\):").find(remaining)
        val displayBlock = remaining.substring(0, nextDisplay?.range?.first ?: remaining.length)

        val tasks = mutableListOf<DisplayTaskSnapshot>()
        var currentHeader: String? = null
        val bodyLines = mutableListOf<String>()

        fun flushCurrent() {
            val header = currentHeader ?: return
            val snapshot = createTaskSnapshot(header, bodyLines)
            if (snapshot != null) {
                tasks += snapshot
            }
            currentHeader = null
            bodyLines.clear()
        }

        for (line in displayBlock.lines()) {
            val trimmed = line.trimStart()
            if (trimmed.startsWith("* Task{")) {
                flushCurrent()
                currentHeader = trimmed
            } else if (currentHeader != null) {
                bodyLines += trimmed
            }
        }
        flushCurrent()
        return tasks
    }

    private fun createTaskSnapshot(header: String, bodyLines: List<String>): DisplayTaskSnapshot? {
        val taskId = Regex("#(\\d+)").find(header)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: return null
        val mode = Regex("mode=([a-zA-Z_]+)").find(header)?.groupValues?.getOrNull(1) ?: "unknown"
        return DisplayTaskSnapshot(taskId, mode, header, bodyLines.joinToString("\n"))
    }

    private fun taskMatchesLaunchTarget(task: DisplayTaskSnapshot, launchTarget: String): Boolean {
        val haystack = buildString {
            append(task.header)
            append('\n')
            append(task.body)
        }
        return launchTargetCandidates(launchTarget).any { candidate ->
            candidate.isNotBlank() && haystack.contains(candidate)
        }
    }

    private fun launchTargetCandidates(launchTarget: String): List<String> {
        if (!launchTarget.contains('/')) return listOf(launchTarget)

        val pkg = launchTarget.substringBefore('/')
        val rawClassName = launchTarget.substringAfter('/')
        val fullClassName = when {
            rawClassName.startsWith('.') -> pkg + rawClassName
            rawClassName.startsWith(pkg) -> rawClassName
            else -> rawClassName
        }
        val shortClassName = if (fullClassName.startsWith(pkg)) {
            "." + fullClassName.removePrefix(pkg).trimStart('.')
        } else {
            rawClassName
        }

        return listOf(
            launchTarget,
            "$pkg/$fullClassName",
            "$pkg/$shortClassName",
            fullClassName,
            shortClassName,
            pkg
        ).distinct()
    }

    private fun showSplitPresentation(url: String) {

        activeSplitUrl = url
        mainHandler.post {
            try {
                val displayId = virtualDisplayManager?.getDisplayId() ?: -1
                if (displayId < 0) {
                    Log.w(TAG, "Split presentation deferred: virtual display unavailable")
                    return@post
                }

                val displayManager = getSystemService(DisplayManager::class.java)
                val display = displayManager?.getDisplay(displayId)
                if (display == null) {
                    Log.w(TAG, "Split presentation deferred: display $displayId not found")
                    return@post
                }

                val existing = splitPresentation
                if (existing != null && existing.display.displayId == display.displayId) {
                    existing.loadUrl(url)
                    return@post
                }

                existing?.dismiss()
                splitPresentation = SplitWebPresentation(this, display, url) {
                    dismissSplitPresentation(clearState = true)
                }.apply {
                    setOnDismissListener {
                        if (splitPresentation === this) {
                            splitPresentation = null
                        }
                    }
                    show()
                }
                Log.i(TAG, "Showing split presentation on display $displayId")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to show split presentation", e)
            }
        }
    }

    private fun dismissSplitPresentation(clearState: Boolean = false) {
        if (clearState) {
            clearSplitState()
        }
        mainHandler.post {
            val presentation = splitPresentation ?: return@post
            splitPresentation = null
            try {
                presentation.dismiss()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to dismiss split presentation", e)
            }
        }
    }

    private fun launchAppFromWebLauncher(pkgName: String, componentName: String? = null, splitMode: Boolean = false, pane: String = if (splitMode) "secondary" else "primary") {
        Log.i(TAG, "launchAppFromWebLauncher: pkg=$pkgName split=$splitMode pane=$pane singleVdSplit=$singleVdSplit")
        if (pane == "secondary") {
            if (singleVdSplit) {
                Log.d(TAG, "Ignoring secondary launch in single-VD split mode (pkg=$pkgName)")
                return
            }
            if (pkgName.isBlank()) {
                releaseSecondaryPipeline(clearState = true)
                return
            }
            val webUrl = OttCatalog.webUrlFor(pkgName)
            val wasSecondaryVideo = isSecondaryAppVideo
            isSecondaryAppVideo = webUrl != null
            if (webUrl != null) {
                // Try external browser for secondary pane OTT too
                val browser = BrowserResolver.resolve(this, webUrl)
                if (browser != null && secondaryDisplayId >= 0) {
                    val service = virtualDisplayManager?.getPrivilegedService()
                    if (service != null) {
                        val cmd = buildExternalBrowserCommand(secondaryDisplayId, webUrl, browser.componentFlat, freeform = false)
                        try {
                            service.execCommand(cmd)
                            currentSecondaryApp = browser.componentFlat
                            currentSecondaryWebUrl = webUrl
                            Log.i(TAG, "Web Launcher: Launched secondary OTT via external browser: $pkgName -> $webUrl")
                            rebalanceSplitBitrates()
                            return
                        } catch (e: Exception) {
                            Log.w(TAG, "Secondary external browser launch failed, falling back to WebBrowserActivity", e)
                        }
                    }
                }
                // Fallback to internal WebBrowserActivity
                val webComponentName = internalComponentName("com.castla.mirror.ui.WebBrowserActivity")
                Log.i(TAG, "Web Launcher: Launching secondary OTT app via WebBrowserActivity: $pkgName -> $webUrl")
                launchSecondaryTarget(webComponentName, webUrl)
            } else {
                val launchTarget = componentName ?: pkgName
                Log.i(TAG, "Web Launcher: Launching secondary app: $pkgName (target=$launchTarget)")
                launchSecondaryTarget(launchTarget)
            }
            if (wasSecondaryVideo != isSecondaryAppVideo) rebalanceSplitBitrates()
            return
        }

        // In freeform split mode, always launch native apps directly (skip OTT web redirect)
        val webUrl = if (singleVdSplit && splitMode) null else OttCatalog.webUrlFor(pkgName)
        val displayId = virtualDisplayManager?.getDisplayId() ?: -1

        if (webUrl != null) {
            Log.i(TAG, "Web Launcher: Launching OTT app via external browser: $pkgName -> $webUrl (splitMode=$splitMode)")

            dismissSplitPresentation(clearState = true)
            if (splitMode && canLaunchPrimarySplitTask()) {
                launchSplitExternalBrowserTarget(displayId, webUrl, pkgName)
            } else {
                launchExternalBrowserTarget(displayId, webUrl, pkgName)
            }

            isCurrentAppVideo = true
            rebalanceSplitBitrates()
        } else {
            val launchTarget = componentName ?: pkgName
            Log.i(TAG, "Web Launcher: Launching standard app: $pkgName (target=$launchTarget, splitMode=$splitMode, singleVdSplit=$singleVdSplit)")

            if (splitMode && canLaunchPrimarySplitTask()) {
                launchSplitStandardTarget(launchTarget)
            } else {
                launchFullscreenStandardTarget(launchTarget)
            }

            isCurrentAppVideo = false
            rebalanceSplitBitrates()
        }

        if (currentCodecMode == "mjpeg") {
            touchInjector?.onTouchEvent(com.castla.mirror.server.TouchEvent("down", 0.5f, 0.5f, 99))
            serviceScope.launch {
                kotlinx.coroutines.delay(50)
                touchInjector?.onTouchEvent(com.castla.mirror.server.TouchEvent("up", 0.5f, 0.5f, 99))
            }
        }
    }

    private fun trySetupVirtualDisplay(
        width: Int,
        height: Int,
        surface: android.view.Surface,
        onResult: (Boolean) -> Unit
    ) {
        shizukuSetupInProgress = true
        var resultDelivered = false
        val safeResult = { success: Boolean ->
            if (!resultDelivered) {
                resultDelivered = true
                shizukuSetupInProgress = false
                if (!success) {
                    releaseShizukuSession("virtual_display_setup_failed")
                }
                onResult(success)
            }
        }

        try {
            releaseShizukuSession("before_virtual_display_setup")
            val setup = ShizukuSetup()
            setup.init(bindService = false)

            if (!setup.isAvailable() || !setup.hasPermission()) {
                Log.i(TAG, "Shizuku not available/permitted")
                setup.release()
                safeResult(false)
                return
            }

            shizukuSetup = setup
            val vdm = VirtualDisplayManager()
            virtualDisplayManager = vdm

            vdm.reconnectListener = reconnect@ {
                if (!browserConnected) {
                    Log.w(TAG, "Ignoring stale Shizuku reconnect after browser disconnect")
                    return@reconnect
                }
                val surf = currentEncoderSurface
                if (surf != null) {
                    vdm.createVirtualDisplay(currentWidth, currentHeight, 160, surf)
                    if (vdm.hasVirtualDisplay()) {
                        // Refresh the binder so audio/text/input paths use the new connection
                        setup.attachPrivilegedService(vdm.getPrivilegedService())
                        touchInjector?.setVirtualDisplayInjector { action, x, y, pointerId ->
                            vdm.injectInput(action, x, y, pointerId)
                        }
                        restoreCurrentVdContent()
                    }
                }
            }

            vdm.bindShizukuService { bound ->
                try {
                    if (!browserConnected) {
                        Log.w(TAG, "Ignoring stale virtual display bind callback after browser disconnect")
                        safeResult(false)
                        return@bindShizukuService
                    }
                    if (bound) {
                        shizukuBindRetryCount = 0
                        // Enable freeform windowing support for split mode
                        try {
                            vdm.getPrivilegedService()?.let { svc ->
                                svc.execCommand("settings put global enable_freeform_support 1")
                                svc.execCommand("settings put global force_resizable_activities 1")
                                Log.i(TAG, "Enabled freeform windowing support")
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to enable freeform support (non-fatal)", e)
                        }
                        // Use latest dimensions/surface in case viewport changed during async bind
                        val actualWidth = if (currentWidth > 0) currentWidth else width
                        val actualHeight = if (currentHeight > 0) currentHeight else height
                        val actualSurface = currentEncoderSurface ?: surface
                        val actualDpi = computeVirtualDisplayDpi(actualWidth, actualHeight)
                        vdm.createVirtualDisplay(actualWidth, actualHeight, actualDpi, actualSurface)
                        if (vdm.hasVirtualDisplay()) {
                            setup.attachPrivilegedService(vdm.getPrivilegedService())
                            touchInjector?.setVirtualDisplayInjector { action, x, y, pointerId ->
                                vdm.injectInput(action, x, y, pointerId)
                            }
                            // Harden Shizuku (fortify + install watchdog if needed) for WiFi-off survival
                            serviceScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                val ok = setup.ensureShizukuHardened()
                                Log.i(TAG, "ensureShizukuHardened (service): $ok")
                            }
                            safeResult(true)
                        } else {
                            safeResult(false)
                        }
                    } else {
                        safeResult(false)
                    }
                } catch (e: Exception) {
                    safeResult(false)
                }
            }

            // Timeout: if binding callback never fires (e.g. service process killed by Shizuku),
            // reset flags and retry up to SHIZUKU_MAX_RETRIES times.
            mainHandler.postDelayed({
                if (!resultDelivered) {
                    shizukuBindRetryCount++
                    if (shizukuBindRetryCount <= SHIZUKU_MAX_RETRIES) {
                        Log.w(TAG, "Shizuku binding timed out (attempt $shizukuBindRetryCount/$SHIZUKU_MAX_RETRIES) — retrying")
                        resultDelivered = true
                        shizukuSetupInProgress = false
                        releaseShizukuSession("binding_timeout")
                        mainHandler.postDelayed({
                            if (browserConnected) {
                                val surf = currentEncoderSurface
                                if (surf != null) {
                                    Log.i(TAG, "Retrying Shizuku setup after timeout (attempt ${shizukuBindRetryCount + 1})")
                                    trySetupVirtualDisplay(currentWidth, currentHeight, surf, onResult)
                                } else {
                                    onResult(false)
                                }
                            } else {
                                onResult(false)
                            }
                        }, 2_000)
                    } else {
                        Log.e(TAG, "Shizuku binding failed after $SHIZUKU_MAX_RETRIES retries — Shizuku server may need restart")
                        safeResult(false)
                    }
                }
            }, 8_000)
        } catch (e: Exception) {
            safeResult(false)
        }
    }

    private fun cancelPendingBrowserDisconnect(reason: String) {
        val job = pendingBrowserDisconnectJob ?: return
        Log.i(TAG, "Cancelling pending browser disconnect: $reason")
        job.cancel()
        pendingBrowserDisconnectJob = null
    }

    private fun scheduleBrowserDisconnect() {
        if (pendingBrowserDisconnectJob != null) {
            Log.d(TAG, "Browser disconnect already pending")
            return
        }
        val screenOff = screenOffPolicy.isScreenOff
        val graceMs = DisconnectPolicy.graceMs(screenOff)
        pendingBrowserDisconnectJob = serviceScope.launch {
            Log.i(TAG, "Scheduling browser disconnect grace window: ${graceMs}ms (screenOff=$screenOff)")
            kotlinx.coroutines.delay(graceMs)
            pendingBrowserDisconnectJob = null
            val stillConnected = mirrorServer?.isBrowserConnected() == true
            if (stillConnected) {
                Log.i(TAG, "Browser reconnected during grace window; keeping pipeline alive")
                return@launch
            }
            if (!DisconnectPolicy.shouldTeardown(screenOffPolicy.isScreenOff, isBrowserConnected = false)) {
                Log.i(TAG, "Screen is off — deferring teardown until screen turns on")
                return@launch
            }
            if (browserConnected) {
                browserConnected = false
                onBrowserDisconnected()
            }
            browserConnectionListener?.invoke(false)
        }
    }

    private fun onBrowserConnected() {
        try {
            acquireWakeLocks()

            // Send current thermal status to new browser client for profile auto-switching
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                broadcastThermalStatus(_thermalStatus.value)
            }

            if (videoEncoder != null) {
                // Reconnection — rebuild existing pipeline and restart audio
                Log.i(TAG, "Browser reconnected — rebuilding pipeline")
                serviceScope.launch {
                    rebuildPipeline(currentWidth, currentHeight, force = true)
                }
                ensureAudioCaptureState()
                return
            }

            // First connection — start encoder, VD, audio, ABR
            Log.i(TAG, "Browser connected — starting active pipeline")
            val width = currentWidth
            val height = currentHeight
            val fps = thermalFpsOverride ?: currentFps

            val baseTargetBitrate = com.castla.mirror.utils.StreamMath.calculateBaseBitrate(width, height)
            targetBitrate = baseTargetBitrate
            currentBitrate = targetBitrate
            preThermalTargetBitrate = targetBitrate

            startAbrLoop()
            startAutoScaleLoop()

            videoEncoder = VideoEncoder(width, height, currentBitrate, fps).also { encoder ->
                val surface = encoder.createInputSurface()
                currentEncoderSurface = surface

                mirrorServer?.setKeyframeRequester("primary") { encoder.requestKeyFrame() }

                encoder.onSpsPps = { spsPps -> mirrorServer?.broadcastSpsPps(spsPps) }
                encoder.start { frameData, isKeyFrame ->
                    mirrorServer?.broadcastFrame(frameData, isKeyFrame)
                }

                trySetupVirtualDisplay(width, height, surface) { shizukuActive ->
                    if (shizukuActive) {
                        screenCapture?.stopCapture()
                        currentVdApp = "HOME"
                    } else {
                        try {
                            screenCapture?.startCapture(surface, width, height)
                            Log.w(TAG, "Fallback: MediaProjection mirroring at ${width}x${height} (raw phone screen)")
                        } catch (e: Exception) {
                            Log.e(TAG, "MediaProjection fallback failed", e)
                        }
                    }
                    // Start audio after privilegedService is attached so REMOTE_SUBMIX is available
                    ensureAudioCaptureState()
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Browser connection activation failed", t)
            requestStopAsync("browser_connect_failure")
        }
    }

    private fun releaseShizukuSession(reason: String) {
        Log.i(TAG, "Releasing Shizuku session: $reason")
        try { touchInjector?.setVirtualDisplayInjector(null) } catch (_: Exception) {}
        try { virtualDisplayManager?.release() } catch (e: Exception) { Log.w(TAG, "Failed to release virtual display manager", e) }
        try { shizukuSetup?.release() } catch (e: Exception) { Log.w(TAG, "Failed to release shizuku setup", e) }
        virtualDisplayManager = null
        shizukuSetup = null
    }

    private fun ensureAudioCaptureState(codecOverride: String? = null) {
        val orch = audioOrchestrator ?: return
        orch.audioEnabled = pendingAudioEnabled && AudioCapture.isSupported()
        orch.muteLocalAudio = pendingMuteLocalAudio
        orch.browserConnected = browserConnected
        orch.ensure(codecOverride)
    }

    private fun restoreCurrentVdContent() {
        val vdm = virtualDisplayManager ?: return
        when (currentVdApp) {
            "HOME", "", "com.android.settings" -> {
                currentVdApp = "HOME"
            }
            else -> {
                if (currentVdApp.contains("SplitWebBrowserActivity")) {
                    currentVdApp = "HOME"
                } else if (hasActiveSplitSession()) {
                    val displayId = vdm.getDisplayId()
                    relaunchPrimaryTaskForSplit(displayId)
                    // Restore split pane: prefer external browser if that was the original mode
                    val splitSession = activeSplitSession
                    when {
                        splitSession?.mode == SessionMode.EXTERNAL_BROWSER && activeSplitUrl != null -> {
                            val browser = BrowserResolver.resolve(this, activeSplitUrl!!)
                            if (browser != null) {
                                val cmd = buildExternalBrowserCommand(displayId, activeSplitUrl!!, browser.componentFlat, freeform = true)
                                val launched = try {
                                    val svc = virtualDisplayManager?.getPrivilegedService()
                                    if (svc != null) { svc.execCommand(cmd); true } else false
                                } catch (_: Exception) { false }
                                if (launched) {
                                    scheduleSplitTaskResize(displayId, browser.componentFlat)
                                } else {
                                    // Fallback to internal WebBrowserActivity
                                    launchInternalActivity("com.castla.mirror.ui.WebBrowserActivity", displayId, activeSplitUrl!!, splitMode = true)
                                }
                            } else {
                                launchInternalActivity("com.castla.mirror.ui.WebBrowserActivity", displayId, activeSplitUrl!!, splitMode = true)
                            }
                        }
                        activeSplitUrl != null -> {
                            launchInternalActivity("com.castla.mirror.ui.WebBrowserActivity", displayId, activeSplitUrl!!, splitMode = true)
                        }
                        activeSplitComponent != null -> {
                            val launched = launchTargetOnDisplay(displayId, activeSplitComponent!!, freeform = true)
                            if (launched) {
                                scheduleSplitTaskResize(displayId, activeSplitComponent!!)
                            }
                        }
                    }
                } else if (activeSession?.mode == SessionMode.EXTERNAL_BROWSER && currentWebUrl != null) {
                    // Restore external browser with URL
                    val displayId = vdm.getDisplayId()
                    val browser = BrowserResolver.resolve(this, currentWebUrl!!)
                    if (browser != null) {
                        val cmd = buildExternalBrowserCommand(displayId, currentWebUrl!!, browser.componentFlat, freeform = false)
                        val launched = try {
                            val svc = virtualDisplayManager?.getPrivilegedService()
                            if (svc != null) { svc.execCommand(cmd); true } else false
                        } catch (_: Exception) { false }
                        if (!launched) {
                            // Fallback to internal WebBrowserActivity
                            launchInternalActivity("com.castla.mirror.ui.WebBrowserActivity", displayId, currentWebUrl!!, splitMode = currentWebSplitMode)
                        }
                    } else {
                        launchInternalActivity("com.castla.mirror.ui.WebBrowserActivity", vdm.getDisplayId(), currentWebUrl!!, splitMode = currentWebSplitMode)
                    }
                } else if (currentVdApp.contains("WebBrowserActivity")) {
                    val activityClassName = currentVdApp.substringAfter('/')
                    launchInternalActivity(activityClassName, vdm.getDisplayId(), currentWebUrl ?: "https://m.youtube.com", splitMode = currentWebSplitMode)
                } else {
                    vdm.launchAppOnDisplay(currentVdApp)
                }
            }
        }
        if (!singleVdSplit && secondaryWidth > 0 && secondaryHeight > 0 && currentSecondaryApp.isNotBlank()) {
            rebuildSecondaryPipeline(secondaryWidth, secondaryHeight)
        }
    }

    private fun onAudioCodecRequest(codec: String) {
        serviceScope.launch(Dispatchers.IO) {
            ensureAudioCaptureState(codecOverride = codec)
        }
    }

    private fun onBrowserDisconnected() {
        Log.i(TAG, "Browser disconnected — suspending pipeline")
        pendingBrowserDisconnectJob = null
        browserConnected = false
        dismissSplitPresentation(clearState = false)
        if (!singleVdSplit) {
            releaseSecondaryPipeline(clearState = false)
        }
        try { removeAllVdTasks() } catch (e: Exception) { Log.w(TAG, "Failed to remove VD tasks on disconnect", e) }
        releaseShizukuSession("browser_disconnected")
        
        screenCapture?.stopCapture()
        videoEncoder?.release()
        videoEncoder = null
        jpegEncoder?.release()
        jpegEncoder = null
        currentEncoderSurface = null

        audioOrchestrator?.stop()

        abrJob?.cancel()
        abrJob = null

        // Reset browser quality metrics so stale values don't affect next session
        lastQualityDroppedFrames = 0
        lastQualityAvgDelayMs = 0.0
        lastQualityBacklogDrops = 0

        releaseWakeLocks()
    }

    private fun activeInputDisplayId(): Int {
        return if (lastTouchPane == "secondary" && secondaryDisplayId >= 0) {
            secondaryDisplayId
        } else {
            virtualDisplayManager?.getDisplayId() ?: -1
        }
    }

    private fun injectText(text: String) {
        serviceScope.launch(compositionDispatcher) {
            try {
                val displayId = activeInputDisplayId()
                val service = shizukuSetup?.privilegedService
                if (service != null) {
                    service.injectText(text, displayId)
                }
            } catch (e: Exception) {}
        }
    }

    private var lastTouchPane = "primary"
    private var lastImeState = false
    private var lastImeCheckTime = 0L
    private var imeCheckSuspendUntil = 0L

    private fun parseImeVisible(dumpsys: String): Boolean {
        if (dumpsys.contains("mInputShown=true")) return true
        val imeWindowVis = Regex("""mImeWindowVis=(\d+)""")
            .find(dumpsys)?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (imeWindowVis != null && imeWindowVis != 0) return true
        val decorVisible = dumpsys.contains("mDecorViewVisible=true")
        val windowVisible = dumpsys.contains("mWindowVisible=true")
        if (decorVisible && windowVisible) return true
        return false
    }

    private fun parseHasServedInput(dumpsys: String): Boolean {
        if (dumpsys.contains("mServedView=") && !dumpsys.contains("mServedView=null")) return true
        if (dumpsys.contains("mCurClient=") && !dumpsys.contains("mCurClient=null")) {
            if (dumpsys.contains("mInputShown=true") || dumpsys.contains("mShowRequested=true")) return true
        }
        return false
    }

    private fun checkImeAndNotifyBrowser() {
        val now = System.currentTimeMillis()
        if (now - lastImeCheckTime < 500) return
        if (now < imeCheckSuspendUntil) return
        lastImeCheckTime = now

        serviceScope.launch(Dispatchers.IO) {
            try {
                val maxRetries = 4
                val retryDelays = longArrayOf(300, 400, 500, 600)
                for (attempt in 0 until maxRetries) {
                    kotlinx.coroutines.delay(retryDelays[attempt])
                    val displayId = virtualDisplayManager?.getDisplayId() ?: -1
                    val service = virtualDisplayManager?.getPrivilegedService()
                    if (service == null) return@launch

                    val result = try {
                        service.execCommand("dumpsys input_method | grep -E 'mInputShown|mImeWindowVis|mDecorViewVisible|mWindowVisible|mServedView|mShowRequested|mCurClient'")
                    } catch (e: android.os.DeadObjectException) {
                        imeCheckSuspendUntil = System.currentTimeMillis() + 10_000
                        return@launch
                    }
                    if (result == null) return@launch

                    var imeVisible = parseImeVisible(result)
                    if (!imeVisible && displayId > 0) {
                        imeVisible = parseHasServedInput(result)
                    }

                    if (imeVisible != lastImeState) {
                        lastImeState = imeVisible
                        val msg = if (imeVisible) """{"type":"showKeyboard"}""" else """{"type":"hideKeyboard"}"""
                        mirrorServer?.broadcastControlMessage(msg)
                        break
                    }
                    // IME state unchanged — if already showing or last attempt, stop retrying
                    if (lastImeState || attempt == maxRetries - 1) break
                }
            } catch (e: Exception) {
                imeCheckSuspendUntil = System.currentTimeMillis() + 10_000
            }
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val compositionDispatcher = kotlinx.coroutines.newSingleThreadContext("composition")

    private fun injectCompositionUpdate(backspaces: Int, text: String) {
        serviceScope.launch(compositionDispatcher) {
            try {
                val displayId = activeInputDisplayId()
                shizukuSetup?.privilegedService?.injectComposingText(backspaces, text, displayId)
            } catch (e: Exception) {}
        }
    }

    private fun injectKeyEvent(keyCode: Int) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val displayId = activeInputDisplayId()
                val cmd = if (displayId > 0) "input -d $displayId keyevent $keyCode" else "input keyevent $keyCode"
                shizukuSetup?.privilegedService?.execCommand(cmd)
            } catch (e: Exception) {}
        }
    }

    private fun onViewportChange(width: Int, height: Int) {
        resizeJob?.cancel()
        resizeJob = serviceScope.launch {
            rebuildPipeline(width, height)
        }
    }

    private fun hasActiveSecondaryViewportRequest(): Boolean {
        return secondaryRequestedWidth > 0 && secondaryRequestedHeight > 0
    }

    private fun shouldUseRequestedHeightForSplit(isSecondaryPane: Boolean = false): Boolean {
        return isSecondaryPane ||
            hasActiveSecondaryViewportRequest() ||
            secondaryDisplayId >= 0 ||
            currentSecondaryApp.isNotBlank() ||
            hasActiveSplitSession()
    }

    private fun effectiveMaxHeightForRequest(requestedHeight: Int, isSecondaryPane: Boolean = false): Int {
        val baseMax = when {
            shouldUseRequestedHeightForSplit(isSecondaryPane) -> {
                // In split mode, still respect auto tier / user resolution cap
                minOf(requestedHeight, currentMaxHeight)
            }
            else -> currentMaxHeight
        }
        // Apply thermal resolution cap if active
        val thermalCap = thermalMaxHeight
        return if (thermalCap != null) minOf(baseMax, thermalCap) else baseMax
    }

    private suspend fun rebuildPipeline(newWidth: Int, newHeight: Int, force: Boolean = false) {
        val effectiveMaxHeight = effectiveMaxHeightForRequest(newHeight)
        var cappedWidth = newWidth
        var cappedHeight = newHeight
        
        if (cappedHeight > effectiveMaxHeight) {
            val scale = effectiveMaxHeight.toFloat() / cappedHeight
            cappedHeight = effectiveMaxHeight
            cappedWidth = (cappedWidth * scale).toInt()
        }

        val alignedWidth = (cappedWidth + 15) and 15.inv()
        val alignedHeight = (cappedHeight + 15) and 15.inv()

        if (!force && alignedWidth == currentWidth && alignedHeight == currentHeight) {
            Log.d(TAG, "rebuildPipeline skipped: dimensions unchanged ${alignedWidth}x${alignedHeight}")
            return
        }

        if (alignedWidth < 320 || alignedWidth > 3840 || alignedHeight < 320 || alignedHeight > 3840) {
            Log.w(TAG, "rebuildPipeline skipped: dimensions out of range ${alignedWidth}x${alignedHeight}")
            return
        }

        val width = alignedWidth
        val height = alignedHeight
        val dpi = computeVirtualDisplayDpi(width, height)

        val newTargetBitrate = com.castla.mirror.utils.StreamMath.calculateBaseBitrate(width, height)
        targetBitrate = if (isCurrentAppVideo) com.castla.mirror.utils.StreamMath.calculateOttBitrate(newTargetBitrate) else newTargetBitrate
        currentBitrate = targetBitrate

        Log.i(
            TAG,
            "Rebuilding pipeline requested=${newWidth}x${newHeight} -> ${width}x${height} effectiveMaxHeight=$effectiveMaxHeight splitActive=${shouldUseRequestedHeightForSplit()} force=$force"
        )

        try {
            val surface = if (currentCodecMode == "mjpeg") {
                videoEncoder?.release()
                videoEncoder = null
                jpegEncoder?.release()
                jpegEncoder = null

                val jpeg = JpegEncoder(width, height, fps = 15, quality = 65)
                val jpegSurface = jpeg.createInputSurface()
                currentEncoderSurface = jpegSurface
                jpeg.start { frameData, isKeyFrame -> mirrorServer?.broadcastFrame(frameData, isKeyFrame) }
                jpegEncoder = jpeg
                jpegSurface
            } else {
                videoEncoder?.release()
                videoEncoder = null

                val encoder = VideoEncoder(width, height, currentBitrate, thermalFpsOverride ?: currentFps)
                val encoderSurface = encoder.createInputSurface()
                currentEncoderSurface = encoderSurface
                videoEncoder = encoder

                encoder.onSpsPps = { spsPps -> mirrorServer?.broadcastSpsPps(spsPps) }
                encoder.start { frameData, isKeyFrame -> mirrorServer?.broadcastFrame(frameData, isKeyFrame) }
                mirrorServer?.setKeyframeRequester("primary") { encoder.requestKeyFrame() }
                encoderSurface
            }

            touchInjector?.updateDimensions(width, height)

            if (virtualDisplayManager?.isBound() == true) {
                dismissSplitPresentation(clearState = false)
                if (virtualDisplayManager?.hasVirtualDisplay() == true && !force) {
                    // Resize existing VD gradually to avoid activity recreation
                    val vdId = virtualDisplayManager!!.getDisplayId()
                    val resized = try {
                        virtualDisplayManager?.getPrivilegedService()?.setSurface(vdId, surface)
                        virtualDisplayManager?.resizeDisplay(vdId, width, height, dpi) ?: false
                    } catch (e: Exception) {
                        Log.w(TAG, "Resize failed for VD $vdId (stale display?), will recreate", e)
                        false
                    }
                    if (resized) {
                        touchInjector?.setVirtualDisplayInjector { action, x, y, pointerId ->
                            virtualDisplayManager?.injectInput(action, x, y, pointerId)
                        }
                        Log.i(TAG, "Gradually resized primary VD $vdId to ${width}x${height}")
                    } else {
                        Log.w(TAG, "VD $vdId resize failed, falling through to recreate")
                        virtualDisplayManager?.releaseVirtualDisplay()
                        virtualDisplayManager?.createVirtualDisplay(width, height, dpi, surface)
                        if (virtualDisplayManager?.hasVirtualDisplay() == true) {
                            touchInjector?.setVirtualDisplayInjector { action, x, y, pointerId ->
                                virtualDisplayManager?.injectInput(action, x, y, pointerId)
                            }
                            restoreCurrentVdContent()
                        } else {
                            Log.e(TAG, "VD recreation failed after stale resize")
                        }
                    }
                } else {
                    virtualDisplayManager?.releaseVirtualDisplay()
                    virtualDisplayManager?.createVirtualDisplay(width, height, dpi, surface)
                    if (virtualDisplayManager?.hasVirtualDisplay() == true) {
                        touchInjector?.setVirtualDisplayInjector { action, x, y, pointerId ->
                            virtualDisplayManager?.injectInput(action, x, y, pointerId)
                        }
                        restoreCurrentVdContent()
                    } else {
                        // Retry once before considering MediaProjection fallback
                        Log.w(TAG, "VD creation failed during rebuild — retrying once")
                        virtualDisplayManager?.createVirtualDisplay(width, height, dpi, surface)
                        if (virtualDisplayManager?.hasVirtualDisplay() == true) {
                            touchInjector?.setVirtualDisplayInjector { action, x, y, pointerId ->
                                virtualDisplayManager?.injectInput(action, x, y, pointerId)
                            }
                            restoreCurrentVdContent()
                        } else {
                            Log.e(TAG, "VD creation failed after retry — NOT falling back to MediaProjection to prevent raw phone screen leak")
                        }
                    }
                }
            } else if (shizukuSetupInProgress) {
                // A binding attempt is already in flight — don't interrupt it
                Log.i(TAG, "Shizuku binding already in progress, skipping redundant rebind")
            } else {
                // Shizuku not bound — try rebinding before falling back
                Log.w(TAG, "Shizuku not bound during rebuild — attempting rebind")
                trySetupVirtualDisplay(width, height, surface) { success ->
                    if (!success) {
                        Log.e(TAG, "Shizuku rebind failed — NOT falling back to MediaProjection to prevent raw phone screen leak")
                    } else {
                        Log.i(TAG, "Shizuku rebound successfully during rebuild")
                    }
                }
            }

            currentWidth = width
            currentHeight = height

            val msg = JSONObject().apply {
                put("type", "resolutionChanged")
                put("width", width)
                put("height", height)
            }
            mirrorServer?.broadcastControlMessage(msg.toString())

        } catch (e: Exception) {
            Log.e(TAG, "Failed to rebuild pipeline", e)
        } finally {
            screenCapture?.isRebuilding = false
        }
    }

    private fun onCodecModeRequest(mode: String) {
        if (mode != "mjpeg" || jpegEncoder != null) return
        currentCodecMode = "mjpeg"

        try {
            val jpeg = JpegEncoder(currentWidth, currentHeight, fps = 15, quality = 65)
            val surface = jpeg.createInputSurface()
            currentEncoderSurface = surface

            videoEncoder?.release()
            videoEncoder = null

            jpeg.start { frameData, isKeyFrame -> mirrorServer?.broadcastFrame(frameData, isKeyFrame) }
            jpegEncoder = jpeg

            if (virtualDisplayManager?.hasVirtualDisplay() == true) {
                dismissSplitPresentation(clearState = false)
                virtualDisplayManager?.releaseVirtualDisplay()
                virtualDisplayManager?.createVirtualDisplay(currentWidth, currentHeight, 160, surface)
                if (virtualDisplayManager?.hasVirtualDisplay() == true) {
                    touchInjector?.setVirtualDisplayInjector { action, x, y, pointerId ->
                        virtualDisplayManager?.injectInput(action, x, y, pointerId)
                    }
                    restoreCurrentVdContent()
                } else {
                    // Do NOT fall back to MediaProjection here — it would mirror the
                    // raw phone screen (showing the Castla UI) instead of virtual
                    // display content.  Retry VD creation once before giving up.
                    Log.w(TAG, "MJPEG VD recreation failed — retrying once")
                    virtualDisplayManager?.createVirtualDisplay(currentWidth, currentHeight, 160, surface)
                    if (virtualDisplayManager?.hasVirtualDisplay() == true) {
                        touchInjector?.setVirtualDisplayInjector { action, x, y, pointerId ->
                            virtualDisplayManager?.injectInput(action, x, y, pointerId)
                        }
                        restoreCurrentVdContent()
                    } else {
                        Log.e(TAG, "MJPEG VD recreation failed after retry — NOT falling back to MediaProjection")
                    }
                }
            } else if (virtualDisplayManager?.isBound() == true) {
                // Shizuku is bound but no VD yet — create one instead of falling back
                virtualDisplayManager?.createVirtualDisplay(currentWidth, currentHeight, 160, surface)
                if (virtualDisplayManager?.hasVirtualDisplay() == true) {
                    touchInjector?.setVirtualDisplayInjector { action, x, y, pointerId ->
                        virtualDisplayManager?.injectInput(action, x, y, pointerId)
                    }
                    restoreCurrentVdContent()
                } else {
                    Log.e(TAG, "MJPEG VD creation failed — NOT falling back to MediaProjection")
                }
            } else {
                // Shizuku not available at all — MediaProjection is the only option
                screenCapture?.reconfigure(surface, currentWidth, currentHeight)
            }
            if (!singleVdSplit && secondaryWidth > 0 && secondaryHeight > 0) {
                rebuildSecondaryPipeline(secondaryWidth, secondaryHeight)
            }
        } catch (e: Exception) {}
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy called")
        // Thermal listener removal is handled inside performCleanup — do NOT
        // remove it here to avoid "Listener was not added" IllegalArgumentException.
        performCleanup("onDestroy") // no-ops if already completed (guard inside)
        MirrorWidgetProvider.updateAllWidgets(this)
        super.onDestroy()
    }

    /**
     * Grant CAPTURE_AUDIO_OUTPUT permission via Shizuku (ADB-level) so that
     * AudioPlaybackCapture can capture restricted usages like navigation guidance.
     */
    private fun tryGrantAudioCapturePermission() {
        try {
            val setup = shizukuSetup
            val service = setup?.privilegedService
            if (setup != null && service != null && setup.isAvailable() && setup.hasPermission()) {
                val pkg = packageName
                val result = service.execCommand("appops set $pkg CAPTURE_AUDIO_OUTPUT allow")
                Log.i(TAG, "CAPTURE_AUDIO_OUTPUT grant via appops: $result")
                val result2 = service.execCommand("pm grant $pkg android.permission.CAPTURE_AUDIO_OUTPUT")
                Log.i(TAG, "CAPTURE_AUDIO_OUTPUT grant via pm: $result2")
            } else {
                Log.i(TAG, "Skipping audio capture permission grant: privileged service not connected")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to grant CAPTURE_AUDIO_OUTPUT", e)
        }
    }

    // Saved volumes for all streams we mute (stream type -> saved volume)
    private val savedVolumes = mutableMapOf<Int, Int>()

    private fun muteMediaVolume() {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            // Mute all streams that could carry navigation/media/notification audio
            val streamsToMute = intArrayOf(
                AudioManager.STREAM_MUSIC,        // 3 - media, also navigation on most devices
                AudioManager.STREAM_NOTIFICATION,  // 5 - notifications
                AudioManager.STREAM_SYSTEM,        // 1 - system sounds
                AudioManager.STREAM_DTMF,          // 8 - DTMF tones
            )
            for (stream in streamsToMute) {
                try {
                    val current = am.getStreamVolume(stream)
                    if (current > 0) {
                        savedVolumes[stream] = current
                        am.setStreamVolume(stream, 0, 0)
                    }
                } catch (_: Exception) {}
            }
            // Legacy compat
            savedMediaVolume = savedVolumes[AudioManager.STREAM_MUSIC] ?: -1

            // Use Shizuku to force-mute via shell — catches navigation guidance audio
            // that bypasses normal stream volume controls
            try {
                val setup = shizukuSetup
                val service = setup?.privilegedService
                if (setup != null && service != null && setup.isAvailable() && setup.hasPermission()) {
                    for (stream in streamsToMute) {
                        service.execCommand("media volume --show --stream $stream --set 0")
                    }
                    Log.i(TAG, "Shizuku force-muted all audio streams")
                } else {
                    Log.i(TAG, "Skipping Shizuku mute: privileged service not connected")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Shizuku stream mute failed", e)
            }
        } catch (e: Exception) {
            Log.e(TAG, "muteMediaVolume failed", e)
        }
    }

    private fun restoreMediaVolume() {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            for ((stream, volume) in savedVolumes) {
                try {
                    am.setStreamVolume(stream, volume, 0)
                } catch (_: Exception) {}
            }

            // Also restore via Shizuku
            try {
                val setup = shizukuSetup
                val service = setup?.privilegedService
                if (setup != null && service != null && setup.isAvailable() && setup.hasPermission()) {
                    for ((stream, volume) in savedVolumes) {
                        service.execCommand("media volume --show --stream $stream --set $volume")
                    }
                }
            } catch (_: Exception) {}

            savedVolumes.clear()
            savedMediaVolume = -1
        } catch (e: Exception) {
            Log.e(TAG, "restoreMediaVolume failed", e)
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Mirror Service", NotificationManager.IMPORTANCE_LOW
        ).apply { setShowBadge(false) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        val openIntent = Intent(this, com.castla.mirror.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPending = PendingIntent.getActivity(this, 0, openIntent, PendingIntent.FLAG_IMMUTABLE)

        val stopIntent = Intent(ACTION_STOP).apply { setPackage(packageName) }
        val stopPending = PendingIntent.getBroadcast(this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Castla")
            .setContentText("Streaming to Tesla")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setContentIntent(openPending)
            .addAction(android.R.drawable.ic_media_pause, "■ Stop Mirroring", stopPending)
            .build()
    }

    class StopReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            if (intent?.action == ACTION_STOP) {
                val stopIntent = Intent(context, MirrorForegroundService::class.java).apply {
                    action = ACTION_STOP
                }
                context.startService(stopIntent)
            }
        }
    }
}
