package com.jakarta.mirror.network

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log

/**
 * Manages WiFi hotspot for Tesla connection.
 * Uses LocalOnlyHotspot API (Android 8.0+) for basic hotspot,
 * or Shizuku for full tethering control (Phase 5).
 */
class HotspotManager(private val context: Context) {

    companion object {
        private const val TAG = "HotspotManager"
    }

    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private var hotspotReservation: WifiManager.LocalOnlyHotspotReservation? = null

    data class HotspotInfo(
        val ssid: String,
        val password: String,
        val isActive: Boolean
    )

    fun startHotspot(callback: (HotspotInfo?) -> Unit) {
        try {
            wifiManager.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation) {
                    hotspotReservation = reservation
                    val config = reservation.wifiConfiguration
                    val info = HotspotInfo(
                        ssid = config?.SSID ?: "Unknown",
                        password = config?.preSharedKey ?: "",
                        isActive = true
                    )
                    Log.i(TAG, "Hotspot started: ${info.ssid}")
                    callback(info)
                }

                override fun onStopped() {
                    Log.i(TAG, "Hotspot stopped")
                    hotspotReservation = null
                    callback(null)
                }

                override fun onFailed(reason: Int) {
                    Log.e(TAG, "Hotspot failed: reason=$reason")
                    callback(null)
                }
            }, null)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start hotspot", e)
            callback(null)
        }
    }

    fun stopHotspot() {
        hotspotReservation?.close()
        hotspotReservation = null
        Log.i(TAG, "Hotspot stopped")
    }
}
