package com.castla.mirror.update

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable

/**
 * Each product flavor provides its own implementation.
 * - playstore  → Google Play In-App Updates (IMMEDIATE)
 * - standalone → Remote JSON version check
 */
interface UpdateManager {

    /** Call once in onCreate. Checks for updates and blocks UI if mandatory. */
    fun checkForUpdate(activity: ComponentActivity)

    /** Forward onResume so Play Store flow can re-check stalled updates. */
    fun onResume(activity: ComponentActivity) {}

    /** Clean up resources. */
    fun destroy() {}

    /**
     * Composable that renders a force-update dialog if needed.
     * PlayStore flavor: no-op (Play handles the UI).
     * Standalone flavor: shows a blocking dialog with download button.
     */
    @Composable
    fun ForceUpdateOverlay(activity: ComponentActivity) {}
}
