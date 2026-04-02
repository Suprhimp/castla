package com.castla.mirror.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
        // 1280x720 = 921600 pixels (base), should give exactly 4,000,000
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
    fun `test secondary bitrate uses lower base and ceiling`() {
        // 1280x720: 3M * 1.0 = 3,000,000
        val secondary720p = StreamMath.calculateSecondaryBitrate(1280, 720)
        assertEquals(3_000_000, secondary720p)

        // 1920x1080: 3M * 2.25 = 6,750,000
        val secondary1080p = StreamMath.calculateSecondaryBitrate(1920, 1080)
        assertEquals(6_750_000, secondary1080p)

        // Tiny resolution: floor at 750,000
        val secondaryTiny = StreamMath.calculateSecondaryBitrate(320, 240)
        assertEquals(750_000, secondaryTiny)

        // Huge resolution: ceiling at 10,000,000
        val secondaryHuge = StreamMath.calculateSecondaryBitrate(3840, 2160)
        assertEquals(10_000_000, secondaryHuge)
    }

    @Test
    fun `test OTT bitrate boost`() {
        val baseBitrate = 4_000_000
        val boosted = StreamMath.calculateOttBitrate(baseBitrate)
        // 4M * 1.2 = 4,800,000
        assertEquals(4_800_000, boosted)

        // Ensure boost respects max cap (15Mbps)
        val highBase = 14_000_000
        val cappedBoost = StreamMath.calculateOttBitrate(highBase)
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

    // ── Display density scale tests ──

    @Test
    fun `test density scale default is 0_85`() {
        assertEquals(0.85f, StreamMath.DENSITY_SCALE_DEFAULT)
    }

    @Test
    fun `test density scale options are ordered large to compact`() {
        val options = StreamMath.DENSITY_SCALE_OPTIONS
        assertEquals(listOf(1.0f, 0.85f, 0.7f, 0.55f), options)
        // Each successive option should be smaller
        for (i in 1 until options.size) {
            assertTrue(options[i] < options[i - 1])
        }
    }

    @Test
    fun `test applyDensityScale at default`() {
        // 720p base DPI = 240, default scale = 0.85
        // 240 * 0.85 = 204
        assertEquals(204, StreamMath.applyDensityScale(240, 0.85f))
    }

    @Test
    fun `test applyDensityScale at large (no scaling)`() {
        // 720p base DPI = 240, scale = 1.0 → unchanged
        assertEquals(240, StreamMath.applyDensityScale(240, 1.0f))
    }

    @Test
    fun `test applyDensityScale at compact levels`() {
        // 240 * 0.7 = 168
        assertEquals(168, StreamMath.applyDensityScale(240, 0.7f))
        // 240 * 0.55 = 132
        assertEquals(132, StreamMath.applyDensityScale(240, 0.55f))
    }

    @Test
    fun `test applyDensityScale is clamped`() {
        // Floor: very small base DPI with compact scale should clamp to 100
        // 120 * 0.55 = 66 → clamped to 100
        assertEquals(100, StreamMath.applyDensityScale(120, 0.55f))

        // Ceiling: high base DPI at large scale should clamp to 320
        // 320 * 1.0 = 320 (exactly at cap)
        assertEquals(320, StreamMath.applyDensityScale(320, 1.0f))
    }
}
