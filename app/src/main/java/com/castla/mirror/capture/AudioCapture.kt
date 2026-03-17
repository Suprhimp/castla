package com.castla.mirror.capture

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Build
import android.os.SystemClock
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Captures system audio via AudioPlaybackCapture (Android 10+) and encodes to Opus
 * for efficient streaming (~96kbps vs ~1.4Mbps raw PCM).
 *
 * Protocol: each callback ByteArray has a header:
 *   0x00 + JSON = config: {"codec":"opus"|"pcm","sampleRate":48000,"channels":2}
 *   0x01 + u32 LE timestamp (ms) + audio data = encoded Opus frame or raw PCM Int16 LE
 *
 * Falls back to raw PCM if Opus encoding fails to initialize.
 */
class AudioCapture(
    private val mediaProjection: MediaProjection
) {
    companion object {
        private const val TAG = "AudioCapture"
        const val SAMPLE_RATE = 48000  // Opus native sample rate
        const val CHANNEL_COUNT = 2
        private const val OPUS_BITRATE = 96_000
        // 20ms of audio at 48kHz stereo Int16 = 960 frames * 2ch * 2 bytes = 3840 bytes
        private const val PCM_BUFFER_SIZE = 3840

        fun isSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    }

    private var audioRecord: AudioRecord? = null
    private var encoder: MediaCodec? = null
    @Volatile
    private var isRunning = false
    private var captureThread: Thread? = null

    fun start(onAudioData: (data: ByteArray) -> Unit) {
        if (!isSupported()) {
            Log.w(TAG, "AudioPlaybackCapture requires Android 10+")
            return
        }

        try {
            setupAudioRecord()

            val useOpus = trySetupOpusEncoder()

            isRunning = true
            audioRecord?.startRecording()

            // Send config JSON so client knows codec type immediately
            val codec = if (useOpus) "opus" else "pcm"
            sendConfig(codec, onAudioData)

            if (useOpus) {
                startOpusCapture(onAudioData)
            } else {
                startRawPcmCapture(onAudioData)
            }

            Log.i(TAG, "Audio capture started: ${SAMPLE_RATE}Hz, ${CHANNEL_COUNT}ch, $codec")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start audio capture", e)
            stop()
        }
    }

    /**
     * Start in PCM-only mode (no Opus). Used when client doesn't support WebCodecs AudioDecoder.
     */
    fun startPcmOnly(onAudioData: (data: ByteArray) -> Unit) {
        if (!isSupported()) return
        try {
            setupAudioRecord()
            isRunning = true
            audioRecord?.startRecording()
            sendConfig("pcm", onAudioData)
            startRawPcmCapture(onAudioData)
            Log.i(TAG, "Audio capture started (PCM only): ${SAMPLE_RATE}Hz, ${CHANNEL_COUNT}ch")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start PCM audio capture", e)
            stop()
        }
    }

    private fun sendConfig(codec: String, onAudioData: (data: ByteArray) -> Unit) {
        val json = """{"codec":"$codec","sampleRate":$SAMPLE_RATE,"channels":$CHANNEL_COUNT}""".toByteArray()
        val msg = ByteArray(1 + json.size)
        msg[0] = 0x00
        System.arraycopy(json, 0, msg, 1, json.size)
        onAudioData(msg)
    }

    private fun trySetupOpusEncoder(): Boolean {
        return try {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, SAMPLE_RATE, CHANNEL_COUNT).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, OPUS_BITRATE)
            }
            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            encoder = codec
            Log.i(TAG, "Opus encoder ready")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Opus encoder unavailable, using raw PCM", e)
            try { encoder?.stop() } catch (_: Exception) {}
            try { encoder?.release() } catch (_: Exception) {}
            encoder = null
            false
        }
    }

    private fun startOpusCapture(onAudioData: (data: ByteArray) -> Unit) {
        val codec = encoder ?: return

        captureThread = Thread({
            val pcmBuffer = ByteArray(PCM_BUFFER_SIZE)
            val bufferInfo = MediaCodec.BufferInfo()

            while (isRunning) {
                // Feed PCM to encoder
                val inputIdx = codec.dequeueInputBuffer(5_000)
                if (inputIdx >= 0) {
                    val inputBuf = codec.getInputBuffer(inputIdx)
                    if (inputBuf != null) {
                        val read = audioRecord?.read(pcmBuffer, 0, minOf(pcmBuffer.size, inputBuf.remaining())) ?: -1
                        if (read > 0) {
                            inputBuf.clear()
                            inputBuf.put(pcmBuffer, 0, read)
                            codec.queueInputBuffer(inputIdx, 0, read, System.nanoTime() / 1000, 0)
                        } else {
                            codec.queueInputBuffer(inputIdx, 0, 0, 0, 0)
                        }
                    }
                }

                // Drain encoded output
                while (isRunning) {
                    val outputIdx = codec.dequeueOutputBuffer(bufferInfo, 0)
                    if (outputIdx < 0) break

                    // Skip codec config buffers (client configures via JSON config)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        Log.d(TAG, "Opus CSD received (${bufferInfo.size} bytes), skipping")
                        codec.releaseOutputBuffer(outputIdx, false)
                        continue
                    }

                    if (bufferInfo.size > 0) {
                        val outputBuf = codec.getOutputBuffer(outputIdx)
                        if (outputBuf != null) {
                            val encoded = ByteArray(bufferInfo.size)
                            outputBuf.position(bufferInfo.offset)
                            outputBuf.limit(bufferInfo.offset + bufferInfo.size)
                            outputBuf.get(encoded)

                            // 5-byte header: [0x01][tsMs u32 LE] + opus data
                            val tsMs = SystemClock.elapsedRealtime().toInt()
                            val header = ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN)
                            header.put(0x01.toByte())
                            header.putInt(tsMs)
                            val msg = ByteArray(5 + encoded.size)
                            System.arraycopy(header.array(), 0, msg, 0, 5)
                            System.arraycopy(encoded, 0, msg, 5, encoded.size)
                            onAudioData(msg)
                        }
                    }
                    codec.releaseOutputBuffer(outputIdx, false)
                }
            }
        }, "AudioCapture-Opus").also { it.start() }
    }

    private fun startRawPcmCapture(onAudioData: (data: ByteArray) -> Unit) {
        captureThread = Thread({
            val pcmBuffer = ByteArray(PCM_BUFFER_SIZE)
            while (isRunning) {
                val read = audioRecord?.read(pcmBuffer, 0, pcmBuffer.size) ?: -1
                if (read > 0) {
                    // 5-byte header: [0x01][tsMs u32 LE] + pcm data
                    val tsMs = SystemClock.elapsedRealtime().toInt()
                    val header = ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN)
                    header.put(0x01.toByte())
                    header.putInt(tsMs)
                    val msg = ByteArray(5 + read)
                    System.arraycopy(header.array(), 0, msg, 0, 5)
                    System.arraycopy(pcmBuffer, 0, msg, 5, read)
                    onAudioData(msg)
                }
            }
        }, "AudioCapture-PCM").also { it.start() }
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
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT
        )

        audioRecord = AudioRecord.Builder()
            .setAudioPlaybackCaptureConfig(config)
            .setAudioFormat(audioFormat)
            .setBufferSizeInBytes(maxOf(minBufferSize * 2, 8192))
            .build()
    }

    fun stop() {
        isRunning = false
        captureThread?.join(1000)
        captureThread = null
        try { encoder?.stop() } catch (_: Exception) {}
        try { encoder?.release() } catch (_: Exception) {}
        encoder = null
        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
        Log.i(TAG, "Audio capture stopped")
    }
}
