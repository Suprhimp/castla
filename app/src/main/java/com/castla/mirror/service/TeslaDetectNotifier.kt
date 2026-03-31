package com.castla.mirror.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.castla.mirror.MainActivity
import com.castla.mirror.R

/**
 * Shared notification logic for Tesla detection — used by both
 * CompanionDeviceManager (CDM) and BLE scanner paths.
 */
object TeslaDetectNotifier {

    private const val TAG = "TeslaDetectNotifier"
    const val CHANNEL_ID = "castla_tesla_detect"
    const val NOTIFICATION_ID = 2001

    fun ensureChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.tesla_detect_channel),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Notifications when Tesla is detected nearby"
        }
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    fun showTeslaDetectedNotification(context: Context) {
        if (MirrorForegroundService.isServiceRunning) {
            Log.i(TAG, "Mirror service already running — skipping notification")
            return
        }

        ensureChannel(context)

        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("start_mirroring", true)
        }
        val tapPending = PendingIntent.getActivity(
            context, NOTIFICATION_ID, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(context.getString(R.string.tesla_detected_title))
            .setContentText(context.getString(R.string.tesla_detected_text))
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

        val nm = context.getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, notification)
        Log.i(TAG, "Tesla detected notification shown")
    }

    fun dismiss(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.cancel(NOTIFICATION_ID)
        Log.i(TAG, "Notification dismissed")
    }
}
