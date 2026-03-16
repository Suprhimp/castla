package com.castla.mirror.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.Inet4Address
import java.net.NetworkInterface

sealed class NetworkState {
    object Disconnected : NetworkState()
    data class Connected(val ip: String) : NetworkState()
}

class NetworkMonitor(private val context: Context) {

    companion object {
        private const val TAG = "NetworkMonitor"
    }

    private val _state = MutableStateFlow<NetworkState>(NetworkState.Disconnected)
    val state: StateFlow<NetworkState> = _state

    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val ip = getDeviceIp()
            _state.value = NetworkState.Connected(ip)
            Log.i(TAG, "Network available: $ip")
        }

        override fun onLost(network: Network) {
            _state.value = NetworkState.Disconnected
            Log.i(TAG, "Network lost")
        }
    }

    fun startMonitoring() {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            .build()
        connectivityManager.registerNetworkCallback(request, networkCallback)

        // Set initial state — also covers hotspot mode where callback may not fire
        val ip = getDeviceIp()
        if (ip != "0.0.0.0") {
            _state.value = NetworkState.Connected(ip)
            Log.i(TAG, "Initial IP: $ip")
        }
    }

    fun stopMonitoring() {
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (_: Exception) {}
    }

    private fun getDeviceIp(): String {
        try {
            val allInterfaces = NetworkInterface.getNetworkInterfaces()?.toList() ?: return "0.0.0.0"

            // Log ALL interfaces for debugging
            for (iface in allInterfaces) {
                if (!iface.isUp) continue
                val addrs = iface.inetAddresses.toList()
                    .filterIsInstance<Inet4Address>()
                    .map { it.hostAddress }
                if (addrs.isNotEmpty()) {
                    Log.i(TAG, "Interface: ${iface.name} IPs: $addrs loopback=${iface.isLoopback}")
                }
            }

            // Collect all candidate IPs with their priority
            data class Candidate(val ip: String, val iface: String, val priority: Int)
            val candidates = mutableListOf<Candidate>()

            for (iface in allInterfaces) {
                if (iface.isLoopback || !iface.isUp) continue
                val name = iface.name.lowercase()

                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (address is Inet4Address && !address.isLoopbackAddress) {
                        val ip = address.hostAddress ?: continue

                        val priority = when {
                            // Hotspot gateway IPs — highest priority (reachable by hotspot clients)
                            ip.startsWith("192.168.43.") || ip.startsWith("192.168.49.") -> 20
                            // Other private IPs on known hotspot interfaces
                            (name.startsWith("swlan") || name.startsWith("ap") || name.startsWith("softap")) -> 15
                            // wlan with private IP (WiFi connected to router)
                            name.startsWith("wlan") && ip.startsWith("192.168.") -> 10
                            name.startsWith("wlan") && ip.startsWith("10.") -> 5
                            name.startsWith("eth") -> 3
                            // Everything else (likely mobile data) — low priority
                            else -> 1
                        }

                        Log.d(TAG, "Candidate: $ip on ${iface.name} priority=$priority")
                        candidates.add(Candidate(ip, iface.name, priority))
                    }
                }
            }

            val best = candidates.maxByOrNull { it.priority }
            if (best != null) {
                Log.i(TAG, "Selected IP ${best.ip} on ${best.iface} (priority=${best.priority})")
                return best.ip
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get device IP", e)
        }
        return "0.0.0.0"
    }
}
