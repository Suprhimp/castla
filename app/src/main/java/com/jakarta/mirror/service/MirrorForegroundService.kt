package com.jakarta.mirror.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.jakarta.mirror.capture.ScreenCaptureManager
import com.jakarta.mirror.capture.VideoEncoder
import com.jakarta.mirror.capture.VirtualDisplayManager
import com.jakarta.mirror.input.TouchInjector
import com.jakarta.mirror.server.MirrorServer
import com.jakarta.mirror.shizuku.ShizukuSetup

class MirrorForegroundService : Service() {

    companion object {
        private const val TAG = "MirrorService"
        private const val CHANNEL_ID = "castla_mirror"
        private const val NOTIFICATION_ID = 1
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
    }

    /** Binder for local (same-process) binding */
    inner class LocalBinder : Binder() {
        val service: MirrorForegroundService get() = this@MirrorForegroundService
    }

    private val binder = LocalBinder()

    private var mirrorServer: MirrorServer? = null
    private var screenCapture: ScreenCaptureManager? = null
    private var videoEncoder: VideoEncoder? = null
    private var touchInjector: TouchInjector? = null
    private var virtualDisplayManager: VirtualDisplayManager? = null
    private var shizukuSetup: ShizukuSetup? = null

    /** Session token — available immediately after pipeline starts */
    val sessionToken: String?
        get() = mirrorServer?.sessionToken

    val isRunning: Boolean
        get() = mirrorServer != null

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, -1) ?: -1
        val data = intent?.getParcelableExtra<Intent>(EXTRA_DATA)

        if (resultCode == -1 || data == null) {
            Log.e(TAG, "Invalid MediaProjection data")
            stopSelf()
            return START_NOT_STICKY
        }

        // Start foreground with notification
        val notification = createNotification()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )

        startPipeline(resultCode, data)

        return START_NOT_STICKY
    }

    private fun startPipeline(resultCode: Int, data: Intent) {
        try {
            // 1. Initialize screen capture (always needed — provides resolution + fallback)
            screenCapture = ScreenCaptureManager(this).also {
                it.initProjection(resultCode, data)
            }

            val width = screenCapture!!.captureWidth
            val height = screenCapture!!.captureHeight

            // 2. Initialize video encoder
            videoEncoder = VideoEncoder(width, height).also { encoder ->
                val surface = encoder.createInputSurface()

                // 3. Initialize touch injector
                touchInjector = TouchInjector(width, height)

                // 4. Start the web server
                mirrorServer = MirrorServer(this).also { server ->
                    server.setKeyframeRequester { encoder.requestKeyFrame() }
                    server.setTouchListener { event -> touchInjector?.onTouchEvent(event) }
                    server.start()
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
                        screenCapture?.startCapture(surface)
                        Log.i(TAG, "Using MediaProjection screen mirror")
                    }
                }
            }

            Log.i(TAG, "Pipeline started: ${width}x${height}")
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
        try {
            val setup = ShizukuSetup()
            setup.init()

            if (!setup.isAvailable() || !setup.hasPermission()) {
                Log.i(TAG, "Shizuku not available/permitted")
                setup.release()
                onResult(false)
                return
            }

            shizukuSetup = setup
            val vdm = VirtualDisplayManager()
            virtualDisplayManager = vdm

            vdm.bindShizukuService { bound ->
                if (bound) {
                    // Create VD with encoder surface attached — content renders into encoder
                    vdm.createVirtualDisplay(width, height, 160, surface)
                    if (vdm.hasVirtualDisplay()) {
                        Log.i(TAG, "Virtual display active — phone operates independently")
                        // Route touch events to virtual display (not main screen)
                        touchInjector?.setVirtualDisplayInjector { action, x, y, pointerId ->
                            vdm.injectInput(action, x, y, pointerId)
                        }
                        onResult(true)
                    } else {
                        Log.w(TAG, "VD creation failed, falling back")
                        onResult(false)
                    }
                } else {
                    Log.w(TAG, "Shizuku service bind failed")
                    onResult(false)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Shizuku setup failed", e)
            onResult(false)
        }
    }

    override fun onDestroy() {
        Log.i(TAG, "Stopping pipeline")
        virtualDisplayManager?.release()
        shizukuSetup?.release()
        screenCapture?.release()
        videoEncoder?.release()
        touchInjector?.release()
        mirrorServer?.stop()
        virtualDisplayManager = null
        shizukuSetup = null
        screenCapture = null
        videoEncoder = null
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
