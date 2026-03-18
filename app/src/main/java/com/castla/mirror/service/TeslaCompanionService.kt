package com.castla.mirror.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.companion.CompanionDeviceService
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import com.castla.mirror.MainActivity
import com.castla.mirror.R

/**
 * Receives callbacks from the OS when a CDM-associated Tesla Bluetooth device
 * appears or disappears. This allows the app to be woken from the background
 * without requiring background location or NotificationListenerService.
 *
 * The API 33+ overloads (AssociationInfo parameter) are isolated in a nested
 * helper so that the AssociationInfo class reference does not cause a
 * ClassNotFoundException on API <33 when the service is instantiated.
 */
class TeslaCompanionService : CompanionDeviceService() {

    companion object {
        private const val TAG = "TeslaCompanion"
        private const val CHANNEL_ID = "castla_tesla_detect"
        private const val NOTIFICATION_ID = 2001

        /** Action used by the notification's "Start" button to launch mirroring */
        const val ACTION_START_MIRRORING = "com.castla.mirror.ACTION_START_MIRRORING_FROM_CDM"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    // --- API 31 (S) callbacks: String MAC address ---

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onDeviceAppeared(address: String) {
        Log.i(TAG, "Tesla device appeared: $address")
        handleDeviceAppeared()
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onDeviceDisappeared(address: String) {
        Log.i(TAG, "Tesla device disappeared: $address")
        dismissNotification()
    }

    // --- API 33+ (TIRAMISU) callbacks: AssociationInfo ---
    // Isolated behind @RequiresApi so the classloader does not try to resolve
    // android.companion.AssociationInfo on older devices.

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onDeviceAppeared(associationInfo: android.companion.AssociationInfo) {
        Log.i(TAG, "Tesla device appeared: id=${associationInfo.id}")
        handleDeviceAppeared()
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onDeviceDisappeared(associationInfo: android.companion.AssociationInfo) {
        Log.i(TAG, "Tesla device disappeared: id=${associationInfo.id}")
        dismissNotification()
    }

    // --- Shared logic ---

    private fun handleDeviceAppeared() {
        // Don't show notification if MirrorForegroundService is already running
        if (isMirrorServiceRunning()) {
            Log.i(TAG, "Mirror service already running — skipping notification")
            return
        }
        showStartMirroringNotification()
    }

    private fun isMirrorServiceRunning(): Boolean {
        @Suppress("DEPRECATION")
        val manager = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
        for (service in manager.getRunningServices(50)) {
            if (service.service.className == MirrorForegroundService::class.java.name) {
                return true
            }
        }
        return false
    }

    private fun showStartMirroringNotification() {
        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("start_mirroring", true)
        }
        val tapPending = PendingIntent.getActivity(
            this, NOTIFICATION_ID, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(getString(R.string.tesla_detected_title))
            .setContentText(getString(R.string.tesla_detected_text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(tapPending)
            .addAction(
                android.R.drawable.ic_media_play,
                "Start",
                tapPending
            )
            .build()

        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, notification)
        Log.i(TAG, "Start mirroring notification shown")
    }

    private fun dismissNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.cancel(NOTIFICATION_ID)
        Log.i(TAG, "Notification dismissed")
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.tesla_detect_channel),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Notifications when Tesla is detected nearby"
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }
}
