package com.castla.mirror.capture

import android.os.Build
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class AudioCaptureTest {

    @Test
    @Config(sdk = [29]) // Android 10 (Q)
    fun `isSupported returns true on Android 10+`() {
        assertTrue(AudioCapture.isSupported())
    }

    @Test
    @Config(sdk = [28]) // Android 9 (Pie)
    fun `isSupported returns false below Android 10`() {
        assertFalse(AudioCapture.isSupported())
    }

    @Test
    @Config(sdk = [30]) // Android 11
    fun `isSupported returns true on Android 11`() {
        assertTrue(AudioCapture.isSupported())
    }

    @Test
    @Config(sdk = [26]) // Android 8 (minSdk)
    fun `isSupported returns false on Android 8`() {
        assertFalse(AudioCapture.isSupported())
    }
}
