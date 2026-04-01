package com.castla.mirror.update

import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Standalone flavor: checks a remote JSON endpoint for the latest version.
 *
 * Expected JSON format at VERSION_CHECK_URL:
 * {
 *   "latest_version": "1.2.0",
 *   "latest_version_code": 3,
 *   "min_version_code": 2,
 *   "download_url": "https://your-site.com/download"
 * }
 *
 * - min_version_code: if current versionCode < this, force update
 * - download_url: where the user goes to download the new APK
 */
class StandaloneUpdateManager : UpdateManager {

    companion object {
        private const val TAG = "StandaloneUpdate"
        // TODO: Replace with your actual version check endpoint
        private const val VERSION_CHECK_URL = "https://castla.app/api/version.json"
        private const val CONNECT_TIMEOUT_MS = 5000
        private const val READ_TIMEOUT_MS = 5000
    }

    private val showForceUpdate = mutableStateOf(false)
    private val latestVersionName = mutableStateOf("")
    private val downloadUrl = mutableStateOf("")

    override fun checkForUpdate(activity: ComponentActivity) {
        activity.lifecycleScope.launch {
            try {
                val result = fetchVersionInfo()
                if (result != null) {
                    val currentVersionCode = activity.packageManager
                        .getPackageInfo(activity.packageName, 0)
                        .let {
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                                it.longVersionCode.toInt()
                            } else {
                                @Suppress("DEPRECATION")
                                it.versionCode
                            }
                        }

                    val minVersionCode = result.optInt("min_version_code", 0)
                    if (currentVersionCode < minVersionCode) {
                        Log.i(TAG, "Force update required: current=$currentVersionCode, min=$minVersionCode")
                        latestVersionName.value = result.optString("latest_version", "")
                        downloadUrl.value = result.optString("download_url", "")
                        showForceUpdate.value = true
                    } else {
                        Log.i(TAG, "App is up to date: current=$currentVersionCode, min=$minVersionCode")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Version check failed, skipping", e)
            }
        }
    }

    @Composable
    override fun ForceUpdateOverlay(activity: ComponentActivity) {
        if (showForceUpdate.value) {
            ForceUpdateDialog(
                latestVersion = latestVersionName.value,
                onUpdate = {
                    val url = downloadUrl.value.ifEmpty { "https://castla.app" }
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    activity.startActivity(intent)
                }
            )
        }
    }

    private suspend fun fetchVersionInfo(): JSONObject? = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(VERSION_CHECK_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                requestMethod = "GET"
            }
            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val body = connection.inputStream.bufferedReader().readText()
                JSONObject(body)
            } else {
                Log.w(TAG, "Version check HTTP ${connection.responseCode}")
                null
            }
        } finally {
            connection?.disconnect()
        }
    }
}
