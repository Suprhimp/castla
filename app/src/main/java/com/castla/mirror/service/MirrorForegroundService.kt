package com.castla.mirror.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.castla.mirror.capture.AudioCapture
import com.castla.mirror.capture.JpegEncoder
import com.castla.mirror.capture.ScreenCaptureManager
import com.castla.mirror.capture.VideoEncoder
import com.castla.mirror.capture.VirtualDisplayManager
import com.castla.mirror.input.TouchInjector
import com.castla.mirror.server.MirrorServer
import com.castla.mirror.shizuku.ShizukuSetup

class MirrorForegroundService : Service() {

    companion object {
        private const val TAG = "MirrorService"
        private const val CHANNEL_ID = "castla_mirror"
        private const val NOTIFICATION_ID = 1
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
        const val EXTRA_WIDTH = "width"
        const val EXTRA_HEIGHT = "height"
        const val EXTRA_BITRATE = "bitrate"
        const val EXTRA_FPS = "fps"
        const val EXTRA_AUDIO = "audio_enabled"
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
    private var currentBitrate: Int = 2_000_000
    private var currentFps: Int = 30

    /** Session PIN — available immediately after pipeline starts */
    val sessionPin: String?
        get() = mirrorServer?.sessionPin

    val isRunning: Boolean
        get() = mirrorServer != null

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
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
        val settingsBitrate = intent.getIntExtra(EXTRA_BITRATE, 2_000_000)
        val settingsFps = intent.getIntExtra(EXTRA_FPS, 30)
        val audioEnabled = intent.getBooleanExtra(EXTRA_AUDIO, false)

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

                // 3. Initialize touch injector
                touchInjector = TouchInjector(width, height)

                // 4. Start the web server
                mirrorServer = MirrorServer(this).also { server ->
                    server.setKeyframeRequester { encoder.requestKeyFrame() }
                    server.setTouchListener { event -> touchInjector?.onTouchEvent(event) }
                    server.setCodecModeListener { mode -> onCodecModeRequest(mode) }
                    server.start(0) // no read timeout — WebSockets stay open indefinitely
                    Log.i(TAG, "Server started on port 8080")
                }

                // 5. Start encoding — frames go to server
                encoder.start { frameData, isKeyFrame ->
                    mirrorServer?.broadcastFrame(frameData, isKeyFrame)
                }

                // 6. Try Shizuku virtual display for independent phone operation
                // If Shizuku succeeds: VD renders onto encoder surface, touch goes to VD
                // If Shizuku fails: fall back to MediaProjection screen mirror
                trySetupVirtualDisplay(width, height, surface) { shizukuActive ->
                    if (!shizukuActive) {
                        // Shizuku unavailable — capture main screen via MediaProjection
                        try {
                            screenCapture?.startCapture(surface, width, height)
                            Log.i(TAG, "Using MediaProjection screen mirror at ${width}x${height}")
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
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start pipeline", e)
            stopSelf()
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
     * Switch encoding mode when client requests MJPEG (no WebCodecs available).
     * Creates a JpegEncoder, captures screen to it, stops the H.264 encoder.
     */
    private fun onCodecModeRequest(mode: String) {
        if (mode != "mjpeg" || jpegEncoder != null) return

        Log.i(TAG, "Switching to MJPEG mode")
        try {
            // Create JPEG encoder with lower FPS to save bandwidth
            val jpeg = JpegEncoder(currentWidth, currentHeight, fps = 15, quality = 65)
            val surface = jpeg.createInputSurface()

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
                // VD already active — just change its surface
                // Note: VD surface change may not be supported, fall through to MediaProjection
                Log.i(TAG, "VD active, cannot switch surface easily — using MediaProjection")
            }

            // Use MediaProjection to capture to JPEG surface
            try {
                screenCapture?.startCapture(surface, currentWidth, currentHeight)
                Log.i(TAG, "MJPEG capture started via MediaProjection at ${currentWidth}x${currentHeight}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start MJPEG capture", e)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to switch to MJPEG", e)
        }
    }

    override fun onDestroy() {
        Log.i(TAG, "Stopping pipeline")
        audioCapture?.stop()
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
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Mirror Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Screen mirroring to Tesla"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Castla")
            .setContentText("Streaming to Tesla")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
    }
}
