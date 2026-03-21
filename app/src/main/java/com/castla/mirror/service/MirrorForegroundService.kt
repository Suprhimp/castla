package com.castla.mirror.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Binder
import android.os.IBinder
import android.util.Log
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
import com.castla.mirror.shizuku.ShizukuSetup
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
        const val EXTRA_WIDTH = "width"
        const val EXTRA_HEIGHT = "height"
        const val EXTRA_BITRATE = "bitrate"
        const val EXTRA_FPS = "fps"
        const val EXTRA_AUDIO = "audio_enabled"
        const val EXTRA_MIRRORING_MODE = "mirroring_mode"
        const val EXTRA_TARGET_PACKAGE = "target_package"
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
    private var mirroringMode: String = "FULL_SCREEN"
    private var targetPackage: String = ""
    private var browserConnectionListener: ((Boolean) -> Unit)? = null
    private var serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var resizeJob: Job? = null
    private var browserConnected = false
    private var currentVdApp: String = "com.android.settings" // what's running on VD
    private var currentCodecMode: String = "h264"
    private var savedMediaVolume: Int = -1

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
        createNotificationChannel()
        observeAppLaunchRequests()
    }

    /** Track whether current app is OTT for bitrate adjustment */
    private var isCurrentAppVideo = false
    private var lastBitrateChangeMs = 0L

    /** Collect app launch requests from DesktopActivity via in-process SharedFlow */
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
                    virtualDisplayManager?.launchAppWithExtraOnDisplay(component, "url", request.intentExtra)
                } else {
                    virtualDisplayManager?.launchAppOnDisplay(component)
                }

                // OTT bitrate boost: 1.5x (cap 6Mbps), debounced 500ms
                val now = android.os.SystemClock.elapsedRealtime()
                if (request.isVideoApp != isCurrentAppVideo && now - lastBitrateChangeMs > 500) {
                    isCurrentAppVideo = request.isVideoApp
                    lastBitrateChangeMs = now
                    if (request.isVideoApp) {
                        val boosted = minOf((currentBitrate * 1.5).toInt(), 6_000_000)
                        videoEncoder?.setBitrate(boosted)
                        Log.i(TAG, "OTT app detected — bitrate boosted to ${boosted / 1000}kbps")
                    } else {
                        videoEncoder?.setBitrate(currentBitrate)
                        Log.i(TAG, "Non-OTT app — bitrate restored to ${currentBitrate / 1000}kbps")
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Must call startForeground() immediately — before any validation or work.
        // Android kills the process if startForegroundService() was called but
        // startForeground() is not invoked within ~5 seconds.
        val notification = createNotification()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )

        // Activity.RESULT_OK == -1, so use MIN_VALUE as sentinel for "missing"
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

        val settingsWidth = intent!!.getIntExtra(EXTRA_WIDTH, 0)
        val settingsHeight = intent.getIntExtra(EXTRA_HEIGHT, 0)
        val settingsBitrate = intent.getIntExtra(EXTRA_BITRATE, 4_000_000)
        val settingsFps = intent.getIntExtra(EXTRA_FPS, 30)
        val audioEnabled = intent.getBooleanExtra(EXTRA_AUDIO, false)
        mirroringMode = intent.getStringExtra(EXTRA_MIRRORING_MODE) ?: "FULL_SCREEN"
        targetPackage = intent.getStringExtra(EXTRA_TARGET_PACKAGE) ?: ""

        startPipeline(resultCode, data, settingsWidth, settingsHeight, settingsBitrate, settingsFps, audioEnabled)

        return START_NOT_STICKY
    }

    private fun startPipeline(
        resultCode: Int,
        data: Intent,
        settingsWidth: Int,
        settingsHeight: Int,
        bitrate: Int,
        fps: Int,
        audioEnabled: Boolean
    ) {
        try {
            // 1. Initialize screen capture (always needed — provides resolution + fallback)
            screenCapture = ScreenCaptureManager(this).also {
                it.initProjection(resultCode, data)
            }

            // Use settings resolution if provided, otherwise fall back to screen capture resolution
            val width = if (settingsWidth > 0) settingsWidth else screenCapture!!.captureWidth
            val height = if (settingsHeight > 0) settingsHeight else screenCapture!!.captureHeight
            currentWidth = width
            currentHeight = height
            currentBitrate = bitrate
            currentFps = fps

            // 2. Initialize video encoder with user settings
            videoEncoder = VideoEncoder(width, height, bitrate, fps).also { encoder ->
                val surface = encoder.createInputSurface()
                currentEncoderSurface = surface

                // 3. Initialize touch injector
                touchInjector = TouchInjector(width, height)

                // 4. Start the web server
                mirrorServer = MirrorServer(this).also { server ->
                    server.setKeyframeRequester { encoder.requestKeyFrame() }
                    server.setTouchListener { event ->
                        touchInjector?.onTouchEvent(event)
                        // Check IME state after tap (ACTION_UP)
                        if (event.action == "up") checkImeAndNotifyBrowser()
                    }
                    server.setCodecModeListener { mode -> onCodecModeRequest(mode) }
                    server.setViewportChangeListener { w, h -> onViewportChange(w, h) }
                    server.setTextInputListener { text -> injectText(text) }
                    server.setKeyEventListener { keyCode -> injectKeyEvent(keyCode) }
                    server.setCompositionUpdateListener { bs, text -> injectCompositionUpdate(bs, text) }
                    server.setAudioCodecListener { codec -> onAudioCodecRequest(codec) }
                    server.setGoHomeListener {
                        Log.i(TAG, "Navigating to home (DesktopActivity) requested by Web Launcher")
                        // Web Launcher 기반에서는 홈 화면이 웹(테슬라 브라우저)이므로 
                        // 안드로이드 쪽에서는 아무 앱도 안 띄우거나 그냥 대기하면 됨.
                        // 이전에 열어둔 앱들을 죽여서 자원을 정리
                        virtualDisplayManager?.launchHomeOnDisplay() 
                        currentVdApp = "HOME"
                    }
                    server.setAppLaunchListener { pkgName, componentName ->
                        // 웹 런처에서 앱 실행 요청이 들어오면 기존 DesktopActivity 내장 로직과 
                        // 동일하게 OTT는 WebBrowserActivity로, 나머지는 일반 앱으로 실행
                        launchAppFromWebLauncher(pkgName, componentName)
                    }

                    // Internal listener: when browser connects, launch target app on VD
                    server.setBrowserConnectionListener { connected ->
                        if (connected && !browserConnected) {
                            browserConnected = true
                            onBrowserConnected()
                        } else if (!connected && browserConnected) {
                            browserConnected = false
                            onBrowserDisconnected()
                        }
                        // Forward to external listener (UI)
                        browserConnectionListener?.invoke(connected)
                    }

                    server.start(0) // no read timeout — WebSockets stay open indefinitely
                    Log.i(TAG, "Server started on port ${MirrorServer.DEFAULT_PORT}")
                }

                // 5. Start encoding — frames go to server
                encoder.onSpsPps = { spsPps -> mirrorServer?.broadcastSpsPps(spsPps) }
                encoder.start { frameData, isKeyFrame ->
                    mirrorServer?.broadcastFrame(frameData, isKeyFrame)
                }

                // 6. Try Shizuku virtual display for viewport matching + touch injection.
                // Both modes use VD when Shizuku is available.
                // APP mode: launches selected app on VD.
                // FULL_SCREEN mode: VD shows home/launcher (no specific app).
                // If Shizuku unavailable: fall back to MediaProjection (FULL_SCREEN only).
                trySetupVirtualDisplay(width, height, surface) { shizukuActive ->
                    if (shizukuActive) {
                        // Web Launcher 방식: VD는 검정 화면으로 시작,
                        // 브라우저에서 앱 선택 시 launchApp으로 직접 실행
                        currentVdApp = "HOME"
                    } else {
                        // Shizuku unavailable: fall back to MediaProjection screen mirror
                        try {
                            screenCapture?.startCapture(surface, width, height)
                            Log.i(TAG, "Fallback: MediaProjection mirroring at ${width}x${height}")
                        } catch (e: Exception) {
                            Log.e(TAG, "MediaProjection fallback failed", e)
                        }
                    }
                }
            }

            // 7. Start audio capture if enabled (Android 10+ only)
            if (audioEnabled && AudioCapture.isSupported()) {
                val projection = screenCapture?.getMediaProjection()
                if (projection != null) {
                    // Mute device speaker — AudioPlaybackCapture still receives audio
                    // at the mixer level, so capture is unaffected
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
            // Update widget to reflect streaming state
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

    private fun launchAppFromWebLauncher(pkgName: String, componentName: String? = null) {
        val webUrl = OTT_WEB_URLS[pkgName]
        if (webUrl != null) {
            Log.i(TAG, "Web Launcher: Launching DRM-restricted OTT app via WebBrowserActivity: $pkgName -> $webUrl")
            val webComponentName = "com.castla.mirror/com.castla.mirror.ui.WebBrowserActivity"
            
            // WebBrowserActivity를 가상 디스플레이에 띄웁니다.
            virtualDisplayManager?.launchAppWithExtraOnDisplay(webComponentName, "url", webUrl)
            
            // Boost bitrate
            val boosted = minOf((currentBitrate * 1.5).toInt(), 6_000_000)
            videoEncoder?.setBitrate(boosted)
            isCurrentAppVideo = true
            currentVdApp = webComponentName
        } else {
            val launchTarget = componentName ?: pkgName
            Log.i(TAG, "Web Launcher: Launching standard app: $pkgName (target=$launchTarget)")
            
            // 웹 런처는 componentName 을 알고 있으므로 가능한 한 명시적 컴포넌트로 실행한다.
            // 패키지명-only launch 는 일부 셸/펌웨어에서 Intent resolve 실패가 날 수 있다.
            virtualDisplayManager?.launchAppOnDisplay(launchTarget)
            
            // Restore bitrate
            videoEncoder?.setBitrate(currentBitrate)
            isCurrentAppVideo = false
            currentVdApp = launchTarget
        }
        
        // 중요: MJPEG 모드일 때 화면 갱신을 위해 프레임 전송을 자극
        if (currentCodecMode == "mjpeg") {
            Log.i(TAG, "Web Launcher: Triggering screen capture for MJPEG mode")
            // 가상 디스플레이에 입력 이벤트를 주거나, 화면을 강제로 한 번 갱신하도록 하면
            // 멈춰있던 JpegEncoder가 화면 변화를 감지하고 스트리밍을 재개합니다.
            // 안드로이드 14+ 에서는 화면 변화가 없으면 ImageReader가 프레임을 주지 않습니다.
            touchInjector?.onTouchEvent(com.castla.mirror.server.TouchEvent("down", 0.5f, 0.5f, 99))
            kotlinx.coroutines.GlobalScope.launch {
                kotlinx.coroutines.delay(50)
                touchInjector?.onTouchEvent(com.castla.mirror.server.TouchEvent("up", 0.5f, 0.5f, 99))
            }
        }
    }

    /**
     * Attempt to set up a Shizuku-powered virtual display.
     *
     * If Shizuku is available: creates a virtual display with the encoder's surface,
     * so VD content renders directly into the encoder. Touch events are routed to the VD.
     * The phone screen operates independently.
     *
     * If Shizuku is unavailable: calls onResult(false) so the caller can fall back
     * to MediaProjection screen mirroring.
     */
    private fun trySetupVirtualDisplay(
        width: Int,
        height: Int,
        surface: android.view.Surface,
        onResult: (Boolean) -> Unit
    ) {
        // Guard: ensure onResult is called exactly once (callback may fire multiple times)
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

            // Handle Shizuku service reconnection after death
            vdm.reconnectListener = {
                val surf = currentEncoderSurface
                if (surf != null) {
                    Log.i(TAG, "Shizuku reconnected — recreating VD and restoring current content")
                    vdm.createVirtualDisplay(currentWidth, currentHeight, 160, surf)
                    if (vdm.hasVirtualDisplay()) {
                        touchInjector?.setVirtualDisplayInjector { action, x, y, pointerId ->
                            vdm.injectInput(action, x, y, pointerId)
                        }
                        restoreCurrentVdContent()
                    }
                } else {
                    Log.i(TAG, "Shizuku reconnected but no surface is available yet, skipping")
                }
            }

            vdm.bindShizukuService { bound ->
                try {
                    if (bound) {
                        vdm.createVirtualDisplay(width, height, 160, surface)
                        if (vdm.hasVirtualDisplay()) {
                            Log.i(TAG, "Virtual display active — phone operates independently")
                            touchInjector?.setVirtualDisplayInjector { action, x, y, pointerId ->
                                vdm.injectInput(action, x, y, pointerId)
                            }
                            safeResult(true)
                        } else {
                            Log.w(TAG, "VD creation failed, falling back")
                            safeResult(false)
                        }
                    } else {
                        Log.w(TAG, "Shizuku service bind failed")
                        safeResult(false)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Shizuku VD callback failed", e)
                    safeResult(false)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Shizuku setup failed", e)
            safeResult(false)
        }
    }

    /**
     * Called when the first browser client connects.
     * Switches VD from placeholder (Settings) to the actual target app.
     */
    private fun onBrowserConnected() {
        Log.i(TAG, "Browser connected — mode=$mirroringMode, currentVdApp=$currentVdApp")
        // No action needed — apps are launched when VD is created
    }

    private fun restoreCurrentVdContent() {
        val vdm = virtualDisplayManager ?: return
        when (currentVdApp) {
            "HOME", "", "com.android.settings" -> {
                // VD 재생성 후 빈 화면 유지 — 브라우저에서 앱 선택 시 실행됨
                currentVdApp = "HOME"
            }
            else -> {
                vdm.launchAppOnDisplay(currentVdApp)
            }
        }
    }

    /**
     * Client requested a different audio codec (e.g. "pcm" when Opus is unsupported).
     * Restart AudioCapture in the requested mode.
     */
    private fun onAudioCodecRequest(codec: String) {
        if (codec != "pcm") return
        serviceScope.launch(Dispatchers.IO) {
            Log.i(TAG, "Switching audio to PCM (client doesn't support Opus)")
            audioCapture?.stop()
            audioCapture = null

            val projection = screenCapture?.getMediaProjection() ?: return@launch
            audioCapture = AudioCapture(projection).also { audio ->
                audio.startPcmOnly { audioData ->
                    mirrorServer?.broadcastAudio(audioData)
                }
            }
            Log.i(TAG, "Audio restarted in PCM mode")
        }
    }

    private fun onBrowserDisconnected() {
        Log.i(TAG, "Browser disconnected — releasing VD to free resources")
        virtualDisplayManager?.releaseVirtualDisplay()
        browserConnected = false
    }

    /**
     * Inject text into the currently focused field via Shizuku (uid 2000).
     * Supports Korean/CJK/emoji via ACTION_MULTIPLE, clipboard+paste fallback.
     */
    private fun injectText(text: String) {
        serviceScope.launch(compositionDispatcher) { // same dispatcher as composition to prevent interleaving
            try {
                val displayId = virtualDisplayManager?.getDisplayId() ?: -1
                val service = shizukuSetup?.privilegedService
                if (service != null) {
                    service.injectText(text, displayId)
                } else {
                    Log.w(TAG, "Text injection failed: Shizuku not available")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Text injection failed", e)
            }
        }
    }

    private var lastImeState = false
    private var lastImeCheckTime = 0L
    private var imeCheckSuspendUntil = 0L

    private fun parseImeVisible(dumpsys: String): Boolean {
        if (dumpsys.contains("mInputShown=true")) return true

        val imeWindowVis = Regex("""mImeWindowVis=(\d+)""")
            .find(dumpsys)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
        if (imeWindowVis != null && imeWindowVis != 0) return true

        val decorVisible = dumpsys.contains("mDecorViewVisible=true")
        val windowVisible = dumpsys.contains("mWindowVisible=true")
        if (decorVisible && windowVisible) return true

        return false
    }

    /**
     * Check if an editable field is focused on a specific display by
     * looking for InputMethod windows in `dumpsys window`.
     */
    private fun parseImeVisibleForDisplay(windowDump: String, displayId: Int): Boolean {
        // Look for the display section first
        val displayTag = "Display: $displayId"
        val displayIdx = windowDump.indexOf(displayTag)
        // If display section exists, only search within it
        val searchArea = if (displayIdx >= 0) {
            val nextDisplay = windowDump.indexOf("Display: ", displayIdx + displayTag.length)
            if (nextDisplay >= 0) windowDump.substring(displayIdx, nextDisplay)
            else windowDump.substring(displayIdx)
        } else {
            windowDump
        }

        // Check for InputMethod window on this display
        if (searchArea.contains("InputMethod") && searchArea.contains("isVisible=true")) return true
        // Some Android versions use different format
        if (searchArea.contains("InputMethod") && searchArea.contains("mHasSurface=true")) return true

        return false
    }

    /**
     * Check if a focused view is editable by looking for ServedInputConnection
     * in the InputMethodManager state. This works even on virtual displays where
     * the IME window may not show up in the traditional mInputShown check.
     */
    private fun parseHasServedInput(dumpsys: String): Boolean {
        // mServedView is non-null when an editable field has focus
        if (dumpsys.contains("mServedView=") && !dumpsys.contains("mServedView=null")) return true
        // Active client indicates an InputConnection is live
        if (dumpsys.contains("mCurClient=") && !dumpsys.contains("mCurClient=null")) {
            if (dumpsys.contains("mInputShown=true") || dumpsys.contains("mShowRequested=true")) return true
        }
        return false
    }

    /**
     * Check if Android IME (keyboard) is visible and notify browser.
     * Called after touch UP events with a small delay.
     * Uses multiple detection strategies for virtual display compatibility.
     */
    private fun checkImeAndNotifyBrowser() {
        val now = System.currentTimeMillis()
        // Debounce: skip if checked within last 500ms
        if (now - lastImeCheckTime < 500) return
        // Suspend after failure to avoid crash loop
        if (now < imeCheckSuspendUntil) return
        lastImeCheckTime = now

        serviceScope.launch(Dispatchers.IO) {
            try {
                kotlinx.coroutines.delay(300) // wait for IME animation
                val displayId = virtualDisplayManager?.getDisplayId() ?: -1
                // Use VDM's own privileged service to avoid ShizukuSetup binder conflict
                val service = virtualDisplayManager?.getPrivilegedService()
                if (service == null) {
                    Log.d(TAG, "IME check: VDM service unavailable")
                    return@launch
                }

                // Use grep to minimize binder data transfer
                val result = try {
                    service.execCommand(
                        "dumpsys input_method | grep -E 'mInputShown|mImeWindowVis|mDecorViewVisible|mWindowVisible|mServedView|mShowRequested|mCurClient'"
                    )
                } catch (e: android.os.DeadObjectException) {
                    Log.w(TAG, "IME check: binder dead, suspending for 10s")
                    imeCheckSuspendUntil = System.currentTimeMillis() + 10_000
                    return@launch
                }
                if (result == null) return@launch

                var imeVisible = parseImeVisible(result)

                // Fallback: check if input is being served (editable focused on VD)
                if (!imeVisible && displayId > 0) {
                    imeVisible = parseHasServedInput(result)
                }

                if (imeVisible != lastImeState) {
                    lastImeState = imeVisible
                    val msg = if (imeVisible) {
                        """{"type":"showKeyboard"}"""
                    } else {
                        """{"type":"hideKeyboard"}"""
                    }
                    mirrorServer?.broadcastControlMessage(msg)
                    Log.i(TAG, "IME state changed: visible=$imeVisible (display=$displayId)")
                }
            } catch (e: Exception) {
                Log.w(TAG, "IME check failed", e)
                imeCheckSuspendUntil = System.currentTimeMillis() + 10_000
            }
        }
    }

    private val compositionDispatcher = kotlinx.coroutines.newSingleThreadContext("composition")

    private fun injectCompositionUpdate(backspaces: Int, text: String) {
        serviceScope.launch(compositionDispatcher) {
            try {
                val displayId = virtualDisplayManager?.getDisplayId() ?: -1
                val service = shizukuSetup?.privilegedService ?: return@launch

                // Use Shizuku's fast ACTION_MULTIPLE injection (no clipboard)
                service.injectComposingText(backspaces, text, displayId)
            } catch (e: Exception) {
                Log.e(TAG, "Composition update failed", e)
            }
        }
    }

    private fun injectKeyEvent(keyCode: Int) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val displayId = virtualDisplayManager?.getDisplayId() ?: -1
                val service = shizukuSetup?.privilegedService
                if (service != null) {
                    val cmd = if (displayId > 0) "input -d $displayId keyevent $keyCode"
                              else "input keyevent $keyCode"
                    service.execCommand(cmd)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Key event injection failed", e)
            }
        }
    }

    /**
     * Called when the Tesla browser reports its viewport dimensions.
     * Debounces by cancelling any previous resize job.
     */
    private fun onViewportChange(width: Int, height: Int) {
        resizeJob?.cancel()
        resizeJob = serviceScope.launch {
            if (currentCodecMode == "mjpeg" && !LicenseManager.isPremiumNow) {
                // Free tier MJPEG keeps a fixed capture size and uses client-side contain layout.
                return@launch
            }
            rebuildPipeline(width, height)
        }
    }

    /**
     * Rebuild the capture/encode pipeline with new dimensions to match
     * the Tesla browser viewport.
     */
    private suspend fun rebuildPipeline(newWidth: Int, newHeight: Int) {
        // Server-side resolution cap for free users (max 720p)
        var cappedWidth = newWidth
        var cappedHeight = newHeight
        if (!LicenseManager.isPremiumNow) {
            if (cappedWidth > 1280) cappedWidth = 1280
            if (cappedHeight > 720) cappedHeight = 720
        }

        // Guard: same size — no-op
        if (cappedWidth == currentWidth && cappedHeight == currentHeight) return

        // Range check
        if (cappedWidth < 320 || cappedWidth > 3840 || cappedHeight < 320 || cappedHeight > 3840) {
            Log.w(TAG, "Viewport out of range: ${cappedWidth}x${cappedHeight}, ignoring")
            return
        }

        // Align to 16-multiple (required by H.264 encoders)
        val width = (cappedWidth + 15) and 15.inv()
        val height = (cappedHeight + 15) and 15.inv()

        // Compute DPI proportional to height
        val dpi = (height * 240 / 720).coerceIn(120, 320)

        // Dynamic bitrate: scale proportionally to pixel count relative to 720p base
        val newBitrate = (currentBitrate.toLong() * width * height / (1280L * 720)).toInt()
            .coerceIn(1_000_000, 20_000_000)

        Log.i(TAG, "Rebuilding pipeline: ${currentWidth}x${currentHeight} -> ${width}x${height}, " +
            "bitrate=${newBitrate / 1000}kbps, dpi=$dpi")

        try {
            val surface = if (currentCodecMode == "mjpeg") {
                videoEncoder?.release()
                videoEncoder = null
                jpegEncoder?.release()
                jpegEncoder = null

                val jpeg = JpegEncoder(width, height, fps = 15, quality = 65)
                val jpegSurface = jpeg.createInputSurface()
                currentEncoderSurface = jpegSurface
                jpeg.start { frameData, isKeyFrame ->
                    mirrorServer?.broadcastFrame(frameData, isKeyFrame)
                }
                jpegEncoder = jpeg
                jpegSurface
            } else {
                // 1. Release old video encoder
                videoEncoder?.release()
                videoEncoder = null

                // 2. Create new VideoEncoder at new dimensions
                val encoder = VideoEncoder(width, height, newBitrate, currentFps)
                val encoderSurface = encoder.createInputSurface()
                currentEncoderSurface = encoderSurface
                videoEncoder = encoder

                // 4. Start encoder with broadcast callback
                encoder.onSpsPps = { spsPps -> mirrorServer?.broadcastSpsPps(spsPps) }
                encoder.start { frameData, isKeyFrame ->
                    mirrorServer?.broadcastFrame(frameData, isKeyFrame)
                }

                // 5. Wire up keyframe requester to new encoder
                mirrorServer?.setKeyframeRequester { encoder.requestKeyFrame() }
                encoderSurface
            }

            // 3. Update touch injector dimensions
            touchInjector?.updateDimensions(width, height)

            // 6. Reconnect capture to new encoder surface
            if (virtualDisplayManager?.isBound() == true) {
                // Shizuku path: release old VD, create new one
                virtualDisplayManager?.releaseVirtualDisplay()
                virtualDisplayManager?.createVirtualDisplay(width, height, dpi, surface)
                if (virtualDisplayManager?.hasVirtualDisplay() == true) {
                    touchInjector?.setVirtualDisplayInjector { action, x, y, pointerId ->
                        virtualDisplayManager?.injectInput(action, x, y, pointerId)
                    }
                    restoreCurrentVdContent()
                    Log.i(TAG, "Virtual display recreated at ${width}x${height}")
                } else {
                    screenCapture?.reconfigure(surface, width, height)
                    Log.i(TAG, "VD recreate failed, using MediaProjection at ${width}x${height}")
                }
            } else {
                // MediaProjection path: resize + setSurface (no release needed)
                screenCapture?.reconfigure(surface, width, height)
                Log.i(TAG, "MediaProjection reconfigured at ${width}x${height}")
            }

            // 8. Update state
            currentWidth = width
            currentHeight = height
            currentBitrate = newBitrate

            // 9. Notify clients that resolution changed
            val msg = JSONObject().apply {
                put("type", "resolutionChanged")
                put("width", width)
                put("height", height)
            }
            mirrorServer?.broadcastControlMessage(msg.toString())

            Log.i(TAG, "Pipeline rebuilt: ${width}x${height}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to rebuild pipeline", e)
        } finally {
            screenCapture?.isRebuilding = false
        }
    }

    /**
     * Switch encoding mode when client requests MJPEG (no WebCodecs available).
     * Creates a JpegEncoder, captures screen to it, stops the H.264 encoder.
     */
    private fun onCodecModeRequest(mode: String) {
        if (mode != "mjpeg" || jpegEncoder != null) return
        currentCodecMode = "mjpeg"

        Log.i(TAG, "Switching to MJPEG mode")
        try {
            // Create JPEG encoder with lower FPS to save bandwidth
            val jpeg = JpegEncoder(currentWidth, currentHeight, fps = 15, quality = 65)
            val surface = jpeg.createInputSurface()
            currentEncoderSurface = surface

            // Stop H.264 encoder
            videoEncoder?.release()
            videoEncoder = null

            // Start JPEG encoding — reuse broadcastFrame with isKeyFrame=true
            jpeg.start { frameData, isKeyFrame ->
                mirrorServer?.broadcastFrame(frameData, isKeyFrame)
            }
            jpegEncoder = jpeg

            // Re-capture screen to the new JPEG surface
            // Try Shizuku VD first, then MediaProjection fallback
            if (virtualDisplayManager?.hasVirtualDisplay() == true) {
                // Recreate the VD instead of only swapping surfaces. This survives the case where
                // the Shizuku service was restarted and lost its internal display map.
                Log.i(TAG, "VD active, recreating virtual display for MJPEG surface")
                virtualDisplayManager?.releaseVirtualDisplay()
                virtualDisplayManager?.createVirtualDisplay(currentWidth, currentHeight, 160, surface)
                if (virtualDisplayManager?.hasVirtualDisplay() == true) {
                    touchInjector?.setVirtualDisplayInjector { action, x, y, pointerId ->
                        virtualDisplayManager?.injectInput(action, x, y, pointerId)
                    }
                    restoreCurrentVdContent()
                } else {
                    Log.w(TAG, "MJPEG VD recreate failed, falling back to MediaProjection")
                    try {
                        screenCapture?.startCapture(surface, currentWidth, currentHeight)
                        Log.i(TAG, "MJPEG capture started via MediaProjection at ${currentWidth}x${currentHeight}")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to start MJPEG capture", e)
                    }
                }
            } else {
                // Use MediaProjection to capture to JPEG surface
                try {
                    screenCapture?.startCapture(surface, currentWidth, currentHeight)
                    Log.i(TAG, "MJPEG capture started via MediaProjection at ${currentWidth}x${currentHeight}")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start MJPEG capture", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to switch to MJPEG", e)
        }
    }

    override fun onDestroy() {
        Log.i(TAG, "Stopping pipeline")
        resizeJob?.cancel()
        serviceScope.cancel()
        compositionDispatcher.close()
        audioCapture?.stop()
        restoreMediaVolume()
        virtualDisplayManager?.release()
        shizukuSetup?.release()
        screenCapture?.release()
        videoEncoder?.release()
        jpegEncoder?.release()
        touchInjector?.release()
        mirrorServer?.stop()
        audioCapture = null
        virtualDisplayManager = null
        shizukuSetup = null
        screenCapture = null
        videoEncoder = null
        jpegEncoder = null
        touchInjector = null
        mirrorServer = null
        // Update widget to reflect stopped state
        MirrorWidgetProvider.updateAllWidgets(this)
        super.onDestroy()
    }

    /**
     * Mute device media volume so audio only plays through the browser.
     * AudioPlaybackCapture taps audio at the mixer level before volume is applied,
     * so captured data is unaffected by the device volume being zero.
     */
    private fun muteMediaVolume() {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            savedMediaVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
            Log.i(TAG, "Media volume muted (saved=$savedMediaVolume)")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to mute media volume", e)
        }
    }

    private fun restoreMediaVolume() {
        if (savedMediaVolume < 0) return
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.setStreamVolume(AudioManager.STREAM_MUSIC, savedMediaVolume, 0)
            Log.i(TAG, "Media volume restored to $savedMediaVolume")
            savedMediaVolume = -1
        } catch (e: Exception) {
            Log.w(TAG, "Failed to restore media volume", e)
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Mirror Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Screen mirroring to Tesla"
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        // Tap notification → open MainActivity
        val openIntent = Intent(this, com.castla.mirror.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPending = PendingIntent.getActivity(
            this, 0, openIntent, PendingIntent.FLAG_IMMUTABLE
        )

        // Stop action
        val stopIntent = Intent(ACTION_STOP).apply {
            setPackage(packageName)
        }
        val stopPending = PendingIntent.getBroadcast(
            this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Castla")
            .setContentText("Streaming to Tesla")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setContentIntent(openPending)
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("Streaming to Tesla\nTap 'Stop' to end mirroring"))
            .addAction(android.R.drawable.ic_media_pause, "■ Stop Mirroring", stopPending)
            .build()
    }

    /**
     * BroadcastReceiver that stops the mirroring service when the notification
     * "Stop" button is tapped.
     */
    class StopReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            if (intent?.action == ACTION_STOP) {
                context.stopService(Intent(context, MirrorForegroundService::class.java))
                // Update widget to reflect stopped state
                MirrorWidgetProvider.updateAllWidgets(context)
            }
        }
    }
}
