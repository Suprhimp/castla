package com.castla.mirror.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamMathTest {

    @Test
    fun `test dimensions are aligned to 16`() {
        // H.264 encoders usually require width and height to be multiples of 16.
        
        // 1920x1080 is already aligned (1920 / 16 = 120, 1080 / 16 = 67.5 -> WAIT. 1080 % 16 is 8!)
        // So 1080 becomes 1088.
        val (w1, h1) = StreamMath.calculateDimensions(1920, 1080, 1080)
        assertEquals(1920, w1)
        assertEquals(1088, h1) // 1080 -> 1088
        
        val (w2, h2) = StreamMath.calculateDimensions(1000, 500, 720)
        assertEquals(1008, w2) // 1000 -> 1008
        assertEquals(512, h2)  // 500 -> 512
        
        val (w3, h3) = StreamMath.calculateDimensions(320, 240, 720)
        assertEquals(320, w3)
        assertEquals(240, h3)
    }

    @Test
    fun `test dimensions are scaled down to maxHeight`() {
        // Raw screen is 2560x1440, max allowed is 720
        // Scale = 720 / 1440 = 0.5
        // New width = 2560 * 0.5 = 1280
        // After alignment: 1280x720 (720 / 16 = 45, aligned!)
        val (w1, h1) = StreamMath.calculateDimensions(2560, 1440, 720)
        assertEquals(1280, w1)
        assertEquals(720, h1)
        
        // Raw screen is 1920x1080, max allowed is 720
        // Scale = 720 / 1080 = 0.666...
        // New width = 1920 * 0.666... = 1280
        // After alignment: 1280x720
        val (w2, h2) = StreamMath.calculateDimensions(1920, 1080, 720)
        assertEquals(1280, w2)
        assertEquals(720, h2)
    }

    @Test
    fun `test dimensions are not scaled up if smaller than maxHeight`() {
        // Raw screen is 800x480, max allowed is 720
        // Should stay the same (and align to 16)
        val (w, h) = StreamMath.calculateDimensions(800, 480, 720)
        assertEquals(800, w)
        assertEquals(480, h)
    }

    @Test
    fun `test base bitrate calculation scales with pixel count`() {
        // 1280x720 should give exactly 4,000,000
        val bitrate720p = StreamMath.calculateBaseBitrate(1280, 720)
        assertEquals(4_000_000, bitrate720p)
        
        // 1920x1080 has 2.25x the pixels of 1280x720
        // 4,000,000 * 2.25 = 9,000,000
        val bitrate1080p = StreamMath.calculateBaseBitrate(1920, 1080)
        assertEquals(9_000_000, bitrate1080p)
    }

    @Test
    fun `test base bitrate calculation is capped`() {
        // A huge resolution should cap at 15,000,000
        val hugeBitrate = StreamMath.calculateBaseBitrate(3840, 2160) // 4K
        assertEquals(15_000_000, hugeBitrate)
        
        // A tiny resolution should cap at 1,000,000
        val tinyBitrate = StreamMath.calculateBaseBitrate(320, 240)
        assertEquals(1_000_000, tinyBitrate)
    }

    @Test
    fun `test OTT video bitrate boost`() {
        val baseBitrate = 4_000_000
        val boosted = StreamMath.calculateVideoAppBitrate(baseBitrate)
        assertEquals(6_000_000, boosted)
        
        // Ensure boost respects max cap (15Mbps)
        val highBase = 12_000_000
        val cappedBoost = StreamMath.calculateVideoAppBitrate(highBase)
        assertEquals(15_000_000, cappedBoost)
    }

    @Test
    fun `test DPI calculation`() {
        // 720p should give 240 DPI
        assertEquals(240, StreamMath.calculateDpi(720))
        
        // 1080p should give 360 DPI (but capped at 320)
        assertEquals(320, StreamMath.calculateDpi(1080))
        
        // Small screen 480p should give 160 DPI
        assertEquals(160, StreamMath.calculateDpi(480))
    }
}
