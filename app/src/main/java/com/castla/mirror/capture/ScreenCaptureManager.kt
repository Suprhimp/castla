package com.castla.mirror.capture

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.util.DisplayMetrics
import android.util.Log
import android.view.Surface

class ScreenCaptureManager(private val context: Context) {

    companion object {
        private const val TAG = "ScreenCaptureManager"
        private const val VIRTUAL_DISPLAY_NAME = "Castla"
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    @Volatile
    var isRebuilding = false

    fun getMediaProjection(): MediaProjection? = mediaProjection

    var captureWidth = 1280
        private set
    var captureHeight = 720
        private set
    var captureDpi = 160
        private set

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            if (isRebuilding) {
                Log.i(TAG, "MediaProjection onStop during rebuild — ignoring")
                return
            }
            Log.i(TAG, "MediaProjection stopped")
            release()
        }
    }

    fun initProjection(resultCode: Int, data: Intent) {
        val projectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, data)
        mediaProjection?.registerCallback(projectionCallback, null)

        // Get display metrics for capture resolution
        val metrics = context.resources.displayMetrics
        captureWidth = metrics.widthPixels.coerceAtMost(1920)
        captureHeight = metrics.heightPixels.coerceAtMost(1080)
        captureDpi = metrics.densityDpi

        Log.i(TAG, "Projection initialized: ${captureWidth}x${captureHeight} @ ${captureDpi}dpi")
    }

    fun startCapture(surface: Surface, overrideWidth: Int = 0, overrideHeight: Int = 0) {
        val projection = mediaProjection ?: throw IllegalStateException("Projection not initialized")

        val width = if (overrideWidth > 0) overrideWidth else captureWidth
        val height = if (overrideHeight > 0) overrideHeight else captureHeight

        virtualDisplay = projection.createVirtualDisplay(
            VIRTUAL_DISPLAY_NAME,
            width,
            height,
            captureDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            surface,
            null,
            null
        )

        Log.i(TAG, "Capture started: ${width}x${height}")
    }

    /**
     * Reconfigure the existing VirtualDisplay with a new surface and dimensions.
     * Does NOT release the MediaProjection — safe for pipeline rebuild.
     */
    fun reconfigure(surface: Surface, width: Int, height: Int) {
        val vd = virtualDisplay
        if (vd != null) {
            vd.resize(width, height, captureDpi)
            vd.setSurface(surface)
            Log.i(TAG, "VirtualDisplay reconfigured: ${width}x${height}")
        } else {
            // No existing VD — create fresh
            startCapture(surface, width, height)
        }
    }

    fun stopCapture() {
        virtualDisplay?.release()
        virtualDisplay = null
        Log.i(TAG, "Capture stopped")
    }

    fun release() {
        stopCapture()
        mediaProjection?.unregisterCallback(projectionCallback)
        mediaProjection?.stop()
        mediaProjection = null
        Log.i(TAG, "Projection released")
    }
}
