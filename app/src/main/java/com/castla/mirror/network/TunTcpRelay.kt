package com.castla.mirror.network

import android.util.Log
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * Userspace TCP relay: reads raw IP/TCP packets from a TUN file descriptor,
 * completes TCP handshakes in userspace, and proxies payload to a local server.
 *
 * Android VPN TUN devices deliver packets to the VPN fd as raw bytes, NOT to the
 * kernel TCP stack. This relay acts as a transparent TCP proxy:
 *   TUN fd ←→ [TCP state machine] ←→ Socket to 127.0.0.1:localPort (NanoHTTPD)
 */
class TunTcpRelay(
    tunFd: FileDescriptor,
    private val localPort: Int = 9090
) {
    companion object {
        private const val TAG = "TunTcpRelay"

        // TCP flags
        private const val FIN = 0x01
        private const val SYN = 0x02
        private const val RST = 0x04
        private const val PSH = 0x08
        private const val ACK = 0x10

        private const val PROTO_TCP = 6
        private const val MSS = 1400
        private const val WINDOW = 65535
    }

    private val tunIn = FileInputStream(tunFd)
    private val tunOut = FileOutputStream(tunFd)
    private val writeLock = Any()
    private val relayThreadCounter = AtomicInteger(0)
    private val relayExecutor: ExecutorService = Executors.newFixedThreadPool(8) { r ->
        Thread(r, "relay-pool-${relayThreadCounter.incrementAndGet()}").apply { isDaemon = true }
    }

    @Volatile
    var running = true

    // --- Connection tracking ---

    data class Key(val srcIp: Int, val srcPort: Int, val dstIp: Int, val dstPort: Int)

    class Session(
        val key: Key,
        var state: State = State.SYN_RCVD,
        var clientNextSeq: Long = 0,
        var serverSeq: Long = 0,
        var localSocket: Socket? = null,
        @Volatile var active: Boolean = true
    ) {
        enum class State { SYN_RCVD, ESTABLISHED, FIN_WAIT, CLOSED }
    }

    private val sessions = ConcurrentHashMap<Key, Session>()

    /** Main loop — call from a dedicated thread. */
    fun run() {
        Log.i(TAG, "TCP relay started → 127.0.0.1:$localPort")
        val buf = ByteArray(32767)
        try {
            while (running) {
                val n = tunIn.read(buf)
                if (n < 0) break
                if (n >= 40) {
                    try {
                        processPacket(buf, n)
                    } catch (e: Exception) {
                        Log.w(TAG, "Packet error: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            if (running) Log.w(TAG, "TUN read error", e)
        }
        Log.i(TAG, "TCP relay stopped (sessions=${sessions.size})")
        cleanup()
    }

    fun stop() {
        running = false
        relayExecutor.shutdownNow()
        cleanup()
    }

    private fun cleanup() {
        for (s in sessions.values) {
            s.active = false
            try { s.localSocket?.close() } catch (_: Exception) {}
        }
        sessions.clear()
    }

    // --- Packet processing ---

    private fun processPacket(buf: ByteArray, len: Int) {
        val ver = (buf[0].toInt() ushr 4) and 0xF
        if (ver != 4) return
        val ihl = (buf[0].toInt() and 0xF) * 4
        if (ihl < 20 || ihl > len) return
        val ipTotalLen = u16(buf, 2)
        if (buf[9].toInt() and 0xFF != PROTO_TCP) return

        val srcIp = i32(buf, 12)
        val dstIp = i32(buf, 16)

        val t = ihl
        if (t + 20 > len) return
        val srcPort = u16(buf, t)
        val dstPort = u16(buf, t + 2)
        val seq = u32(buf, t + 4)
        val ackNum = u32(buf, t + 8)
        val dataOff = ((buf[t + 12].toInt() ushr 4) and 0xF) * 4
        val flags = buf[t + 13].toInt() and 0x3F
        val payStart = t + dataOff
        val payLen = minOf(ipTotalLen - ihl - dataOff, len - payStart).coerceAtLeast(0)

        val key = Key(srcIp, srcPort, dstIp, dstPort)

        when {
            flags and RST != 0 -> {
                sessions.remove(key)?.let {
                    it.active = false
                    try { it.localSocket?.close() } catch (_: Exception) {}
                }
            }
            flags and SYN != 0 && flags and ACK == 0 -> handleSyn(key, seq)
            flags and FIN != 0 -> handleFin(key, seq)
            flags and ACK != 0 -> {
                val s = sessions[key] ?: return
                if (s.state == Session.State.SYN_RCVD) {
                    s.state = Session.State.ESTABLISHED
                    Log.i(TAG, "ESTABLISHED ${ipStr(srcIp)}:$srcPort")
                }
                if (payLen > 0) {
                    handleData(s, buf, payStart, payLen, seq)
                }
            }
        }
    }

    private fun handleSyn(key: Key, clientIsn: Long) {
        sessions.remove(key)?.let {
            it.active = false
            try { it.localSocket?.close() } catch (_: Exception) {}
        }

        try {
            val sock = Socket("127.0.0.1", localPort)
            sock.tcpNoDelay = true

            val isn = Random.nextLong(1, 0xFFFFFFFFL)
            val s = Session(
                key = key,
                clientNextSeq = (clientIsn + 1) and 0xFFFFFFFFL,
                serverSeq = isn,
                localSocket = sock
            )
            sessions[key] = s

            // SYN-ACK (with MSS option → data offset = 6 words = 24 bytes)
            sendTcp(key.dstIp, key.srcIp, key.dstPort, key.srcPort,
                isn, s.clientNextSeq, SYN or ACK, mssOption = true)
            s.serverSeq = (isn + 1) and 0xFFFFFFFFL

            Log.i(TAG, "SYN-ACK → ${ipStr(key.srcIp)}:${key.srcPort}")

            // Reader: NanoHTTPD responses → TUN (bounded thread pool)
            relayExecutor.execute { readLocal(s) }

        } catch (e: Exception) {
            Log.e(TAG, "Local connect failed: ${e.message}")
            sendTcp(key.dstIp, key.srcIp, key.dstPort, key.srcPort,
                0, (clientIsn + 1) and 0xFFFFFFFFL, RST or ACK)
        }
    }

    private fun handleData(s: Session, buf: ByteArray, off: Int, len: Int, seq: Long) {
        if (seq != s.clientNextSeq) {
            // Out of order — just ACK what we have
            sendAck(s)
            return
        }

        try {
            s.localSocket?.getOutputStream()?.let {
                it.write(buf, off, len)
                it.flush()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Write to local failed: ${e.message}")
            closeSession(s)
            return
        }

        s.clientNextSeq = (seq + len) and 0xFFFFFFFFL
        sendAck(s)
    }

    private fun readLocal(s: Session) {
        try {
            val input = s.localSocket?.getInputStream() ?: return
            val buf = ByteArray(MSS)

            while (s.active && running) {
                val n = input.read(buf)
                if (n < 0) break

                sendTcp(s.key.dstIp, s.key.srcIp, s.key.dstPort, s.key.srcPort,
                    s.serverSeq, s.clientNextSeq, ACK or PSH,
                    data = buf.copyOfRange(0, n))
                s.serverSeq = (s.serverSeq + n) and 0xFFFFFFFFL
            }
        } catch (e: Exception) {
            if (s.active) Log.d(TAG, "Local read ended: ${e.message}")
        }

        // Server closed → send FIN
        if (s.active) {
            sendTcp(s.key.dstIp, s.key.srcIp, s.key.dstPort, s.key.srcPort,
                s.serverSeq, s.clientNextSeq, FIN or ACK)
            s.serverSeq = (s.serverSeq + 1) and 0xFFFFFFFFL
            s.state = Session.State.FIN_WAIT
        }
    }

    private fun handleFin(key: Key, seq: Long) {
        val s = sessions[key] ?: return
        s.clientNextSeq = (seq + 1) and 0xFFFFFFFFL

        // ACK the FIN
        sendAck(s)

        // Send our FIN
        sendTcp(key.dstIp, key.srcIp, key.dstPort, key.srcPort,
            s.serverSeq, s.clientNextSeq, FIN or ACK)
        s.serverSeq = (s.serverSeq + 1) and 0xFFFFFFFFL

        closeSession(s)
    }

    private fun sendAck(s: Session) {
        sendTcp(s.key.dstIp, s.key.srcIp, s.key.dstPort, s.key.srcPort,
            s.serverSeq, s.clientNextSeq, ACK)
    }

    private fun closeSession(s: Session) {
        s.active = false
        s.state = Session.State.CLOSED
        try { s.localSocket?.close() } catch (_: Exception) {}
        sessions.remove(s.key)
    }

    // --- Packet crafting ---

    private fun sendTcp(
        srcIp: Int, dstIp: Int, srcPort: Int, dstPort: Int,
        seq: Long, ack: Long, flags: Int,
        data: ByteArray? = null, mssOption: Boolean = false
    ) {
        val tcpHdrLen = if (mssOption) 24 else 20
        val dataLen = data?.size ?: 0
        val ipTotal = 20 + tcpHdrLen + dataLen
        val pkt = ByteArray(ipTotal)

        // --- IPv4 header (20 bytes) ---
        pkt[0] = 0x45.toByte()
        w16(pkt, 2, ipTotal)
        w16(pkt, 4, Random.nextInt(0xFFFF))
        pkt[6] = 0x40.toByte() // DF
        pkt[8] = 64 // TTL
        pkt[9] = PROTO_TCP.toByte()
        w32(pkt, 12, srcIp)
        w32(pkt, 16, dstIp)
        w16(pkt, 10, ipCksum(pkt, 0, 20))

        // --- TCP header ---
        val t = 20
        w16(pkt, t, srcPort)
        w16(pkt, t + 2, dstPort)
        w32(pkt, t + 4, seq)
        w32(pkt, t + 8, ack)
        pkt[t + 12] = ((tcpHdrLen / 4) shl 4).toByte()
        pkt[t + 13] = flags.toByte()
        w16(pkt, t + 14, WINDOW)
        // checksum at t+16 stays 0 for calculation

        if (mssOption) {
            pkt[t + 20] = 2 // kind=MSS
            pkt[t + 21] = 4 // len=4
            w16(pkt, t + 22, MSS)
        }

        if (data != null) {
            System.arraycopy(data, 0, pkt, 20 + tcpHdrLen, dataLen)
        }

        w16(pkt, t + 16, tcpCksum(srcIp, dstIp, pkt, t, tcpHdrLen + dataLen))

        synchronized(writeLock) {
            tunOut.write(pkt)
            tunOut.flush()
        }
    }

    // --- Checksums ---

    private fun ipCksum(buf: ByteArray, off: Int, len: Int): Int {
        var sum = 0L
        var i = 0
        while (i < len - 1) { sum += u16(buf, off + i); i += 2 }
        if (len % 2 == 1) sum += (buf[off + len - 1].toInt() and 0xFF).toLong() shl 8
        while (sum > 0xFFFF) sum = (sum and 0xFFFF) + (sum ushr 16)
        return sum.toInt().inv() and 0xFFFF
    }

    private fun tcpCksum(srcIp: Int, dstIp: Int, buf: ByteArray, tcpOff: Int, tcpLen: Int): Int {
        var sum = 0L
        // Pseudo-header
        sum += (srcIp.toLong() ushr 16) and 0xFFFF
        sum += srcIp.toLong() and 0xFFFF
        sum += (dstIp.toLong() ushr 16) and 0xFFFF
        sum += dstIp.toLong() and 0xFFFF
        sum += PROTO_TCP.toLong()
        sum += tcpLen.toLong()
        // TCP segment
        var i = 0
        while (i < tcpLen - 1) { sum += u16(buf, tcpOff + i); i += 2 }
        if (tcpLen % 2 == 1) sum += (buf[tcpOff + tcpLen - 1].toInt() and 0xFF).toLong() shl 8
        while (sum > 0xFFFF) sum = (sum and 0xFFFF) + (sum ushr 16)
        return sum.toInt().inv() and 0xFFFF
    }

    // --- Byte helpers ---

    private fun i32(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF shl 24) or (b[o+1].toInt() and 0xFF shl 16) or
        (b[o+2].toInt() and 0xFF shl 8) or (b[o+3].toInt() and 0xFF)

    private fun u32(b: ByteArray, o: Int): Long = i32(b, o).toLong() and 0xFFFFFFFFL

    private fun u16(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF shl 8) or (b[o+1].toInt() and 0xFF)

    private fun w32(b: ByteArray, o: Int, v: Long) { w32(b, o, v.toInt()) }
    private fun w32(b: ByteArray, o: Int, v: Int) {
        b[o] = (v ushr 24).toByte(); b[o+1] = (v ushr 16).toByte()
        b[o+2] = (v ushr 8).toByte(); b[o+3] = v.toByte()
    }

    private fun w16(b: ByteArray, o: Int, v: Int) {
        b[o] = (v ushr 8).toByte(); b[o+1] = v.toByte()
    }

    private fun ipStr(ip: Int) =
        "${ip ushr 24 and 0xFF}.${ip ushr 16 and 0xFF}.${ip ushr 8 and 0xFF}.${ip and 0xFF}"

    private fun flagStr(f: Int): String {
        val s = mutableListOf<String>()
        if (f and SYN != 0) s += "SYN"; if (f and ACK != 0) s += "ACK"
        if (f and FIN != 0) s += "FIN"; if (f and RST != 0) s += "RST"
        if (f and PSH != 0) s += "PSH"
        return s.joinToString("|")
    }
}
