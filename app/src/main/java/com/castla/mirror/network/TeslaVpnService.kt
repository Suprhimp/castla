package com.castla.mirror.network

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.io.FileInputStream

/**
 * VPN that assigns 100.99.9.9 to a TUN interface for Tesla browser access.
 *
 * Tesla blocks RFC1918 IPs. This VPN creates tun0 with a CGNAT IP.
 * The kernel's local routing table delivers external packets (from Tesla via swlan0)
 * directly to the TCP stack. NanoHTTPD on 0.0.0.0:8080 handles them.
 *
 * A lightweight health-check server starts immediately so connectivity can be tested
 * before the full MirrorServer pipeline begins.
 */
class TeslaVpnService : VpnService() {

    companion object {
        private const val TAG = "TeslaVpn"
        const val VIRTUAL_IP = "100.99.9.9"

        /** Stop the health-check server (call before starting MirrorServer on same port). */
        fun stopHealthServer() {
            healthServerInstance?.let {
                try { it.stop() } catch (_: Exception) {}
                Log.i(TAG, "Health server stopped (port freed for MirrorServer)")
            }
            healthServerInstance = null
        }

        @Volatile
        private var healthServerInstance: HealthCheckServer? = null
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var drainThread: Thread? = null
    private var healthServer: HealthCheckServer? = null
    @Volatile
    private var running = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (vpnInterface != null) {
            Log.i(TAG, "VPN already running")
            return START_STICKY
        }

        try {
            // addAddress assigns IP to tun0 and adds to kernel local routing table (priority 0).
            // External packets to 100.99.9.9 → kernel local delivery → TCP stack → NanoHTTPD.
            // No addRoute needed — local table handles everything.
            val builder = Builder()
                .setSession("Castla")
                .addAddress(VIRTUAL_IP, 32)
                .setMtu(1500)
                .setBlocking(true)
                .allowBypass()

            // Exclude our app so NanoHTTPD responses route via real network (swlan0)
            try {
                builder.addDisallowedApplication(packageName)
            } catch (e: Exception) {
                Log.w(TAG, "Could not exclude app from VPN", e)
            }

            vpnInterface = builder.establish()

            if (vpnInterface == null) {
                Log.e(TAG, "Failed to establish VPN interface")
                stopSelf()
                return START_NOT_STICKY
            }

            Log.i(TAG, "VPN established: $VIRTUAL_IP on tun0")

            // Start health-check HTTP server so connectivity can be tested immediately
            startHealthServer()

            // Drain TUN fd to keep interface alive (no packets expected here)
            running = true
            drainThread = Thread({
                drainTun()
            }, "vpn-drain").apply { isDaemon = true; start() }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start VPN", e)
            stopSelf()
        }

        return START_STICKY
    }

    private fun startHealthServer() {
        try {
            val server = HealthCheckServer()
            server.start()
            healthServer = server
            healthServerInstance = server
            Log.i(TAG, "Health-check server on :8080")
        } catch (e: Exception) {
            Log.w(TAG, "Health server failed: ${e.message}")
        }
    }

    private fun drainTun() {
        val fd = vpnInterface?.fileDescriptor ?: return
        val input = FileInputStream(fd)
        val buffer = ByteArray(32767)
        var count = 0

        try {
            while (running) {
                val n = input.read(buffer)
                if (n < 0) break
                count++
                if (count <= 5) {
                    val proto = if (n > 9) (buffer[9].toInt() and 0xFF) else -1
                    Log.d(TAG, "TUN pkt #$count len=$n proto=$proto")
                }
            }
        } catch (e: Exception) {
            if (running) Log.w(TAG, "TUN read error", e)
        }
        Log.i(TAG, "Drain exited (pkts=$count)")
    }

    override fun onDestroy() {
        running = false
        try { healthServer?.stop() } catch (_: Exception) {}
        healthServer = null
        try { vpnInterface?.close() } catch (_: Exception) {}
        vpnInterface = null
        drainThread?.interrupt()
        drainThread = null
        Log.i(TAG, "VPN stopped")
        super.onDestroy()
    }

    override fun onRevoke() {
        Log.i(TAG, "VPN revoked by system")
        stopSelf()
    }

    /**
     * Lightweight NanoHTTPD that responds to GET / with a simple page.
     * Proves that 100.99.9.9:8080 is reachable before MirrorServer takes over.
     * MirrorServer will stop this when it starts on the same port.
     */
    class HealthCheckServer : NanoHTTPD(8080) {
        override fun serve(session: IHTTPSession): Response {
            Log.i("TeslaVpn", "Health check hit: ${session.method} ${session.uri} from ${session.remoteIpAddress}")
            val html = """
                <!DOCTYPE html>
                <html><head><meta charset="utf-8"><title>Castla</title>
                <style>body{background:#111;color:#0f0;font-family:monospace;display:flex;
                justify-content:center;align-items:center;height:100vh;margin:0}
                div{text-align:center}h1{font-size:3em}</style></head>
                <body><div><h1>&#x2713; Castla Ready</h1>
                <p>VPN IP: $VIRTUAL_IP</p>
                <p>Server is running. Start mirroring to begin.</p></div></body></html>
            """.trimIndent()
            return newFixedLengthResponse(Response.Status.OK, "text/html", html)
        }
    }
}
