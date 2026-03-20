package com.castla.mirror.server

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

class VideoStreamSocket(
    handshake: NanoHTTPD.IHTTPSession,
    private val server: MirrorServer
) : NanoWSD.WebSocket(handshake) {

    companion object {
        private const val TAG = "VideoStreamSocket"
        private const val MAX_QUEUE = 3
    }

    private val sendQueue = ArrayBlockingQueue<ByteArray>(MAX_QUEUE)
    private val sending = AtomicBoolean(false)
    @Volatile private var closed = false
    @Volatile private var framesSent = 0L

    private val sendThread = Thread({
        while (!closed) {
            try {
                val data = sendQueue.take() // blocks until available
                if (closed) break
                send(data)
                framesSent++
                if (framesSent == 1L || framesSent % 100 == 0L) {
                    Log.i(TAG, "Sent frame #$framesSent, size=${data.size}")
                }
            } catch (_: InterruptedException) {
                break
            } catch (e: IOException) {
                Log.w(TAG, "Send failed, closing", e)
                closed = true
                try { close(NanoWSD.WebSocketFrame.CloseCode.GoingAway, "send error", false) }
                catch (_: Exception) {}
                break
            } catch (e: Exception) {
                Log.w(TAG, "Unexpected send error", e)
            }
        }
    }, "WS-Video-Send").apply { isDaemon = true }

    override fun onOpen() {
        server.registerVideoSocket(this)
        sendThread.start()
    }

    override fun onClose(code: NanoWSD.WebSocketFrame.CloseCode?, reason: String?, initiatedByRemote: Boolean) {
        closed = true
        sendThread.interrupt()
        server.unregisterVideoSocket(this)
    }

    override fun onMessage(message: NanoWSD.WebSocketFrame) {
        val text = message.textPayload
        if (text == "requestKeyframe") {
            server.onKeyframeRequest()
        }
    }

    override fun onPong(pong: NanoWSD.WebSocketFrame?) {}

    override fun onException(exception: IOException?) {
        Log.w(TAG, "WebSocket exception", exception)
        closed = true
        sendThread.interrupt()
        server.unregisterVideoSocket(this)
    }

    /**
     * Smart keyframe-preserving queue:
     * - Keyframes: clear all queued delta frames, then enqueue (keyframes are never dropped)
     * - Delta frames: if queue is full, drop oldest delta to make room
     * - SPS/PPS config (0x02): always enqueue immediately
     *
     * Frame header: [flags:u8][seqLo:u8][seqHi:u8][reserved:u8]
     */
    fun sendBinary(data: ByteArray) {
        if (closed) throw IOException("Socket closed")

        val isKeyFrame = data.isNotEmpty() && data[0] == 0x01.toByte()
        val isConfig = data.isNotEmpty() && data[0] == 0x02.toByte()

        if (isConfig) {
            // Config messages bypass the queue — send directly
            // (small enough that blocking briefly is fine)
            while (!sendQueue.offer(data)) {
                sendQueue.poll()
            }
            return
        }

        if (isKeyFrame) {
            // Keyframe arriving: clear all queued delta frames to make room
            // Keyframes are precious — never drop them
            sendQueue.clear()
            sendQueue.offer(data)
        } else {
            // Delta frame: if queue full, drop oldest (which is also a delta)
            if (!sendQueue.offer(data)) {
                // The queue is full (network bottleneck).
                // Do NOT just drop a P-frame, because it will cause smearing/ghosting
                // until the next I-frame. Instead, clear the queue to stop sending
                // useless deltas, and immediately request a fresh I-frame from the encoder.
                sendQueue.clear()
                server.onKeyframeRequest()
            }
        }
    }
}