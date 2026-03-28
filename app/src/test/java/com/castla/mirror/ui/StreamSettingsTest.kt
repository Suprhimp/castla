package com.castla.mirror.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.castla.mirror.billing.LicenseManager
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
        // Default maxResolution is RES_1080 (assuming premium, or standard default object value)
        assertEquals(StreamSettings.Resolution.RES_1080, settings.maxResolution)
        assertEquals(30, settings.fps)
        assertTrue(settings.audioEnabled)
    }

    // Since LicenseManager checks premium status in load(), if not premium, it defaults to 720
    @Test
    fun `load returns 720p when no saved settings and not premium`() {
        val settings = StreamSettings.load(context)
        assertEquals(StreamSettings.Resolution.RES_720, settings.maxResolution)
        assertEquals(30, settings.fps)
        assertTrue(settings.audioEnabled)
    }

    @Test
    fun `save and load round-trips all fields`() {
        val original = StreamSettings(
            maxResolution = StreamSettings.Resolution.RES_720,
            fps = 30,
            audioEnabled = false
        )
        StreamSettings.save(context, original)
        val loaded = StreamSettings.load(context)
        assertEquals(original, loaded)
    }

    @Test
    fun `load handles corrupted resolution gracefully`() {
        context.getSharedPreferences("castla_settings", Context.MODE_PRIVATE)
            .edit().putString("max_resolution", "INVALID_RES").commit()
        val loaded = StreamSettings.load(context)
        // If not premium, it will clamp to 720p anyway
        assertEquals(StreamSettings.Resolution.RES_720, loaded.maxResolution)
    }

    @Test
    fun `resolution enum has correct dimensions`() {
        val hd = StreamSettings.Resolution.RES_720
        assertEquals(720, hd.maxHeight)
        assertTrue(hd.label.contains("720p"))

        val fhd = StreamSettings.Resolution.RES_1080
        assertEquals(1080, fhd.maxHeight)
        assertTrue(fhd.label.contains("1080p"))
    }

    @Test
    fun `fps options are 30 and 60`() {
        assertEquals(listOf(30, 60), StreamSettings.FPS_OPTIONS)
    }

    @Test
    fun `copy preserves unchanged fields`() {
        val original = StreamSettings(
            maxResolution = StreamSettings.Resolution.RES_720,
            fps = 30,
            audioEnabled = true
        )
        val modified = original.copy(fps = 60)
        assertEquals(StreamSettings.Resolution.RES_720, modified.maxResolution)
        assertEquals(60, modified.fps)
        assertTrue(modified.audioEnabled)
    }
}