package com.jakarta.mirror.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.jakarta.mirror.capture.ScreenCaptureManager
import com.jakarta.mirror.capture.VideoEncoder
import com.jakarta.mirror.input.TouchInjector
import com.jakarta.mirror.server.MirrorServer

class MirrorForegroundService : Service() {

    companion object {
        private const val TAG = "MirrorService"
        private const val CHANNEL_ID = "jakarta_mirror"
        private const val NOTIFICATION_ID = 1
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
    }

    private var mirrorServer: MirrorServer? = null
    private var screenCapture: ScreenCaptureManager? = null
    private var videoEncoder: VideoEncoder? = null
    private var touchInjector: TouchInjector? = null

    override fun onBind(intent: Intent?): IBinder? = null

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
            // 1. Initialize screen capture
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

                // 6. Start screen capture into encoder's surface
                screenCapture!!.startCapture(surface)
            }

            Log.i(TAG, "Pipeline started: ${width}x${height}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start pipeline", e)
            stopSelf()
        }
    }

    override fun onDestroy() {
        Log.i(TAG, "Stopping pipeline")
        screenCapture?.release()
        videoEncoder?.release()
        touchInjector?.release()
        mirrorServer?.stop()
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
            .setContentTitle("Jakarta Mirror")
            .setContentText("Streaming to Tesla")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
    }
}
