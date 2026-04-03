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
    fun `default settings are Auto`() {
        val settings = StreamSettings()
        assertEquals(StreamSettings.Resolution.AUTO, settings.maxResolution)
        assertEquals(StreamSettings.FPS_AUTO, settings.fps)
        assertTrue(settings.isAutoResolution)
        assertTrue(settings.isAutoFps)
        assertFalse(settings.audioEnabled)
        assertFalse(settings.muteLocalAudio)
    }

    @Test
    fun `load returns Auto defaults when no saved settings`() {
        val settings = StreamSettings.load(context)
        assertEquals(StreamSettings.Resolution.AUTO, settings.maxResolution)
        assertEquals(StreamSettings.FPS_AUTO, settings.fps)
        assertTrue(settings.isAutoResolution)
        assertTrue(settings.isAutoFps)
        assertFalse(settings.audioEnabled)
        assertFalse(settings.muteLocalAudio)
    }

    @Test
    fun `save and load round-trips all fields`() {
        val original = StreamSettings(
            maxResolution = StreamSettings.Resolution.RES_1080,
            fps = 60,
            audioEnabled = true
        )
        StreamSettings.save(context, original)
        val loaded = StreamSettings.load(context)
        assertEquals(original, loaded)
    }

    @Test
    fun `save and load round-trips Auto settings`() {
        val original = StreamSettings(
            maxResolution = StreamSettings.Resolution.AUTO,
            fps = StreamSettings.FPS_AUTO,
            audioEnabled = false
        )
        StreamSettings.save(context, original)
        val loaded = StreamSettings.load(context)
        assertEquals(original, loaded)
        assertTrue(loaded.isAutoResolution)
        assertTrue(loaded.isAutoFps)
    }

    @Test
    fun `load handles corrupted resolution gracefully`() {
        context.getSharedPreferences("castla_settings", Context.MODE_PRIVATE)
            .edit().putString("max_resolution", "INVALID_RES").commit()
        val loaded = StreamSettings.load(context)
        assertEquals(StreamSettings.Resolution.AUTO, loaded.maxResolution)
    }

    @Test
    fun `resolution enum has correct dimensions`() {
        val auto = StreamSettings.Resolution.AUTO
        assertEquals(720, auto.maxHeight) // AUTO starts at 720p
        assertTrue(auto.label.contains("Auto"))

        val hd = StreamSettings.Resolution.RES_720
        assertEquals(720, hd.maxHeight)
        assertTrue(hd.label.contains("720p"))

        val fhd = StreamSettings.Resolution.RES_1080
        assertEquals(1080, fhd.maxHeight)
        assertTrue(fhd.label.contains("1080p"))
    }

    @Test
    fun `fps options include Auto, 30, and 60`() {
        assertEquals(listOf(StreamSettings.FPS_AUTO, 30, 60), StreamSettings.FPS_OPTIONS)
        assertEquals(0, StreamSettings.FPS_AUTO)
    }

    @Test
    fun `isAutoResolution and isAutoFps reflect manual values`() {
        val manual = StreamSettings(
            maxResolution = StreamSettings.Resolution.RES_720,
            fps = 30
        )
        assertFalse(manual.isAutoResolution)
        assertFalse(manual.isAutoFps)
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

    @Test
    fun `1080p and 60fps are accessible without restriction`() {
        val settings = StreamSettings(
            maxResolution = StreamSettings.Resolution.RES_1080,
            fps = 60,
            audioEnabled = false
        )
        StreamSettings.save(context, settings)
        val loaded = StreamSettings.load(context)
        assertEquals(StreamSettings.Resolution.RES_1080, loaded.maxResolution)
        assertEquals(60, loaded.fps)
    }
}
