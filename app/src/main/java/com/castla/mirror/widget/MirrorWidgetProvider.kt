package com.castla.mirror.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.RemoteViews
import com.castla.mirror.MainActivity
import com.castla.mirror.R
import com.castla.mirror.service.MirrorForegroundService
import rikka.shizuku.Shizuku

class MirrorWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_TOGGLE = "com.castla.mirror.WIDGET_TOGGLE"
        private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

        fun updateAllWidgets(context: Context) {
            val intent = Intent(context, MirrorWidgetProvider::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
            }
            val ids = AppWidgetManager.getInstance(context)
                .getAppWidgetIds(ComponentName(context, MirrorWidgetProvider::class.java))
            intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            context.sendBroadcast(intent)
        }
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (widgetId in appWidgetIds) {
            updateWidget(context, appWidgetManager, widgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_TOGGLE) {
            val isStreaming = isMirrorServiceRunning(context)
            if (isStreaming) {
                // Stop mirroring
                context.stopService(Intent(context, MirrorForegroundService::class.java))
            } else {
                // Open MainActivity to start mirroring (needs MediaProjection permission)
                val launchIntent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra("start_mirroring", true)
                }
                context.startActivity(launchIntent)
            }
            // Widget will be updated by service start/stop callbacks
        }
    }

    private fun updateWidget(
        context: Context,
        appWidgetManager: AppWidgetManager,
        widgetId: Int
    ) {
        val views = RemoteViews(context.packageName, R.layout.widget_mirror)
        val isStreaming = isMirrorServiceRunning(context)
        val shizukuStatus = getShizukuStatus(context)

        // Mirror toggle button
        val toggleIntent = Intent(context, MirrorWidgetProvider::class.java).apply {
            action = ACTION_TOGGLE
        }
        val togglePending = PendingIntent.getBroadcast(
            context, 0, toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.btn_toggle, togglePending)

        // Update toggle button text and appearance
        if (isStreaming) {
            views.setTextViewText(R.id.btn_toggle, "Stop")
            views.setInt(R.id.btn_toggle, "setBackgroundResource", R.drawable.widget_btn_stop)
        } else {
            views.setTextViewText(R.id.btn_toggle, "Start")
            views.setInt(R.id.btn_toggle, "setBackgroundResource", R.drawable.widget_btn_start)
        }

        // Status text
        views.setTextViewText(
            R.id.tv_mirror_status,
            if (isStreaming) "Mirroring ON" else "Mirroring OFF"
        )
        views.setTextColor(
            R.id.tv_mirror_status,
            if (isStreaming) 0xFF4CAF50.toInt() else 0xFF9E9E9E.toInt()
        )

        // Shizuku status
        val (shizukuText, shizukuColor) = when (shizukuStatus) {
            ShizukuStatus.RUNNING -> "Shizuku: Running" to 0xFF4CAF50.toInt()
            ShizukuStatus.NOT_RUNNING -> "Shizuku: Not Running" to 0xFFFF9800.toInt()
            ShizukuStatus.NOT_INSTALLED -> "Shizuku: Not Installed" to 0xFFF44336.toInt()
        }
        views.setTextViewText(R.id.tv_shizuku_status, shizukuText)
        views.setTextColor(R.id.tv_shizuku_status, shizukuColor)

        // Tap Shizuku status → open Shizuku app or MainActivity
        val shizukuIntent = if (shizukuStatus == ShizukuStatus.NOT_INSTALLED) {
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        } else {
            context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
                ?: Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
        }
        val shizukuPending = PendingIntent.getActivity(
            context, 2, shizukuIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.tv_shizuku_status, shizukuPending)

        appWidgetManager.updateAppWidget(widgetId, views)
    }

    private fun isMirrorServiceRunning(context: Context): Boolean {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        @Suppress("DEPRECATION")
        for (service in manager.getRunningServices(100)) {
            if (service.service.className == MirrorForegroundService::class.java.name) {
                return true
            }
        }
        return false
    }

    private fun getShizukuStatus(context: Context): ShizukuStatus {
        return try {
            context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
            // Installed — check if running
            if (Shizuku.pingBinder()) ShizukuStatus.RUNNING else ShizukuStatus.NOT_RUNNING
        } catch (_: PackageManager.NameNotFoundException) {
            ShizukuStatus.NOT_INSTALLED
        } catch (_: Exception) {
            ShizukuStatus.NOT_RUNNING
        }
    }

    private enum class ShizukuStatus {
        RUNNING, NOT_RUNNING, NOT_INSTALLED
    }
}
