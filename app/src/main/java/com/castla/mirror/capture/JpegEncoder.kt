package com.castla.mirror.capture

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PixelFormat
import android.graphics.Rect
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
    private var fps: Int = 15,
    private var quality: Int = 70
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
    private var frameIntervalMs = 1000L / fps

    private var reusableBitmap: Bitmap? = null
    private var croppedBitmap: Bitmap? = null
    private var cropCanvas: Canvas? = null
    private val cropSrcRect = Rect()
    private val cropDstRect = Rect()

    private val baos = ByteArrayOutputStream(width * height / 8)

    fun createInputSurface(): Surface {
        // RGBA_8888 방식이 하드웨어 디코더에 따라 호환되지 않는 경우가 있어
        // 오류를 뿜고 프레임(이미지)이 안 나올 수 있으므로 호환성이 높은 포맷으로 테스트
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        Log.i(TAG, "JPEG encoder created: ${width}x${height} @ ${fps}fps, quality=$quality")
        return imageReader!!.surface
    }
    
    fun setFps(newFps: Int) {
        if (newFps > 0) {
            fps = newFps
            frameIntervalMs = 1000L / fps
            Log.i(TAG, "JPEG encoder FPS changed to $fps")
        }
    }
    
    fun setQuality(newQuality: Int) {
        if (newQuality in 1..100) {
            quality = newQuality
            Log.i(TAG, "JPEG encoder quality changed to $quality")
        }
    }

    fun start(onFrame: (data: ByteArray, isKeyFrame: Boolean) -> Unit) {
        val reader = imageReader ?: throw IllegalStateException("Call createInputSurface() first")

        thread = HandlerThread("JpegEncoder").also { it.start() }
        handler = Handler(thread!!.looper)
        isRunning = true

        reader.setOnImageAvailableListener({ ir ->
            if (!isRunning) return@setOnImageAvailableListener

            val now = System.currentTimeMillis()
            
            // Frame rate control
            if (now - lastFrameTime < frameIntervalMs) {
                val imageToSkip = try { ir.acquireLatestImage() } catch (e: Exception) { null }
                imageToSkip?.close()
                return@setOnImageAvailableListener
            }

            val image = try {
                ir.acquireLatestImage()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to acquire image: ${e.message}")
                null
            } ?: return@setOnImageAvailableListener
            
            lastFrameTime = now

            try {
                val plane = image.planes[0]
                val buffer = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                
                // 가끔 rowStride나 pixelStride가 이상하게 넘어와서 터지는 것을 방지
                if (pixelStride == 0 || rowStride == 0) {
                    Log.w(TAG, "Invalid stride: pixel=$pixelStride, row=$rowStride")
                    return@setOnImageAvailableListener
                }

                val rowPadding = rowStride - pixelStride * width
                // 실제 메모리에 깔린 비트맵의 가로폭
                val bitmapWidth = width + rowPadding / pixelStride

                if (reusableBitmap == null || reusableBitmap!!.width != bitmapWidth || reusableBitmap!!.height != height) {
                    reusableBitmap?.recycle()
                    // ARGB_8888을 통해 픽셀 복사
                    reusableBitmap = Bitmap.createBitmap(bitmapWidth, height, Bitmap.Config.ARGB_8888)
                }
                
                buffer.rewind()
                reusableBitmap!!.copyPixelsFromBuffer(buffer)

                // 패딩이 있다면 우리가 원하는 width/height만큼만 잘라냄
                val target = if (rowPadding > 0) {
                    if (croppedBitmap == null || croppedBitmap!!.width != width || croppedBitmap!!.height != height) {
                        croppedBitmap?.recycle()
                        croppedBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        cropCanvas = Canvas(croppedBitmap!!)
                    }
                    cropSrcRect.set(0, 0, width, height)
                    cropDstRect.set(0, 0, width, height)
                    cropCanvas!!.drawBitmap(reusableBitmap!!, cropSrcRect, cropDstRect, null)
                    croppedBitmap!!
                } else {
                    reusableBitmap!!
                }

                baos.reset()
                target.compress(Bitmap.CompressFormat.JPEG, quality, baos)

                val jpegData = baos.toByteArray()
                // Log.d(TAG, "Encoded JPEG frame: ${jpegData.size} bytes")
                
                // 전송
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
        thread?.quitSafely()
        thread = null
        handler = null
        
        imageReader?.close()
        imageReader = null
        
        reusableBitmap?.recycle()
        reusableBitmap = null
        
        croppedBitmap?.recycle()
        croppedBitmap = null
        cropCanvas = null

        Log.i(TAG, "JPEG encoder released")
    }
}
