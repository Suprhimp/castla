package com.castla.mirror.ui

import android.content.Context
import android.content.SharedPreferences

enum class MirroringMode { FULL_SCREEN, APP }

data class StreamSettings(
    val resolution: Resolution = Resolution.HD_720,
    val bitrate: Int = 2_000_000,
    val fps: Int = 30,
    val audioEnabled: Boolean = false,
    val mirroringMode: MirroringMode = MirroringMode.FULL_SCREEN,
    val targetAppPackage: String = "",
    val targetAppLabel: String = ""
) {
    enum class Resolution(val width: Int, val height: Int, val label: String) {
        AUTO(0, 0, "Auto (Tesla)"),
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
        private const val KEY_MIRRORING_MODE = "mirroring_mode"
        private const val KEY_TARGET_APP_PACKAGE = "target_app_package"
        private const val KEY_TARGET_APP_LABEL = "target_app_label"

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
                audioEnabled = prefs.getBoolean(KEY_AUDIO, false),
                mirroringMode = try {
                    MirroringMode.valueOf(prefs.getString(KEY_MIRRORING_MODE, MirroringMode.FULL_SCREEN.name)!!)
                } catch (_: Exception) { MirroringMode.FULL_SCREEN },
                targetAppPackage = prefs.getString(KEY_TARGET_APP_PACKAGE, "") ?: "",
                targetAppLabel = prefs.getString(KEY_TARGET_APP_LABEL, "") ?: ""
            )
        }

        fun save(context: Context, settings: StreamSettings) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putString(KEY_RESOLUTION, settings.resolution.name)
                .putInt(KEY_BITRATE, settings.bitrate)
                .putInt(KEY_FPS, settings.fps)
                .putBoolean(KEY_AUDIO, settings.audioEnabled)
                .putString(KEY_MIRRORING_MODE, settings.mirroringMode.name)
                .putString(KEY_TARGET_APP_PACKAGE, settings.targetAppPackage)
                .putString(KEY_TARGET_APP_LABEL, settings.targetAppLabel)
                .apply()
        }
    }
}
