package com.castla.mirror.capture

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer

class VideoEncoder(
    private val width: Int,
    private val height: Int,
    private val bitrate: Int = 2_000_000,
    private val fps: Int = 30
) {
    companion object {
        private const val TAG = "VideoEncoder"
        private const val MIME_TYPE = "video/avc" // H.264
        private const val KEYFRAME_INTERVAL = 1 // seconds
    }

    private var codec: MediaCodec? = null
    private var encoderThread: HandlerThread? = null
    private var encoderHandler: Handler? = null
    private var isRunning = false

    // SPS and PPS NAL units needed for decoder initialization
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null

    fun createInputSurface(): Surface {
        val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, KEYFRAME_INTERVAL)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31)
            // Low latency hints
            setInteger(MediaFormat.KEY_LATENCY, 0)
            setInteger(MediaFormat.KEY_PRIORITY, 0) // real-time priority
        }

        val encoder = MediaCodec.createEncoderByType(MIME_TYPE)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = encoder.createInputSurface()
        codec = encoder

        Log.i(TAG, "Encoder created: ${width}x${height} @ ${bitrate / 1000}kbps, ${fps}fps")
        return surface
    }

    fun start(onEncodedFrame: (data: ByteArray, isKeyFrame: Boolean) -> Unit) {
        val encoder = codec ?: throw IllegalStateException("Call createInputSurface() first")

        encoderThread = HandlerThread("VideoEncoder").also { it.start() }
        encoderHandler = Handler(encoderThread!!.looper)

        isRunning = true

        encoder.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                // Not used with Surface input
            }

            override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                if (!isRunning) return

                try {
                    val buffer = codec.getOutputBuffer(index) ?: return

                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        // Extract SPS/PPS from codec config
                        extractSpsPps(buffer, info)
                        codec.releaseOutputBuffer(index, false)
                        return
                    }

                    if (info.size > 0) {
                        val data = ByteArray(info.size)
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        buffer.get(data)

                        val isKeyFrame = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0

                        if (isKeyFrame && sps != null && pps != null) {
                            // Prepend SPS+PPS before keyframe for decoder init
                            val fullFrame = sps!! + pps!! + data
                            onEncodedFrame(fullFrame, true)
                        } else {
                            onEncodedFrame(data, isKeyFrame)
                        }
                    }

                    codec.releaseOutputBuffer(index, false)
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing output buffer", e)
                    try { codec.releaseOutputBuffer(index, false) } catch (_: Exception) {}
                }
            }

            override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                Log.e(TAG, "Encoder error", e)
            }

            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                Log.i(TAG, "Output format changed: $format")
            }
        }, encoderHandler)

        encoder.start()
        Log.i(TAG, "Encoder started")
    }

    private fun extractSpsPps(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        val configData = ByteArray(info.size)
        buffer.position(info.offset)
        buffer.limit(info.offset + info.size)
        buffer.get(configData)

        // Parse Annex-B NAL units to find SPS (type 7) and PPS (type 8)
        var i = 0
        while (i < configData.size - 4) {
            // Look for start code 0x00000001
            if (configData[i] == 0.toByte() && configData[i + 1] == 0.toByte() &&
                configData[i + 2] == 0.toByte() && configData[i + 3] == 1.toByte()) {

                val nalType = configData[i + 4].toInt() and 0x1F
                val nalStart = i

                // Find next start code or end
                var nalEnd = configData.size
                var j = i + 4
                while (j < configData.size - 3) {
                    if (configData[j] == 0.toByte() && configData[j + 1] == 0.toByte() &&
                        configData[j + 2] == 0.toByte() && configData[j + 3] == 1.toByte()) {
                        nalEnd = j
                        break
                    }
                    j++
                }

                val nalUnit = configData.copyOfRange(nalStart, nalEnd)
                when (nalType) {
                    7 -> {
                        sps = nalUnit
                        Log.i(TAG, "SPS extracted (${nalUnit.size} bytes)")
                    }
                    8 -> {
                        pps = nalUnit
                        Log.i(TAG, "PPS extracted (${nalUnit.size} bytes)")
                    }
                }
                i = nalEnd
            } else {
                i++
            }
        }
    }

    fun requestKeyFrame() {
        try {
            val params = Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            }
            codec?.setParameters(params)
            Log.d(TAG, "Keyframe requested")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to request keyframe", e)
        }
    }

    fun stop() {
        isRunning = false
        try {
            codec?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping encoder", e)
        }
    }

    fun release() {
        stop()
        try {
            codec?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing encoder", e)
        }
        codec = null
        encoderThread?.quitSafely()
        encoderThread = null
        encoderHandler = null
        Log.i(TAG, "Encoder released")
    }
}
