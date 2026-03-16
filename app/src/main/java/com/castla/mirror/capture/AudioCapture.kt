package com.castla.mirror.capture

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log

/**
 * Captures system audio via AudioPlaybackCapture (Android 10+) and encodes to AAC-LC.
 * Uses synchronous MediaCodec mode — both input feeding and output draining happen
 * on a single dedicated thread.
 */
class AudioCapture(
    private val mediaProjection: MediaProjection
) {
    companion object {
        private const val TAG = "AudioCapture"
        private const val SAMPLE_RATE = 44100
        private const val CHANNEL_COUNT = 2
        private const val BIT_RATE = 128_000
        private const val MIME_TYPE = "audio/mp4a-latm" // AAC-LC

        fun isSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    }

    private var audioRecord: AudioRecord? = null
    private var encoder: MediaCodec? = null
    @Volatile
    private var isRunning = false
    private var captureThread: Thread? = null

    /**
     * Start capture. Each callback ByteArray is prefixed with a 1-byte type header:
     *   0x00 = Codec Specific Data (AudioSpecificConfig) — sent once at start
     *   0x01 = Encoded AAC audio frame
     */
    fun start(onEncodedAudio: (data: ByteArray) -> Unit) {
        if (!isSupported()) {
            Log.w(TAG, "AudioPlaybackCapture requires Android 10+")
            return
        }

        try {
            setupAudioRecord()
            setupEncoder()

            isRunning = true
            audioRecord?.startRecording()
            encoder?.start()

            captureThread = Thread({
                val pcmBuffer = ByteArray(4096)
                val bufferInfo = MediaCodec.BufferInfo()

                while (isRunning) {
                    // 1. Read PCM from AudioRecord
                    val read = audioRecord?.read(pcmBuffer, 0, pcmBuffer.size) ?: -1
                    if (read > 0) {
                        feedEncoder(pcmBuffer, read)
                    }

                    // 2. Drain encoded output
                    drainEncoder(bufferInfo, onEncodedAudio)
                }
            }, "AudioCapture").also { it.start() }

            Log.i(TAG, "Audio capture started: ${SAMPLE_RATE}Hz, ${CHANNEL_COUNT}ch, ${BIT_RATE / 1000}kbps")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start audio capture", e)
            stop()
        }
    }

    private fun setupAudioRecord() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return

        val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val audioFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()

        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        audioRecord = AudioRecord.Builder()
            .setAudioPlaybackCaptureConfig(config)
            .setAudioFormat(audioFormat)
            .setBufferSizeInBytes(maxOf(minBufferSize * 2, 8192))
            .build()
    }

    private fun setupEncoder() {
        val format = MediaFormat.createAudioFormat(MIME_TYPE, SAMPLE_RATE, CHANNEL_COUNT).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }

        encoder = MediaCodec.createEncoderByType(MIME_TYPE).also { codec ->
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
    }

    private fun feedEncoder(data: ByteArray, size: Int) {
        val codec = encoder ?: return
        try {
            val index = codec.dequeueInputBuffer(5000) // 5ms timeout
            if (index >= 0) {
                val inputBuffer = codec.getInputBuffer(index) ?: return
                inputBuffer.clear()
                val copySize = minOf(size, inputBuffer.capacity())
                inputBuffer.put(data, 0, copySize)
                codec.queueInputBuffer(index, 0, copySize, System.nanoTime() / 1000, 0)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error feeding audio encoder", e)
        }
    }

    private fun drainEncoder(bufferInfo: MediaCodec.BufferInfo, onEncodedAudio: (ByteArray) -> Unit) {
        val codec = encoder ?: return
        while (true) {
            val index = codec.dequeueOutputBuffer(bufferInfo, 0) // non-blocking
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    Log.i(TAG, "Audio output format: ${codec.outputFormat}")
                }
                index >= 0 -> {
                    try {
                        if (bufferInfo.size > 0) {
                            val buffer = codec.getOutputBuffer(index) ?: continue
                            val rawData = ByteArray(bufferInfo.size)
                            buffer.position(bufferInfo.offset)
                            buffer.limit(bufferInfo.offset + bufferInfo.size)
                            buffer.get(rawData)

                            val isConfig = bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                            // Prefix: 0x00 = CSD (AudioSpecificConfig), 0x01 = audio frame
                            val header = if (isConfig) 0x00.toByte() else 0x01.toByte()
                            val prefixed = ByteArray(1 + rawData.size)
                            prefixed[0] = header
                            System.arraycopy(rawData, 0, prefixed, 1, rawData.size)
                            onEncodedAudio(prefixed)

                            if (isConfig) {
                                Log.i(TAG, "CSD sent: ${rawData.size} bytes = ${rawData.joinToString(" ") { "%02X".format(it) }}")
                            }
                        }
                        codec.releaseOutputBuffer(index, false)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error draining audio encoder", e)
                        try { codec.releaseOutputBuffer(index, false) } catch (_: Exception) {}
                    }
                }
                else -> return
            }
        }
    }

    fun stop() {
        isRunning = false
        captureThread?.join(1000)
        captureThread = null

        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null

        try { encoder?.stop() } catch (_: Exception) {}
        try { encoder?.release() } catch (_: Exception) {}
        encoder = null

        Log.i(TAG, "Audio capture stopped")
    }
}
