package com.castla.mirror.capture

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log

/**
 * Captures system audio via AudioPlaybackCapture (Android 10+) and streams
 * raw PCM Int16 directly — no encoding step.
 *
 * Protocol: each callback ByteArray has a 1-byte header:
 *   0x00 = config JSON (sent once): {"sampleRate":44100,"channels":2}
 *   0x01 = raw PCM Int16 LE interleaved samples
 */
class AudioCapture(
    private val mediaProjection: MediaProjection
) {
    companion object {
        private const val TAG = "AudioCapture"
        const val SAMPLE_RATE = 44100
        const val CHANNEL_COUNT = 2
        // ~23ms per chunk (matches AAC frame duration) = 1024 stereo frames = 4096 bytes
        private const val PCM_BUFFER_SIZE = 4096

        fun isSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    }

    private var audioRecord: AudioRecord? = null
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

            isRunning = true
            audioRecord?.startRecording()

            // Send config as first message
            val config = """{"sampleRate":$SAMPLE_RATE,"channels":$CHANNEL_COUNT}""".toByteArray()
            val configMsg = ByteArray(1 + config.size)
            configMsg[0] = 0x00
            System.arraycopy(config, 0, configMsg, 1, config.size)
            onAudioData(configMsg)

            captureThread = Thread({
                val pcmBuffer = ByteArray(PCM_BUFFER_SIZE)

                while (isRunning) {
                    val read = audioRecord?.read(pcmBuffer, 0, pcmBuffer.size) ?: -1
                    if (read > 0) {
                        val msg = ByteArray(1 + read)
                        msg[0] = 0x01
                        System.arraycopy(pcmBuffer, 0, msg, 1, read)
                        onAudioData(msg)
                    }
                }
            }, "AudioCapture").also { it.start() }

            Log.i(TAG, "Audio capture started: ${SAMPLE_RATE}Hz, ${CHANNEL_COUNT}ch, raw PCM")
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

    fun stop() {
        isRunning = false
        captureThread?.join(1000)
        captureThread = null

        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null

        Log.i(TAG, "Audio capture stopped")
    }
}
