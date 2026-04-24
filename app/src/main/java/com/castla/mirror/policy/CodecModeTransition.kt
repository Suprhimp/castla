package com.castla.mirror.policy

/**
 * Pure decision for whether a client codec-mode request should trigger a
 * pipeline rebuild.
 *
 * Encapsulates the guard used by `MirrorForegroundService.onCodecModeRequest`
 * so it can be unit-tested without spinning up an Android Service. Keeps the
 * orchestration (mutex, encoder tear-down, VD swap) in the service while the
 * branching logic lives here.
 */
object CodecModeTransition {

    const val MODE_H264 = "h264"
    const val MODE_MJPEG = "mjpeg"

    /**
     * @param requestedMode mode string carried by the client control message
     * @param currentMode the service's currently active codec mode
     * @param jpegEncoderActive whether a JpegEncoder is already live
     * @return true if the service should apply the switch (set mode + rebuild)
     */
    fun shouldApply(
        requestedMode: String,
        currentMode: String,
        jpegEncoderActive: Boolean
    ): Boolean {
        if (requestedMode != MODE_MJPEG) return false
        if (currentMode == MODE_MJPEG && jpegEncoderActive) return false
        return true
    }
}
