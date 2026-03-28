package com.castla.mirror.ui

import android.content.Context
import android.content.SharedPreferences
import com.castla.mirror.billing.LicenseManager

enum class MirroringMode { FULL_SCREEN, APP }

data class StreamSettings(
    val maxResolution: Resolution = Resolution.RES_1080,
    val fps: Int = 30,
    val audioEnabled: Boolean = true,
    val mirroringMode: MirroringMode = MirroringMode.FULL_SCREEN,
    val targetAppPackage: String = "",
    val targetAppLabel: String = ""
) {
    enum class Resolution(val maxHeight: Int, val label: String) {
        RES_720(720, "720p (Normal)"),
        RES_1080(1080, "1080p (High)");
    }

    companion object {
        private const val PREFS_NAME = "castla_settings"
        private const val KEY_RESOLUTION = "max_resolution"
        private const val KEY_FPS = "fps"
        private const val KEY_AUDIO = "audio"
        private const val KEY_MIRRORING_MODE = "mirroring_mode"
        private const val KEY_TARGET_APP_PACKAGE = "target_app_package"
        private const val KEY_TARGET_APP_LABEL = "target_app_label"

        val FPS_OPTIONS = listOf(30, 60)

        fun load(context: Context): StreamSettings {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val loadedResolution = try {
                Resolution.valueOf(prefs.getString(KEY_RESOLUTION, Resolution.RES_1080.name)!!)
            } catch (_: Exception) { Resolution.RES_1080 }
            
            // Auto-select 720p if not premium
            val resolution = if (!LicenseManager.isPremiumNow) {
                Resolution.RES_720
            } else {
                loadedResolution
            }
            
            // Clamp FPS for free users
            val loadedFps = prefs.getInt(KEY_FPS, 30)
            val fps = if (!LicenseManager.isPremiumNow && loadedFps > 30) 30 else loadedFps

            return StreamSettings(
                maxResolution = resolution,
                fps = fps,
                audioEnabled = prefs.getBoolean(KEY_AUDIO, true),
                mirroringMode = try {
                    MirroringMode.valueOf(prefs.getString(KEY_MIRRORING_MODE, MirroringMode.FULL_SCREEN.name)!!)
                } catch (_: Exception) { MirroringMode.FULL_SCREEN },
                targetAppPackage = prefs.getString(KEY_TARGET_APP_PACKAGE, "") ?: "",
                targetAppLabel = prefs.getString(KEY_TARGET_APP_LABEL, "") ?: ""
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
                .apply()
        }
    }
}