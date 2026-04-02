package com.castla.mirror.ui

import android.content.Context
import android.content.SharedPreferences

enum class MirroringMode { FULL_SCREEN, APP }

data class StreamSettings(
    val maxResolution: Resolution = Resolution.AUTO,
    val fps: Int = FPS_AUTO,
    val audioEnabled: Boolean = false,
    val mirroringMode: MirroringMode = MirroringMode.FULL_SCREEN,
    val targetAppPackage: String = "",
    val targetAppLabel: String = "",
    val autoHotspot: Boolean = true
) {
    enum class Resolution(val maxHeight: Int, val label: String) {
        AUTO(720, "Auto"),
        RES_720(720, "720p (Normal)"),
        RES_1080(1080, "1080p (High)");
    }

    /** True when resolution is set to automatic mode */
    val isAutoResolution: Boolean get() = maxResolution == Resolution.AUTO

    /** True when fps is set to automatic mode */
    val isAutoFps: Boolean get() = fps == FPS_AUTO

    companion object {
        private const val PREFS_NAME = "castla_settings"
        private const val KEY_RESOLUTION = "max_resolution"
        private const val KEY_FPS = "fps"
        private const val KEY_AUDIO = "audio"
        private const val KEY_MIRRORING_MODE = "mirroring_mode"
        private const val KEY_TARGET_APP_PACKAGE = "target_app_package"
        private const val KEY_TARGET_APP_LABEL = "target_app_label"
        private const val KEY_AUTO_HOTSPOT = "auto_hotspot"

        /** Sentinel value indicating auto FPS mode. Must not collide with real FPS values. */
        const val FPS_AUTO = 0

        val FPS_OPTIONS = listOf(FPS_AUTO, 30, 60)

        fun load(context: Context): StreamSettings {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val resolution = try {
                Resolution.valueOf(prefs.getString(KEY_RESOLUTION, Resolution.AUTO.name)!!)
            } catch (_: Exception) { Resolution.AUTO }

            val fps = prefs.getInt(KEY_FPS, FPS_AUTO)

            return StreamSettings(
                maxResolution = resolution,
                fps = fps,
                audioEnabled = prefs.getBoolean(KEY_AUDIO, false),
                mirroringMode = try {
                    MirroringMode.valueOf(prefs.getString(KEY_MIRRORING_MODE, MirroringMode.FULL_SCREEN.name)!!)
                } catch (_: Exception) { MirroringMode.FULL_SCREEN },
                targetAppPackage = prefs.getString(KEY_TARGET_APP_PACKAGE, "") ?: "",
                targetAppLabel = prefs.getString(KEY_TARGET_APP_LABEL, "") ?: "",
                autoHotspot = prefs.getBoolean(KEY_AUTO_HOTSPOT, true)
            )
        }

        fun save(context: Context, settings: StreamSettings) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putString(KEY_RESOLUTION, settings.maxResolution.name)
                .putInt(KEY_FPS, settings.fps)
                .putBoolean(KEY_AUDIO, settings.audioEnabled)
                .putString(KEY_MIRRORING_MODE, settings.mirroringMode.name)
                .putString(KEY_TARGET_APP_PACKAGE, settings.targetAppPackage)
                .putString(KEY_TARGET_APP_LABEL, settings.targetAppLabel)
                .putBoolean(KEY_AUTO_HOTSPOT, settings.autoHotspot)
                .apply()
        }
    }
}