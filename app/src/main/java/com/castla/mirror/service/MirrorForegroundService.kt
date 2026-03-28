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
import com.castla.mirror.billing.LicenseManager
import com.castla.mirror.server.MirrorServer
import com.castla.mirror.shizuku.IPrivilegedService
import com.castla.mirror.shizuku.ShizukuSetup
import com.castla.mirror.ui.SplitWebPresentation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
        const val EXTRA_MIRRORING_MODE = "mirroring_mode"
        const val EXTRA_TARGET_PACKAGE = "target_package"

        @Volatile
        var isServiceRunning = false
        
        @JvmStatic
        var instance: MirrorForegroundService? = null
            private set
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
    
    // WakeLocks to keep streaming alive when screen is off
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null
    private var screenOffReceiver: BroadcastReceiver? = null
    private var vdKeepAliveJob: Job? = null

    val isRunning: Boolean
        get() = mirrorServer != null

    fun setBrowserConnectionListener(listener: ((Boolean) -> Unit)?) {
        browserConnectionListener = listener
        mirrorServer?.setBrowserConnectionListener(listener)
    }

    fun setPurchaseRequestListener(listener: (() -> Unit)?) {
        mirrorServer?.setPurchaseRequestListener(listener)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        instance = this
        isServiceRunning = true
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
                        Log.i(TAG, "Screen OFF detected — keeping VD awake")
                        onPhoneScreenOff()
                    }
                    android.content.Intent.ACTION_SCREEN_ON -> {
                        Log.i(TAG, "Screen ON detected — stopping VD keepalive")
                        stopVdKeepAlive()
                    }
                }
            }
        }
        val filter = android.content.IntentFilter().apply {
            addAction(android.content.Intent.ACTION_SCREEN_OFF)
            addAction(android.content.Intent.ACTION_SCREEN_ON)
        }
        registerReceiver(screenOffReceiver, filter)
    }
    
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun handleThermalStatusChange(status: Int) {
        when (status) {
            PowerManager.THERMAL_STATUS_SEVERE,
            PowerManager.THERMAL_STATUS_CRITICAL,
            PowerManager.THERMAL_STATUS_EMERGENCY -> {
                Log.w(TAG, "Thermal status CRITICAL ($status) - Throttling encoder heavily")
                val newBitrate = (currentBitrate * 0.5).toInt().coerceAtLeast(500_000)
                if (currentBitrate > newBitrate) {
                    currentBitrate = newBitrate
                    targetBitrate = newBitrate
                    videoEncoder?.setBitrate(currentBitrate)
                }
                jpegEncoder?.setFps(10)
            }
            PowerManager.THERMAL_STATUS_MODERATE -> {
                Log.w(TAG, "Thermal status MODERATE ($status) - Throttling encoder slightly")
                val newBitrate = (currentBitrate * 0.7).toInt().coerceAtLeast(1_000_000)
                if (currentBitrate > newBitrate) {
                    currentBitrate = newBitrate
                    videoEncoder?.setBitrate(currentBitrate)
                }
                jpegEncoder?.setFps(15)
            }
            PowerManager.THERMAL_STATUS_NONE,
            PowerManager.THERMAL_STATUS_LIGHT -> {
                Log.i(TAG, "Thermal status NORMAL ($status) - Conditions are good")
                jpegEncoder?.setFps(15)
            }
        }
    }

    private fun acquireWakeLocks() {
        try {
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
                acquire()
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
    private var lastBitrateChangeMs = 0L

    private fun observeAppLaunchRequests() {
        serviceScope.launch {
            com.castla.mirror.utils.AppLaunchBus.events.collect { request ->
                val component = if (request.className != null) {
                    "${request.packageName}/${request.className}"
                } else {
                    request.packageName
                }
                Log.i(TAG, "VD launch request: $component (video=${request.isVideoApp})")

                if (request.intentExtra != null) {
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

                // OTT bitrate boost: 1.5x (cap 15Mbps), debounced 500ms
                val now = android.os.SystemClock.elapsedRealtime()
                if (request.isVideoApp != isCurrentAppVideo && now - lastBitrateChangeMs > 500) {
                    isCurrentAppVideo = request.isVideoApp
                    lastBitrateChangeMs = now
                    
                    val baseTargetBitrate = (4_000_000L * currentWidth * currentHeight / (1280L * 720)).toInt().coerceIn(1_000_000, 15_000_000)
                    targetBitrate = if (isCurrentAppVideo) minOf((baseTargetBitrate * 1.5).toInt(), 15_000_000) else baseTargetBitrate
                    
                    if (currentBitrate > targetBitrate || (now - lastCongestionTimeMs > 2000)) {
                         currentBitrate = targetBitrate
                         videoEncoder?.setBitrate(currentBitrate)
                    }
                    Log.i(TAG, "OTT app detected=${isCurrentAppVideo} — target bitrate set to ${targetBitrate / 1000}kbps")
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

        currentMaxHeight = intent!!.getIntExtra(EXTRA_MAX_RESOLUTION, 720)
        val settingsFps = intent.getIntExtra(EXTRA_FPS, 30)
        val audioEnabled = intent.getBooleanExtra(EXTRA_AUDIO, false)
        mirroringMode = intent.getStringExtra(EXTRA_MIRRORING_MODE) ?: "FULL_SCREEN"
        targetPackage = intent.getStringExtra(EXTRA_TARGET_PACKAGE) ?: ""

        startPipeline(resultCode, data, settingsFps, audioEnabled)

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
            cleanupCompleted = true
            mainHandler.post {
                MirrorWidgetProvider.updateAllWidgets(this)
                stopSelf()
            }
        }.start()
    }

    private fun onPhoneScreenOff() {
        // When the physical screen turns off, force the virtual display to stay awake.
        // Without this, Android may put the VD into DOZE state showing a clock screensaver.
        // We wake the VD immediately and then periodically to prevent re-dozing.
        stopVdKeepAlive()
        vdKeepAliveJob = serviceScope.launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(300)
            virtualDisplayManager?.keepDisplayAwake()
            // Periodically re-wake the VD to prevent it from dozing again
            while (coroutineContext[kotlinx.coroutines.Job]?.isActive == true) {
                kotlinx.coroutines.delay(25_000)
                virtualDisplayManager?.keepDisplayAwake()
            }
        }
    }

    private fun stopVdKeepAlive() {
        vdKeepAliveJob?.cancel()
        vdKeepAliveJob = null
    }

    private fun performCleanup(reason: String) {
        Log.i(TAG, "Performing cleanup: $reason")
        isServiceRunning = false
        instance = null
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
        dismissSplitPresentation(clearState = true)
        releaseSecondaryPipeline(clearState = true)
        try { resizeJob?.cancel() } catch (_: Exception) {}
        try { abrJob?.cancel() } catch (_: Exception) {}
        try { serviceScope.cancel() } catch (_: Exception) {}
        try { compositionDispatcher.close() } catch (_: Exception) {}
        try { audioCapture?.stop() } catch (_: Exception) {}
        restoreMediaVolume()

        try { removeAllVdTasks() } catch (e: Exception) { Log.w(TAG, "Failed to remove VD tasks", e) }
        try { virtualDisplayManager?.release() } catch (e: Exception) { Log.w(TAG, "Failed to release virtual display manager", e) }
        try { shizukuSetup?.release() } catch (e: Exception) { Log.w(TAG, "Failed to release shizuku setup", e) }
        try { screenCapture?.release() } catch (e: Exception) { Log.w(TAG, "Failed to release screen capture", e) }
        try { videoEncoder?.release() } catch (e: Exception) { Log.w(TAG, "Failed to release video encoder", e) }
        try { jpegEncoder?.release() } catch (e: Exception) { Log.w(TAG, "Failed to release jpeg encoder", e) }
        try { touchInjector?.release() } catch (e: Exception) { Log.w(TAG, "Failed to release touch injector", e) }
        try { mirrorServer?.stop() } catch (e: Exception) { Log.w(TAG, "Failed to stop mirror server", e) }

        audioCapture = null
        virtualDisplayManager = null
        shizukuSetup = null
        screenCapture = null
        videoEncoder = null
        jpegEncoder = null
        touchInjector = null
        mirrorServer = null
    }

    private fun startAbrLoop() {
        abrJob?.cancel()
        abrJob = serviceScope.launch {
            while (true) {
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

    private fun startPipeline(
        resultCode: Int,
        data: Intent,
        fps: Int,
        audioEnabled: Boolean
    ) {
        try {
            acquireWakeLocks()
            
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
            
            val baseTargetBitrate = (4_000_000L * width * height / (1280L * 720)).toInt().coerceIn(1_000_000, 15_000_000)
            targetBitrate = baseTargetBitrate
            currentBitrate = targetBitrate
            
            startAbrLoop()

            videoEncoder = VideoEncoder(width, height, currentBitrate, fps).also { encoder ->
                val surface = encoder.createInputSurface()
                currentEncoderSurface = surface

                touchInjector = TouchInjector(width, height)

                mirrorServer = MirrorServer(this).also { server ->
                    server.setKeyframeRequester("primary") { encoder.requestKeyFrame() }
                    server.setNetworkCongestionListener { onNetworkCongestion() }
                    server.setTouchListener { event ->
                        if (event.pane == "secondary") {
                            secondaryTouchInjector?.onTouchEvent(event)
                        } else {
                            touchInjector?.onTouchEvent(event)
                            if (event.action == "up") checkImeAndNotifyBrowser()
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
                    server.setGoHomeListener {
                        Log.i(TAG, "Navigating to home requested by Web Launcher")
                        dismissSplitPresentation(clearState = true)
                        if (!singleVdSplit) {
                            releaseSecondaryPipeline(clearState = true)
                        }
                        virtualDisplayManager?.launchHomeOnDisplay()
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

                    server.setBrowserConnectionListener { connected ->
                        if (connected && !browserConnected) {
                            browserConnected = true
                            onBrowserConnected()
                        } else if (!connected && browserConnected) {
                            browserConnected = false
                            onBrowserDisconnected()
                        }
                        browserConnectionListener?.invoke(connected)
                    }

                    server.start(0)
                    Log.i(TAG, "Server started on port ${MirrorServer.DEFAULT_PORT}")
                }

                encoder.onSpsPps = { spsPps -> mirrorServer?.broadcastSpsPps(spsPps) }
                encoder.start { frameData, isKeyFrame ->
                    mirrorServer?.broadcastFrame(frameData, isKeyFrame)
                }

                trySetupVirtualDisplay(width, height, surface) { shizukuActive ->
                    if (shizukuActive) {
                        currentVdApp = "HOME"
                    } else {
                        try {
                            screenCapture?.startCapture(surface, width, height)
                            Log.i(TAG, "Fallback: MediaProjection mirroring at ${width}x${height}")
                        } catch (e: Exception) {
                            Log.e(TAG, "MediaProjection fallback failed", e)
                        }
                    }
                }
            }

            if (audioEnabled && AudioCapture.isSupported()) {
                val projection = screenCapture?.getMediaProjection()
                if (projection != null) {
                    muteMediaVolume()
                    audioCapture = AudioCapture(projection).also { audio ->
                        audio.start { audioData ->
                            mirrorServer?.broadcastAudio(audioData)
                        }
                    }
                    Log.i(TAG, "Audio capture enabled")
                } else {
                    Log.w(TAG, "Audio requested but MediaProjection not available")
                }
            }

            Log.i(TAG, "Pipeline started: ${width}x${height}, audio=${audioEnabled}")
            MirrorWidgetProvider.updateAllWidgets(this)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start pipeline", e)
            stopSelf()
        }
    }
    
    private val OTT_WEB_URLS = mapOf(
        "com.google.android.youtube" to "https://m.youtube.com",
        "com.netflix.mediaclient" to "https://www.netflix.com",
        "com.disney.disneyplus" to "https://www.disneyplus.com",
        "com.disney.disneyplus.kr" to "https://www.disneyplus.com",
        "com.wavve.player" to "https://m.wavve.com",
        "net.cj.cjhv.gs.tving" to "https://www.tving.com",
        "com.coupang.play" to "https://www.coupangplay.com",
        "com.frograms.watcha" to "https://watcha.com"
    )

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
        return (3_000_000L * width * height / (1280L * 720)).toInt().coerceIn(750_000, 10_000_000)
    }

    private fun computeVirtualDisplayDpi(width: Int, height: Int): Int {
        val reference = minOf(width, height)
        return (reference * 240 / 720).coerceIn(120, 320)
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
        if (clearState) {
            clearSecondaryState()
        }
    }

    private fun rebuildSecondaryPipeline(targetWidth: Int, targetHeight: Int) {
        if (targetWidth <= 0 || targetHeight <= 0) return
        val effectiveMaxHeight = effectiveMaxHeightForRequest(targetHeight, isSecondaryPane = true)
        Log.i(
            TAG,
            "Rebuilding secondary pipeline requested=${targetWidth}x${targetHeight} effectiveMaxHeight=$effectiveMaxHeight premium=${LicenseManager.isPremiumNow}"
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
     * Remove all tasks from the virtual display so they don't get
     * reparented to the main display when the VD is released.
     */
    private fun removeAllVdTasks() {
        val service = virtualDisplayManager?.getPrivilegedService() ?: return
        val displayId = virtualDisplayManager?.getDisplayId() ?: -1
        if (displayId < 0) return

        try {
            val dumpsys = service.execCommand("dumpsys activity activities")
            val tasks = parseDisplayTasks(dumpsys, displayId)
            for (task in tasks) {
                service.execCommand("am task remove ${task.taskId}")
                Log.i(TAG, "Removed VD task ${task.taskId} (${task.header.take(80)})")
            }
            Log.i(TAG, "Removed ${tasks.size} tasks from display $displayId")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to remove VD tasks", e)
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
        launchInternalActivity(activityClassName, displayId, url, splitMode = false)
        currentVdApp = internalComponentName(activityClassName)
        currentWebUrl = url
        currentWebSplitMode = false
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
    }

    private fun launchFullscreenStandardTarget(launchTarget: String) {
        clearSplitState()
        val resolvedTarget = normalizeLaunchTarget(launchTarget)
        virtualDisplayManager?.launchAppOnDisplay(resolvedTarget)
        currentVdApp = resolvedTarget
        currentWebUrl = null
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
        val launched = if (primaryTarget.contains("WebBrowserActivity")) {
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
            val webUrl = OTT_WEB_URLS[pkgName]
            if (webUrl != null) {
                val webComponentName = internalComponentName("com.castla.mirror.ui.WebBrowserActivity")
                Log.i(TAG, "Web Launcher: Launching secondary OTT app: $pkgName -> $webUrl")
                launchSecondaryTarget(webComponentName, webUrl)
            } else {
                val launchTarget = componentName ?: pkgName
                Log.i(TAG, "Web Launcher: Launching secondary app: $pkgName (target=$launchTarget)")
                launchSecondaryTarget(launchTarget)
            }
            return
        }

        // In freeform split mode, always launch native apps directly (skip OTT web redirect)
        val webUrl = if (singleVdSplit && splitMode) null else OTT_WEB_URLS[pkgName]
        val displayId = virtualDisplayManager?.getDisplayId() ?: -1

        if (webUrl != null) {
            Log.i(TAG, "Web Launcher: Launching DRM-restricted OTT app: $pkgName -> $webUrl (splitMode=$splitMode)")

            dismissSplitPresentation(clearState = true)
            val webComponentName = "com.castla.mirror.ui.WebBrowserActivity"
            if (splitMode && canLaunchPrimarySplitTask()) {
                launchSplitWebTarget(webComponentName, displayId, webUrl)
            } else {
                launchFullscreenWebTarget(webComponentName, displayId, webUrl)
            }

            isCurrentAppVideo = true
            val baseTargetBitrate = (4_000_000L * currentWidth * currentHeight / (1280L * 720)).toInt().coerceIn(1_000_000, 15_000_000)
            targetBitrate = minOf((baseTargetBitrate * 1.5).toInt(), 15_000_000)
            if (currentBitrate > targetBitrate || (android.os.SystemClock.elapsedRealtime() - lastCongestionTimeMs > 2000)) {
                 currentBitrate = targetBitrate
                 videoEncoder?.setBitrate(currentBitrate)
            }
        } else {
            val launchTarget = componentName ?: pkgName
            Log.i(TAG, "Web Launcher: Launching standard app: $pkgName (target=$launchTarget, splitMode=$splitMode, singleVdSplit=$singleVdSplit)")

            if (splitMode && canLaunchPrimarySplitTask()) {
                launchSplitStandardTarget(launchTarget)
            } else {
                launchFullscreenStandardTarget(launchTarget)
            }

            isCurrentAppVideo = false
            targetBitrate = (4_000_000L * currentWidth * currentHeight / (1280L * 720)).toInt().coerceIn(1_000_000, 15_000_000)
            currentBitrate = minOf(currentBitrate, targetBitrate)
            videoEncoder?.setBitrate(currentBitrate)
        }
        
        if (currentCodecMode == "mjpeg") {
            touchInjector?.onTouchEvent(com.castla.mirror.server.TouchEvent("down", 0.5f, 0.5f, 99))
            kotlinx.coroutines.GlobalScope.launch {
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
        var resultDelivered = false
        val safeResult = { success: Boolean ->
            if (!resultDelivered) {
                resultDelivered = true
                onResult(success)
            }
        }

        try {
            val setup = ShizukuSetup()
            setup.init()

            if (!setup.isAvailable() || !setup.hasPermission()) {
                Log.i(TAG, "Shizuku not available/permitted")
                setup.release()
                safeResult(false)
                return
            }

            shizukuSetup = setup
            val vdm = VirtualDisplayManager()
            virtualDisplayManager = vdm

            vdm.reconnectListener = {
                val surf = currentEncoderSurface
                if (surf != null) {
                    vdm.createVirtualDisplay(currentWidth, currentHeight, 160, surf)
                    if (vdm.hasVirtualDisplay()) {
                        touchInjector?.setVirtualDisplayInjector { action, x, y, pointerId ->
                            vdm.injectInput(action, x, y, pointerId)
                        }
                        restoreCurrentVdContent()
                    }
                }
            }

            vdm.bindShizukuService { bound ->
                try {
                    if (bound) {
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
                        vdm.createVirtualDisplay(width, height, 160, surface)
                        if (vdm.hasVirtualDisplay()) {
                            touchInjector?.setVirtualDisplayInjector { action, x, y, pointerId ->
                                vdm.injectInput(action, x, y, pointerId)
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
        } catch (e: Exception) {
            safeResult(false)
        }
    }

    private fun onBrowserConnected() {
        Log.i(TAG, "Browser connected — resuming pipeline")
        acquireWakeLocks()
        
        serviceScope.launch {
            rebuildPipeline(currentWidth, currentHeight, force = true)
        }
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
                    when {
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
        if (codec != "pcm") return
        serviceScope.launch(Dispatchers.IO) {
            audioCapture?.stop()
            audioCapture = null

            val projection = screenCapture?.getMediaProjection() ?: return@launch
            audioCapture = AudioCapture(projection).also { audio ->
                audio.startPcmOnly { audioData ->
                    mirrorServer?.broadcastAudio(audioData)
                }
            }
        }
    }

    private fun onBrowserDisconnected() {
        Log.i(TAG, "Browser disconnected — suspending pipeline")
        dismissSplitPresentation(clearState = false)
        if (!singleVdSplit) {
            releaseSecondaryPipeline(clearState = false)
        }
        try { removeAllVdTasks() } catch (e: Exception) { Log.w(TAG, "Failed to remove VD tasks on disconnect", e) }
        virtualDisplayManager?.releaseVirtualDisplay()
        
        screenCapture?.stopCapture()
        videoEncoder?.release()
        videoEncoder = null
        jpegEncoder?.release()
        jpegEncoder = null
        currentEncoderSurface = null
        
        releaseWakeLocks()
        
        browserConnected = false
    }

    private fun injectText(text: String) {
        serviceScope.launch(compositionDispatcher) { 
            try {
                val displayId = virtualDisplayManager?.getDisplayId() ?: -1
                val service = shizukuSetup?.privilegedService
                if (service != null) {
                    service.injectText(text, displayId)
                }
            } catch (e: Exception) {}
        }
    }

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
                kotlinx.coroutines.delay(300)
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
                }
            } catch (e: Exception) {
                imeCheckSuspendUntil = System.currentTimeMillis() + 10_000
            }
        }
    }

    private val compositionDispatcher = kotlinx.coroutines.newSingleThreadContext("composition")

    private fun injectCompositionUpdate(backspaces: Int, text: String) {
        serviceScope.launch(compositionDispatcher) {
            try {
                val displayId = virtualDisplayManager?.getDisplayId() ?: -1
                shizukuSetup?.privilegedService?.injectComposingText(backspaces, text, displayId)
            } catch (e: Exception) {}
        }
    }

    private fun injectKeyEvent(keyCode: Int) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val displayId = virtualDisplayManager?.getDisplayId() ?: -1
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
        return when {
            shouldUseRequestedHeightForSplit(isSecondaryPane) -> requestedHeight
            LicenseManager.isPremiumNow -> currentMaxHeight
            else -> 720
        }
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

        if (!force && alignedWidth == currentWidth && alignedHeight == currentHeight) return

        if (alignedWidth < 320 || alignedWidth > 3840 || alignedHeight < 320 || alignedHeight > 3840) return

        val width = alignedWidth
        val height = alignedHeight
        val dpi = computeVirtualDisplayDpi(width, height)

        val newTargetBitrate = (4_000_000L * width * height / (1280L * 720)).toInt().coerceIn(1_000_000, 15_000_000)
        targetBitrate = if (isCurrentAppVideo) minOf((newTargetBitrate * 1.5).toInt(), 15_000_000) else newTargetBitrate
        currentBitrate = targetBitrate

        Log.i(
            TAG,
            "Rebuilding pipeline requested=${newWidth}x${newHeight} -> ${width}x${height} effectiveMaxHeight=$effectiveMaxHeight premium=${LicenseManager.isPremiumNow} splitActive=${shouldUseRequestedHeightForSplit()} force=$force"
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

                val encoder = VideoEncoder(width, height, currentBitrate, currentFps)
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
                    virtualDisplayManager?.getPrivilegedService()?.setSurface(vdId, surface)
                    virtualDisplayManager?.resizeDisplay(vdId, width, height, dpi)
                    touchInjector?.setVirtualDisplayInjector { action, x, y, pointerId ->
                        virtualDisplayManager?.injectInput(action, x, y, pointerId)
                    }
                    Log.i(TAG, "Gradually resized primary VD $vdId to ${width}x${height}")
                } else {
                    virtualDisplayManager?.releaseVirtualDisplay()
                    virtualDisplayManager?.createVirtualDisplay(width, height, dpi, surface)
                    if (virtualDisplayManager?.hasVirtualDisplay() == true) {
                        touchInjector?.setVirtualDisplayInjector { action, x, y, pointerId ->
                            virtualDisplayManager?.injectInput(action, x, y, pointerId)
                        }
                        restoreCurrentVdContent()
                    } else {
                        screenCapture?.reconfigure(surface, width, height)
                    }
                }
            } else {
                screenCapture?.reconfigure(surface, width, height)
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
                    screenCapture?.reconfigure(surface, currentWidth, currentHeight)
                }
            } else {
                screenCapture?.reconfigure(surface, currentWidth, currentHeight)
            }
            if (!singleVdSplit && secondaryWidth > 0 && secondaryHeight > 0) {
                rebuildSecondaryPipeline(secondaryWidth, secondaryHeight)
            }
        } catch (e: Exception) {}
    }

    override fun onDestroy() {
        Log.i(TAG, "Stopping pipeline")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            thermalListener?.let { pm.removeThermalStatusListener(it) }
        }
        if (!cleanupCompleted) {
            performCleanup("onDestroy")
            cleanupCompleted = true
        }
        MirrorWidgetProvider.updateAllWidgets(this)
        super.onDestroy()
    }

    private fun muteMediaVolume() {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            savedMediaVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
        } catch (e: Exception) {}
    }

    private fun restoreMediaVolume() {
        if (savedMediaVolume < 0) return
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.setStreamVolume(AudioManager.STREAM_MUSIC, savedMediaVolume, 0)
            savedMediaVolume = -1
        } catch (e: Exception) {}
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
