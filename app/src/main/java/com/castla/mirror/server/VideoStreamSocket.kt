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
        private const val MAX_QUEUE = 2 // very small — prefer dropping over buffering
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
     * Enqueue a frame for async sending. If the queue is full, drop the oldest
     * frame (delta frames are expendable — the next keyframe will resync).
     */
    fun sendBinary(data: ByteArray) {
        if (closed) throw IOException("Socket closed")
        // If queue is full, drop oldest to make room (prefer freshness over completeness)
        while (!sendQueue.offer(data)) {
            sendQueue.poll() // discard oldest
        }
    }
}
