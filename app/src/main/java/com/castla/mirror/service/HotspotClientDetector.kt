package com.castla.mirror.service

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.net.NetworkInterface

/**
 * Detects when a client (e.g. Tesla) connects to the phone's WiFi hotspot.
 *
 * Strategy:
 * 1. Polls /proc/net/arp every few seconds to detect new clients
 * 2. Checks hotspot network interfaces (ap0, wlan1, swlan0, etc.) for activity
 *
 * No special permissions required — ARP table is world-readable on Android.
 */
class HotspotClientDetector(
    @Suppress("unused") private val context: android.content.Context
) {

    companion object {
        private const val TAG = "HotspotClientDetect"
        private const val POLL_INTERVAL_MS = 3_000L
        private const val COOLDOWN_MS = 60_000L

        /** Common hotspot interface name prefixes */
        private val HOTSPOT_IFACE_PREFIXES = listOf("ap", "swlan", "softap", "wlan1")
    }

    private val handler = Handler(Looper.getMainLooper())
    private var onClientConnected: (() -> Unit)? = null
    private var isMonitoring = false
    private var lastNotifyTimeMs = 0L
    private var knownClients = mutableSetOf<String>()

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!isMonitoring) return
            checkForNewClients()
            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    fun start(onDetected: () -> Unit) {
        if (isMonitoring) return
        onClientConnected = onDetected
        isMonitoring = true
        knownClients = getCurrentArpClients().toMutableSet()
        Log.i(TAG, "Started monitoring. Initial ARP clients: ${knownClients.size}")
        handler.postDelayed(pollRunnable, POLL_INTERVAL_MS)
    }

    fun stop() {
        if (!isMonitoring) return
        isMonitoring = false
        handler.removeCallbacks(pollRunnable)
        onClientConnected = null
        knownClients.clear()
        Log.i(TAG, "Stopped monitoring")
    }

    private fun checkForNewClients() {
        // Only trigger if a hotspot interface is active
        if (!isHotspotInterfaceUp()) return

        val current = getCurrentArpClients()
        val newClients = current - knownClients
        knownClients = current.toMutableSet()

        if (newClients.isNotEmpty()) {
            Log.i(TAG, "New hotspot client(s) detected: $newClients")
            notifyIfCooldownPassed()
        }
    }

    private fun notifyIfCooldownPassed() {
        val now = System.currentTimeMillis()
        if (now - lastNotifyTimeMs < COOLDOWN_MS) {
            Log.d(TAG, "Cooldown active, skipping notification")
            return
        }
        lastNotifyTimeMs = now
        onClientConnected?.invoke()
    }

    /**
     * Reads /proc/net/arp to get MAC addresses of connected clients.
     * Format: IP HW_type Flags HW_address Mask Device
     */
    private fun getCurrentArpClients(): Set<String> {
        return try {
            File("/proc/net/arp").readLines()
                .drop(1) // skip header
                .mapNotNull { line ->
                    val parts = line.trim().split("\\s+".toRegex())
                    if (parts.size >= 6) {
                        val mac = parts[3]
                        val iface = parts[5]
                        // Only count clients on hotspot-like interfaces
                        if (mac != "00:00:00:00:00:00" && isHotspotInterface(iface)) {
                            mac.uppercase()
                        } else null
                    } else null
                }
                .toSet()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read ARP table", e)
            emptySet()
        }
    }

    /** Check if any hotspot network interface is up and running */
    private fun isHotspotInterfaceUp(): Boolean {
        return try {
            NetworkInterface.getNetworkInterfaces()?.toList()?.any { iface ->
                iface.isUp && !iface.isLoopback && isHotspotInterface(iface.name)
            } ?: false
        } catch (e: Exception) {
            false
        }
    }

    private fun isHotspotInterface(name: String): Boolean {
        return HOTSPOT_IFACE_PREFIXES.any { name.startsWith(it) }
    }
}
