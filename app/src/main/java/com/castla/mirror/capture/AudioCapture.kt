package com.castla.mirror.capture

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import com.castla.mirror.shizuku.IPrivilegedService
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
    private val mediaProjection: MediaProjection,
    private val privilegedService: IPrivilegedService? = null
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
    // HandlerThread for async MediaCodec callback (Opus path)
    private var encoderThread: HandlerThread? = null
    private var encoderHandler: Handler? = null
    // Pipe from Shizuku REMOTE_SUBMIX capture
    private var remoteSubmixPipe: ParcelFileDescriptor? = null
    private var usingRemoteSubmix = false

    fun start(onAudioData: (data: ByteArray) -> Unit) {
        if (!isSupported()) {
            Log.w(TAG, "AudioPlaybackCapture requires Android 10+")
            return
        }

        try {
            setupAudioRecord()
            isRunning = true

            if (usingRemoteSubmix) {
                // REMOTE_SUBMIX: read PCM from Shizuku pipe, always PCM (encode later if needed)
                sendConfig("pcm", onAudioData)
                startRemoteSubmixCapture(onAudioData)
                Log.i(TAG, "Audio capture started (REMOTE_SUBMIX): ${SAMPLE_RATE}Hz, ${CHANNEL_COUNT}ch, pcm")
            } else {
                val useOpus = trySetupOpusEncoder(onAudioData)
                audioRecord?.startRecording()

                val codec = if (useOpus) "opus" else "pcm"
                sendConfig(codec, onAudioData)

                if (useOpus) {
                    startOpusPcmFeeder()
                } else {
                    startRawPcmCapture(onAudioData)
                }
                Log.i(TAG, "Audio capture started: ${SAMPLE_RATE}Hz, ${CHANNEL_COUNT}ch, $codec")
            }
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
            sendConfig("pcm", onAudioData)

            if (usingRemoteSubmix) {
                startRemoteSubmixCapture(onAudioData)
                Log.i(TAG, "Audio capture started (REMOTE_SUBMIX PCM only): ${SAMPLE_RATE}Hz, ${CHANNEL_COUNT}ch")
            } else {
                audioRecord?.startRecording()
                startRawPcmCapture(onAudioData)
                Log.i(TAG, "Audio capture started (PCM only): ${SAMPLE_RATE}Hz, ${CHANNEL_COUNT}ch")
            }
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

    /**
     * Sets up Opus encoder with async MediaCodec.Callback.
     * Returns true if Opus is available, false to fall back to PCM.
     */
    private fun trySetupOpusEncoder(onAudioData: (data: ByteArray) -> Unit): Boolean {
        return try {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, SAMPLE_RATE, CHANNEL_COUNT).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, OPUS_BITRATE)
            }
            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)

            // Set up async callback before start() — matches VideoEncoder pattern
            encoderThread = HandlerThread("AudioCapture-Opus").also { it.start() }
            encoderHandler = Handler(encoderThread!!.looper)

            codec.setCallback(object : MediaCodec.Callback() {
                private val pcmBuffer = ByteArray(PCM_BUFFER_SIZE)

                override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                    if (!isRunning) return
                    try {
                        val inputBuf = codec.getInputBuffer(index) ?: return
                        val read = audioRecord?.read(pcmBuffer, 0, minOf(pcmBuffer.size, inputBuf.remaining())) ?: -1
                        if (read > 0) {
                            inputBuf.clear()
                            inputBuf.put(pcmBuffer, 0, read)
                            codec.queueInputBuffer(index, 0, read, System.nanoTime() / 1000, 0)
                        } else {
                            codec.queueInputBuffer(index, 0, 0, 0, 0)
                        }
                    } catch (e: IllegalStateException) {
                        Log.w(TAG, "Opus input buffer error (codec released?)", e)
                    }
                }

                override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                    if (!isRunning) {
                        try { codec.releaseOutputBuffer(index, false) } catch (_: IllegalStateException) {}
                        return
                    }
                    try {
                        // Skip codec config buffers (client configures via JSON config)
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            codec.releaseOutputBuffer(index, false)
                            return
                        }

                        if (info.size > 0) {
                            val outputBuf = codec.getOutputBuffer(index)
                            if (outputBuf != null) {
                                val encoded = ByteArray(info.size)
                                outputBuf.position(info.offset)
                                outputBuf.limit(info.offset + info.size)
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
                        codec.releaseOutputBuffer(index, false)
                    } catch (e: IllegalStateException) {
                        Log.w(TAG, "Opus output buffer error (codec released?)", e)
                    }
                }

                override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                    Log.e(TAG, "Opus encoder error", e)
                    isRunning = false
                }

                override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                    Log.i(TAG, "Opus output format changed: $format")
                }
            }, encoderHandler)

            codec.start()
            encoder = codec
            Log.i(TAG, "Opus encoder ready (async callback)")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Opus encoder unavailable, using raw PCM", e)
            try { encoder?.stop() } catch (_: Exception) {}
            try { encoder?.release() } catch (_: Exception) {}
            encoder = null
            encoderThread?.quitSafely()
            encoderThread = null
            encoderHandler = null
            false
        }
    }

    /**
     * For the async Opus path, input buffers are fed via onInputBufferAvailable callback.
     * No separate capture thread needed — the callback reads PCM directly from AudioRecord.
     * This method exists as a no-op placeholder for clarity.
     */
    private fun startOpusPcmFeeder() {
        // Async callback handles everything — no polling thread needed.
        // onInputBufferAvailable reads from audioRecord on the HandlerThread.
    }

    /**
     * Read PCM from Shizuku REMOTE_SUBMIX pipe and forward with timestamp header.
     */
    private fun startRemoteSubmixCapture(onAudioData: (data: ByteArray) -> Unit) {
        val pipe = remoteSubmixPipe ?: return
        captureThread = Thread({
            val pcmBuffer = ByteArray(PCM_BUFFER_SIZE)
            val input = ParcelFileDescriptor.AutoCloseInputStream(pipe)
            try {
                while (isRunning) {
                    val read = input.read(pcmBuffer)
                    if (read > 0) {
                        val tsMs = SystemClock.elapsedRealtime().toInt()
                        val header = ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN)
                        header.put(0x01.toByte())
                        header.putInt(tsMs)
                        val msg = ByteArray(5 + read)
                        System.arraycopy(header.array(), 0, msg, 0, 5)
                        System.arraycopy(pcmBuffer, 0, msg, 5, read)
                        onAudioData(msg)
                    } else if (read < 0) {
                        Log.w(TAG, "REMOTE_SUBMIX pipe closed")
                        break
                    }
                }
            } catch (e: Exception) {
                if (isRunning) Log.w(TAG, "REMOTE_SUBMIX read error", e)
            } finally {
                try { input.close() } catch (_: Exception) {}
            }
        }, "AudioCapture-RemoteSubmix").also { it.start() }
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

        // Priority 1: Use Shizuku REMOTE_SUBMIX — captures ALL audio (navigation, notifications, etc.)
        // Shell uid (2000) has CAPTURE_AUDIO_OUTPUT permission that normal apps don't have.
        if (privilegedService != null) {
            try {
                val pipe = privilegedService.startSystemAudioCapture(SAMPLE_RATE, CHANNEL_COUNT)
                if (pipe != null) {
                    remoteSubmixPipe = pipe
                    usingRemoteSubmix = true
                    Log.i(TAG, "Using REMOTE_SUBMIX via Shizuku — ALL audio will be captured")
                    return
                }
            } catch (e: Exception) {
                Log.w(TAG, "REMOTE_SUBMIX via Shizuku failed, falling back to AudioPlaybackCapture", e)
            }
        }

        // Priority 2: AudioPlaybackCapture (can only capture MEDIA/GAME/UNKNOWN without system permission)
        val audioFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()

        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT
        )

        val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        audioRecord = AudioRecord.Builder()
            .setAudioPlaybackCaptureConfig(config)
            .setAudioFormat(audioFormat)
            .setBufferSizeInBytes(maxOf(minBufferSize * 2, 8192))
            .build()
        Log.w(TAG, "Using AudioPlaybackCapture (BASIC usages only — navigation audio will NOT be captured)")
    }

    fun stop() {
        isRunning = false

        // Stop Shizuku REMOTE_SUBMIX capture
        if (usingRemoteSubmix) {
            try { privilegedService?.stopSystemAudioCapture() } catch (_: Exception) {}
            try { remoteSubmixPipe?.close() } catch (_: Exception) {}
            remoteSubmixPipe = null
            usingRemoteSubmix = false
        }

        // Stop audioRecord (AudioPlaybackCapture path)
        try { audioRecord?.stop() } catch (_: Exception) {}
        captureThread?.join(2000)
        captureThread = null
        try { encoder?.stop() } catch (_: Exception) {}
        try { encoder?.release() } catch (_: Exception) {}
        encoder = null
        encoderThread?.quitSafely()
        encoderThread = null
        encoderHandler = null
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
        Log.i(TAG, "Audio capture stopped")
    }
}
