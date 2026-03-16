package com.castla.mirror.capture

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.io.ByteArrayOutputStream

/**
 * Captures frames via ImageReader and compresses to JPEG.
 * Used as MJPEG fallback when the client doesn't support WebCodecs (e.g. HTTP context).
 */
class JpegEncoder(
    private val width: Int,
    private val height: Int,
    private val fps: Int = 15,
    private val quality: Int = 70
) {
    companion object {
        private const val TAG = "JpegEncoder"
    }

    private var imageReader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    @Volatile
    private var isRunning = false
    private var lastFrameTime = 0L
    private val frameIntervalMs = 1000L / fps

    fun createInputSurface(): Surface {
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        Log.i(TAG, "JPEG encoder created: ${width}x${height} @ ${fps}fps, quality=$quality")
        return imageReader!!.surface
    }

    fun start(onFrame: (data: ByteArray, isKeyFrame: Boolean) -> Unit) {
        val reader = imageReader ?: throw IllegalStateException("Call createInputSurface() first")

        thread = HandlerThread("JpegEncoder").also { it.start() }
        handler = Handler(thread!!.looper)
        isRunning = true

        reader.setOnImageAvailableListener({ ir ->
            if (!isRunning) return@setOnImageAvailableListener

            val now = System.currentTimeMillis()
            if (now - lastFrameTime < frameIntervalMs) {
                // Skip frame to maintain target FPS (reduce CPU/bandwidth)
                val image = ir.acquireLatestImage()
                image?.close()
                return@setOnImageAvailableListener
            }
            lastFrameTime = now

            val image = ir.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val plane = image.planes[0]
                val buffer = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * width

                val bitmap = Bitmap.createBitmap(
                    width + rowPadding / pixelStride,
                    height,
                    Bitmap.Config.ARGB_8888
                )
                bitmap.copyPixelsFromBuffer(buffer)

                // Crop if there's row padding
                val cropped = if (rowPadding > 0) {
                    Bitmap.createBitmap(bitmap, 0, 0, width, height).also {
                        bitmap.recycle()
                    }
                } else {
                    bitmap
                }

                val baos = ByteArrayOutputStream(width * height / 8)
                cropped.compress(Bitmap.CompressFormat.JPEG, quality, baos)
                cropped.recycle()

                val jpegData = baos.toByteArray()
                // MJPEG: every frame is a "keyframe"
                onFrame(jpegData, true)
            } catch (e: Exception) {
                Log.e(TAG, "JPEG encode error", e)
            } finally {
                image.close()
            }
        }, handler)

        Log.i(TAG, "JPEG encoder started")
    }

    fun release() {
        isRunning = false
        imageReader?.close()
        imageReader = null
        thread?.quitSafely()
        thread = null
        handler = null
        Log.i(TAG, "JPEG encoder released")
    }
}
