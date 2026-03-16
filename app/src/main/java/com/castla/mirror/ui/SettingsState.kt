package com.castla.mirror.ui

import android.content.Context
import android.content.SharedPreferences

data class StreamSettings(
    val resolution: Resolution = Resolution.HD_720,
    val bitrate: Int = 2_000_000,
    val fps: Int = 30,
    val audioEnabled: Boolean = false
) {
    enum class Resolution(val width: Int, val height: Int, val label: String) {
        SD_480(854, 480, "480p"),
        HD_720(1280, 720, "720p"),
        FHD_1080(1920, 1080, "1080p");
    }

    companion object {
        private const val PREFS_NAME = "castla_settings"
        private const val KEY_RESOLUTION = "resolution"
        private const val KEY_BITRATE = "bitrate"
        private const val KEY_FPS = "fps"
        private const val KEY_AUDIO = "audio"

        val BITRATE_OPTIONS = listOf(
            2_000_000 to "2 Mbps",
            4_000_000 to "4 Mbps",
            8_000_000 to "8 Mbps",
            12_000_000 to "12 Mbps"
        )

        val FPS_OPTIONS = listOf(30, 60)

        fun load(context: Context): StreamSettings {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return StreamSettings(
                resolution = try {
                    Resolution.valueOf(prefs.getString(KEY_RESOLUTION, Resolution.HD_720.name)!!)
                } catch (_: Exception) { Resolution.HD_720 },
                bitrate = prefs.getInt(KEY_BITRATE, 2_000_000),
                fps = prefs.getInt(KEY_FPS, 30),
                audioEnabled = prefs.getBoolean(KEY_AUDIO, false)
            )
        }

        fun save(context: Context, settings: StreamSettings) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putString(KEY_RESOLUTION, settings.resolution.name)
                .putInt(KEY_BITRATE, settings.bitrate)
                .putInt(KEY_FPS, settings.fps)
                .putBoolean(KEY_AUDIO, settings.audioEnabled)
                .apply()
        }
    }
}
