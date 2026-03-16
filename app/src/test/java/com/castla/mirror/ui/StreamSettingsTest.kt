package com.castla.mirror.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class StreamSettingsTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        // Clear any previous settings
        context.getSharedPreferences("castla_settings", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun `default settings are correct`() {
        val settings = StreamSettings()
        assertEquals(StreamSettings.Resolution.HD_720, settings.resolution)
        assertEquals(4_000_000, settings.bitrate)
        assertEquals(30, settings.fps)
        assertFalse(settings.audioEnabled)
    }

    @Test
    fun `load returns defaults when no saved settings`() {
        val settings = StreamSettings.load(context)
        assertEquals(StreamSettings.Resolution.HD_720, settings.resolution)
        assertEquals(4_000_000, settings.bitrate)
        assertEquals(30, settings.fps)
        assertFalse(settings.audioEnabled)
    }

    @Test
    fun `save and load round-trips all fields`() {
        val original = StreamSettings(
            resolution = StreamSettings.Resolution.FHD_1080,
            bitrate = 8_000_000,
            fps = 30,
            audioEnabled = true
        )
        StreamSettings.save(context, original)
        val loaded = StreamSettings.load(context)
        assertEquals(original, loaded)
    }

    @Test
    fun `save and load preserves 480p resolution`() {
        val settings = StreamSettings(resolution = StreamSettings.Resolution.SD_480)
        StreamSettings.save(context, settings)
        val loaded = StreamSettings.load(context)
        assertEquals(StreamSettings.Resolution.SD_480, loaded.resolution)
    }

    @Test
    fun `load handles corrupted resolution gracefully`() {
        // Write invalid resolution string directly
        context.getSharedPreferences("castla_settings", Context.MODE_PRIVATE)
            .edit().putString("resolution", "INVALID_RES").commit()
        val loaded = StreamSettings.load(context)
        assertEquals(StreamSettings.Resolution.HD_720, loaded.resolution)
    }

    @Test
    fun `resolution enum has correct dimensions`() {
        val sd = StreamSettings.Resolution.SD_480
        assertEquals(854, sd.width)
        assertEquals(480, sd.height)
        assertEquals("480p", sd.label)

        val hd = StreamSettings.Resolution.HD_720
        assertEquals(1280, hd.width)
        assertEquals(720, hd.height)

        val fhd = StreamSettings.Resolution.FHD_1080
        assertEquals(1920, fhd.width)
        assertEquals(1080, fhd.height)
    }

    @Test
    fun `bitrate options are all valid`() {
        val options = StreamSettings.BITRATE_OPTIONS
        assertEquals(4, options.size)
        assertTrue(options.all { (value, _) -> value > 0 })
        // Verify sorted ascending
        val values = options.map { it.first }
        assertEquals(values.sorted(), values)
    }

    @Test
    fun `fps options are 30 and 60`() {
        assertEquals(listOf(30, 60), StreamSettings.FPS_OPTIONS)
    }

    @Test
    fun `copy preserves unchanged fields`() {
        val original = StreamSettings(
            resolution = StreamSettings.Resolution.FHD_1080,
            bitrate = 12_000_000,
            fps = 30,
            audioEnabled = true
        )
        val modified = original.copy(bitrate = 4_000_000)
        assertEquals(StreamSettings.Resolution.FHD_1080, modified.resolution)
        assertEquals(4_000_000, modified.bitrate)
        assertEquals(30, modified.fps)
        assertTrue(modified.audioEnabled)
    }

    @Test
    fun `overwriting saved settings replaces old values`() {
        val first = StreamSettings(bitrate = 2_000_000, fps = 30)
        StreamSettings.save(context, first)

        val second = StreamSettings(bitrate = 12_000_000, fps = 60)
        StreamSettings.save(context, second)

        val loaded = StreamSettings.load(context)
        assertEquals(12_000_000, loaded.bitrate)
        assertEquals(60, loaded.fps)
    }
}
