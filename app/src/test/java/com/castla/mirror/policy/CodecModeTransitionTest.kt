package com.castla.mirror.policy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodecModeTransitionTest {

    @Test
    fun `non-mjpeg request is rejected regardless of state`() {
        assertFalse(CodecModeTransition.shouldApply("h264", "h264", jpegEncoderActive = false))
        assertFalse(CodecModeTransition.shouldApply("h264", "mjpeg", jpegEncoderActive = true))
        assertFalse(CodecModeTransition.shouldApply("", "h264", jpegEncoderActive = false))
        assertFalse(CodecModeTransition.shouldApply("av1", "h264", jpegEncoderActive = false))
    }

    @Test
    fun `mjpeg request from h264 applies`() {
        assertTrue(CodecModeTransition.shouldApply("mjpeg", "h264", jpegEncoderActive = false))
    }

    @Test
    fun `mjpeg request when already mjpeg with active encoder is a no-op`() {
        assertFalse(CodecModeTransition.shouldApply("mjpeg", "mjpeg", jpegEncoderActive = true))
    }

    @Test
    fun `mjpeg request when mode is mjpeg but encoder is missing still applies`() {
        // Covers the case where the mode flag was set but the previous rebuild
        // failed to finish creating the JpegEncoder — the next request must
        // still trigger a rebuild instead of silently skipping.
        assertTrue(CodecModeTransition.shouldApply("mjpeg", "mjpeg", jpegEncoderActive = false))
    }
}
