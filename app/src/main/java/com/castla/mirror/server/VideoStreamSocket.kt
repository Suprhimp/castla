package com.castla.mirror.server

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

class VideoStreamSocket(
    handshake: NanoHTTPD.IHTTPSession,
    private val server: MirrorServer,
    private val channel: String = "primary"
) : NanoWSD.WebSocket(handshake) {

    companion object {
        private const val TAG = "VideoStreamSocket"
        private const val MAX_QUEUE = 5
        private const val MIN_KEYFRAME_REQUEST_INTERVAL_MS = 500L
    }

    private val sendQueue = ArrayBlockingQueue<ByteArray>(MAX_QUEUE)
    private val sending = AtomicBoolean(false)
    @Volatile private var closed = false
    @Volatile private var framesSent = 0L
    @Volatile private var framesDropped = 0L
    @Volatile private var lastKeyframeRequestTime = 0L
    @Volatile private var queueFlushCount = 0L

    private val sendThread = Thread({
        while (!closed) {
            try {
                val data = sendQueue.take() // blocks until available
                if (closed) break
                send(data)
                framesSent++
                if (framesSent == 1L || framesSent % 1000 == 0L) {
                    Log.i(TAG, "[$channel] frame=#$framesSent size=${data.size} dropped=$framesDropped flushes=$queueFlushCount queueSize=${sendQueue.size}")
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
        server.registerVideoSocket(channel, this)
        sendThread.start()
    }

    override fun onClose(code: NanoWSD.WebSocketFrame.CloseCode?, reason: String?, initiatedByRemote: Boolean) {
        closed = true
        sendThread.interrupt()
        server.unregisterVideoSocket(channel, this)
    }

    override fun onMessage(message: NanoWSD.WebSocketFrame) {
        val text = message.textPayload
        if (text == "requestKeyframe") {
            server.onKeyframeRequest(channel)
        }
    }

    override fun onPong(pong: NanoWSD.WebSocketFrame?) {}

    override fun onException(exception: IOException?) {
        Log.w(TAG, "WebSocket exception", exception)
        closed = true
        sendThread.interrupt()
        server.unregisterVideoSocket(channel, this)
    }

    /**
     * Smart keyframe-preserving queue with graduated drop policy:
     * - Keyframes: clear all queued delta frames, then enqueue (keyframes are never dropped)
     * - Delta frames: if queue is full, drop oldest delta to make room
     * - SPS/PPS config (0x02): always enqueue immediately
     * - Keyframe requests are rate-limited to avoid encoder thrashing
     *
     * Frame header: [flags:u8][seqLo:u8][seqHi:u8][tsMs0-3:u8x4][reserved:u8]
     */
    fun sendBinary(data: ByteArray) {
        if (closed) throw IOException("Socket closed")

        val isKeyFrame = data.isNotEmpty() && data[0] == 0x01.toByte()
        val isConfig = data.isNotEmpty() && data[0] == 0x02.toByte()

        if (isConfig) {
            // Config messages bypass the queue — send directly
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
            // Delta frame: graduated drop policy
            if (!sendQueue.offer(data)) {
                // Queue is full — drop the oldest frame (likely a stale delta)
                // and try again before resorting to a full flush + keyframe request
                sendQueue.poll()
                framesDropped++

                if (!sendQueue.offer(data)) {
                    // Still can't fit — network is severely bottlenecked.
                    // Clear queue and request a fresh keyframe, but rate-limit
                    // the request to avoid encoder thrashing.
                    sendQueue.clear()
                    queueFlushCount++

                    val now = System.currentTimeMillis()
                    if (now - lastKeyframeRequestTime >= MIN_KEYFRAME_REQUEST_INTERVAL_MS) {
                        lastKeyframeRequestTime = now
                        server.onKeyframeRequest(channel)
                    }
                }
            }
        }
    }
}
