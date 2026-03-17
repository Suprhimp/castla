package com.castla.mirror.server

import android.content.Context
import android.util.Log
import com.castla.mirror.billing.LicenseManager
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

class MirrorServer(
    private val context: Context,
    port: Int = DEFAULT_PORT
) : NanoWSD(port) {

    companion object {
        const val DEFAULT_PORT = 9090
        private const val TAG = "MirrorServer"
    }

    private val videoSockets = CopyOnWriteArrayList<VideoStreamSocket>()
    private val controlSockets = CopyOnWriteArrayList<ControlSocket>()
    private val audioSockets = CopyOnWriteArrayList<AudioStreamSocket>()
    private var touchListener: ((TouchEvent) -> Unit)? = null
    private var keyframeRequester: (() -> Unit)? = null
    private var codecModeListener: ((String) -> Unit)? = null
    private var viewportChangeListener: ((Int, Int) -> Unit)? = null
    private var audioCodecListener: ((String) -> Unit)? = null
    private var browserConnectionListener: ((Boolean) -> Unit)? = null
    private var purchaseRequestListener: (() -> Unit)? = null
    private var goHomeListener: (() -> Unit)? = null

    /** Cached audio config (0x00 message) — replayed to late-joining audio clients */
    @Volatile private var audioConfig: ByteArray? = null

    /** Disable Nagle algorithm on all client connections for lower latency */
    override fun createClientHandler(finalAccept: java.net.Socket, inputStream: java.io.InputStream): NanoHTTPD.ClientHandler {
        try {
            finalAccept.tcpNoDelay = true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set TCP_NODELAY", e)
        }
        return super.createClientHandler(finalAccept, inputStream)
    }

    data class TouchEvent(
        val action: String,
        val x: Float,
        val y: Float,
        val pointerId: Int
    )

    /** Text input listener for keyboard events from the browser */
    private var textInputListener: ((String) -> Unit)? = null

    fun setTouchListener(listener: (TouchEvent) -> Unit) {
        touchListener = listener
    }

    fun setKeyframeRequester(requester: () -> Unit) {
        keyframeRequester = requester
    }

    fun setCodecModeListener(listener: (String) -> Unit) {
        codecModeListener = listener
    }

    fun setViewportChangeListener(listener: (Int, Int) -> Unit) {
        viewportChangeListener = listener
    }

    fun setBrowserConnectionListener(listener: ((Boolean) -> Unit)?) {
        browserConnectionListener = listener
    }

    fun setAudioCodecListener(listener: (String) -> Unit) {
        audioCodecListener = listener
    }

    fun onAudioCodecRequest(codec: String) {
        Log.i(TAG, "Audio codec request: $codec")
        audioCodecListener?.invoke(codec)
    }

    fun setTextInputListener(listener: (String) -> Unit) {
        textInputListener = listener
    }

    fun onTextInput(text: String) {
        Log.i(TAG, "Text input: ${text.length} chars")
        textInputListener?.invoke(text)
    }

    private var keyEventListener: ((Int) -> Unit)? = null
    fun setKeyEventListener(listener: (Int) -> Unit) { keyEventListener = listener }
    fun onKeyEvent(keyCode: Int) {
        Log.i(TAG, "Key event: $keyCode")
        keyEventListener?.invoke(keyCode)
    }

    fun setPurchaseRequestListener(listener: (() -> Unit)?) {
        purchaseRequestListener = listener
    }

    fun onPurchaseRequest() {
        Log.i(TAG, "Purchase request from browser")
        purchaseRequestListener?.invoke()
    }

    fun setGoHomeListener(listener: (() -> Unit)?) {
        goHomeListener = listener
    }

    fun onGoHome() {
        Log.i(TAG, "Go home request from browser")
        goHomeListener?.invoke()
    }

    fun onViewportChange(width: Int, height: Int) {
        Log.i(TAG, "Client viewport change: ${width}x${height}")
        viewportChangeListener?.invoke(width, height)
    }

    /**
     * Send a text message to all connected control sockets.
     */
    fun broadcastControlMessage(json: String) {
        for (socket in controlSockets) {
            socket.sendMessage(json)
        }
    }

    fun onCodecModeRequest(mode: String) {
        Log.i(TAG, "Client requested codec mode: $mode")
        codecModeListener?.invoke(mode)
    }

    /**
     * Video frame header (8 bytes, all little-endian):
     *   [flags:u8] [seqLo:u8] [seqHi:u8] [tsMs_0:u8] [tsMs_1:u8] [tsMs_2:u8] [tsMs_3:u8] [reserved:u8]
     * flags: 0x00=delta, 0x01=keyframe, 0x02=codec config (SPS/PPS)
     * seqNum: u16 LE, wraps at 65535
     * tsMs: u32 LE, milliseconds from SystemClock.elapsedRealtime()
     */
    private var frameSeqNum: Int = 0

    /** Cached SPS/PPS — sent to new clients and on resolution change */
    @Volatile private var cachedSpsPps: ByteArray? = null

    private fun buildVideoHeader(flags: Byte, seq: Int): ByteArray {
        val tsMs = android.os.SystemClock.elapsedRealtime().toInt()
        val buf = java.nio.ByteBuffer.allocate(8).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buf.put(flags)
        buf.putShort(seq.toShort())  // seqLo, seqHi (2 bytes LE)
        buf.putInt(tsMs)             // tsMs_0~3 (4 bytes LE)
        buf.put(0.toByte())          // reserved
        return buf.array()
    }

    fun broadcastFrame(data: ByteArray, isKeyFrame: Boolean) {
        val seq = frameSeqNum++
        val flags: Byte = if (isKeyFrame) 0x01 else 0x00
        val header = buildVideoHeader(flags, seq)
        val frame = header + data

        for (socket in videoSockets) {
            try {
                socket.sendBinary(frame)
            } catch (e: IOException) {
                Log.w(TAG, "Failed to enqueue frame, removing client", e)
                videoSockets.remove(socket)
            }
        }
    }

    /**
     * Broadcast SPS/PPS as a separate config message (flags=0x02).
     * Also caches it for late-joining clients.
     */
    fun broadcastSpsPps(spsPps: ByteArray) {
        val seq = frameSeqNum
        val header = buildVideoHeader(0x02, seq)
        val frame = header + spsPps
        cachedSpsPps = frame.copyOf()

        for (socket in videoSockets) {
            try {
                socket.sendBinary(frame)
            } catch (e: IOException) {
                Log.w(TAG, "Failed to send SPS/PPS", e)
            }
        }
    }

    fun registerVideoSocket(socket: VideoStreamSocket) {
        videoSockets.add(socket)
        Log.i(TAG, "Video client connected (total: ${videoSockets.size})")
        // Send cached SPS/PPS so decoder can initialize before first keyframe
        cachedSpsPps?.let { config ->
            try { socket.sendBinary(config) } catch (_: IOException) {}
        }
        keyframeRequester?.invoke()
    }

    fun unregisterVideoSocket(socket: VideoStreamSocket) {
        videoSockets.remove(socket)
        Log.i(TAG, "Video client disconnected (total: ${videoSockets.size})")
    }

    fun registerControlSocket(socket: ControlSocket) {
        controlSockets.add(socket)
        Log.i(TAG, "Control client connected (total: ${controlSockets.size})")

        // Send premium status to new client
        val status = JSONObject().apply {
            put("type", "premiumStatus")
            put("isPremium", LicenseManager.isPremiumNow)
        }
        socket.sendMessage(status.toString())

        if (controlSockets.size == 1) {
            browserConnectionListener?.invoke(true)
        }
    }

    fun unregisterControlSocket(socket: ControlSocket) {
        controlSockets.remove(socket)
        Log.i(TAG, "Control client disconnected (total: ${controlSockets.size})")
        if (controlSockets.isEmpty()) {
            browserConnectionListener?.invoke(false)
        }
    }

    fun registerAudioSocket(socket: AudioStreamSocket) {
        audioSockets.add(socket)
        Log.i(TAG, "Audio client connected (total: ${audioSockets.size})")
        // Replay cached audio config so late-joining clients know the codec
        audioConfig?.let { config ->
            try {
                socket.sendBinary(config)
                Log.i(TAG, "Replayed audio config to new client (${config.size} bytes)")
            } catch (e: IOException) {
                Log.w(TAG, "Failed to send audio config to new client", e)
            }
        }
    }

    fun unregisterAudioSocket(socket: AudioStreamSocket) {
        audioSockets.remove(socket)
        Log.i(TAG, "Audio client disconnected (total: ${audioSockets.size})")
    }

    fun broadcastAudio(data: ByteArray) {
        // Cache config messages (0x00 header) for late-joining clients
        if (data.isNotEmpty() && data[0] == 0x00.toByte()) {
            audioConfig = data.copyOf()
        }

        val deadSockets = mutableListOf<AudioStreamSocket>()
        for (socket in audioSockets) {
            try {
                socket.sendBinary(data)
            } catch (e: IOException) {
                Log.w(TAG, "Failed to send audio to client", e)
                deadSockets.add(socket)
            }
        }
        audioSockets.removeAll(deadSockets.toSet())
    }

    fun onTouchEvent(event: TouchEvent) {
        touchListener?.invoke(event)
    }

    fun onKeyframeRequest() {
        keyframeRequester?.invoke()
    }

    val connectedClients: Int
        get() = videoSockets.size

    override fun openWebSocket(handshake: IHTTPSession): WebSocket {
        // Single-connection limit: reject if a video client is already connected
        val uri = handshake.uri
        if (uri.startsWith("/ws/video") && videoSockets.isNotEmpty()) {
            Log.w(TAG, "Rejecting second video connection from ${handshake.remoteIpAddress}")
            return UnauthorizedSocket(handshake)
        }

        return when {
            uri.startsWith("/ws/video") -> VideoStreamSocket(handshake, this)
            uri.startsWith("/ws/control") -> ControlSocket(handshake, this)
            uri.startsWith("/ws/audio") -> AudioStreamSocket(handshake, this)
            else -> VideoStreamSocket(handshake, this)
        }
    }

    override fun serveHttp(session: IHTTPSession): Response {
        var uri = session.uri
        if (uri == "/") uri = "/index.html"
        return serveAsset(uri)
    }

    private fun serveAsset(uri: String): Response {
        val assetPath = "web${uri}"
        return try {
            val inputStream = context.assets.open(assetPath)
            val mimeType = getMimeType(uri)
            newFixedLengthResponse(Response.Status.OK, mimeType, inputStream, inputStream.available().toLong())
        } catch (e: IOException) {
            newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "404 Not Found: $uri")
        }
    }

    private fun getMimeType(uri: String): String {
        return when {
            uri.endsWith(".html") -> "text/html"
            uri.endsWith(".js") -> "application/javascript"
            uri.endsWith(".css") -> "text/css"
            uri.endsWith(".json") -> "application/json"
            uri.endsWith(".png") -> "image/png"
            uri.endsWith(".jpg") || uri.endsWith(".jpeg") -> "image/jpeg"
            uri.endsWith(".svg") -> "image/svg+xml"
            uri.endsWith(".ico") -> "image/x-icon"
            else -> "application/octet-stream"
        }
    }

    /** Immediately closes unauthorized WebSocket connections */
    private class UnauthorizedSocket(
        handshake: IHTTPSession
    ) : NanoWSD.WebSocket(handshake) {
        override fun onOpen() {
            try {
                close(NanoWSD.WebSocketFrame.CloseCode.PolicyViolation, "Unauthorized", false)
            } catch (_: Exception) {}
        }
        override fun onClose(
            code: NanoWSD.WebSocketFrame.CloseCode?,
            reason: String?,
            initiatedByRemote: Boolean
        ) {}
        override fun onMessage(message: NanoWSD.WebSocketFrame?) {}
        override fun onPong(pong: NanoWSD.WebSocketFrame?) {}
        override fun onException(exception: IOException?) {}
    }
}
