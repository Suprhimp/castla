package com.castla.mirror.server

import android.content.Context
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import java.io.IOException
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArrayList

class MirrorServer(
    private val context: Context,
    port: Int = 8080
) : NanoWSD(port) {

    companion object {
        private const val TAG = "MirrorServer"
    }

    private val videoSockets = CopyOnWriteArrayList<VideoStreamSocket>()
    private val controlSockets = CopyOnWriteArrayList<ControlSocket>()
    private val audioSockets = CopyOnWriteArrayList<AudioStreamSocket>()
    private var touchListener: ((TouchEvent) -> Unit)? = null
    private var keyframeRequester: (() -> Unit)? = null
    private var codecModeListener: ((String) -> Unit)? = null
    private var viewportChangeListener: ((Int, Int) -> Unit)? = null
    private var browserConnectionListener: ((Boolean) -> Unit)? = null

    /** Random 4-digit PIN generated on each server start for authentication */
    val sessionPin: String = generatePin()

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

    private fun generatePin(): String {
        val random = SecureRandom()
        return String.format("%04d", random.nextInt(10000))
    }

    private fun isAuthorized(session: IHTTPSession): Boolean {
        val params = session.parms ?: return false
        return params["pin"] == sessionPin
    }

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

    fun broadcastFrame(data: ByteArray, isKeyFrame: Boolean) {
        val header = if (isKeyFrame) byteArrayOf(0x01) else byteArrayOf(0x00)
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

    fun registerVideoSocket(socket: VideoStreamSocket) {
        videoSockets.add(socket)
        Log.i(TAG, "Video client connected (total: ${videoSockets.size})")
        keyframeRequester?.invoke()
    }

    fun unregisterVideoSocket(socket: VideoStreamSocket) {
        videoSockets.remove(socket)
        Log.i(TAG, "Video client disconnected (total: ${videoSockets.size})")
    }

    fun registerControlSocket(socket: ControlSocket) {
        controlSockets.add(socket)
        Log.i(TAG, "Control client connected (total: ${controlSockets.size})")
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
    }

    fun unregisterAudioSocket(socket: AudioStreamSocket) {
        audioSockets.remove(socket)
        Log.i(TAG, "Audio client disconnected (total: ${audioSockets.size})")
    }

    fun broadcastAudio(data: ByteArray) {
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
        if (!isAuthorized(handshake)) {
            Log.w(TAG, "Unauthorized WebSocket attempt from ${handshake.remoteIpAddress}")
            return UnauthorizedSocket(handshake)
        }

        val uri = handshake.uri
        return when {
            uri.startsWith("/ws/video") -> VideoStreamSocket(handshake, this)
            uri.startsWith("/ws/control") -> ControlSocket(handshake, this)
            uri.startsWith("/ws/audio") -> AudioStreamSocket(handshake, this)
            else -> VideoStreamSocket(handshake, this)
        }
    }

    override fun serveHttp(session: IHTTPSession): Response {
        var uri = session.uri

        // Static sub-resources (JS, CSS, images) are loaded by the browser without pin params.
        // Only the initial page load (/ or /index.html) and WS connections require pin auth.
        val isSubResource = uri.endsWith(".js") || uri.endsWith(".css") ||
            uri.endsWith(".png") || uri.endsWith(".jpg") || uri.endsWith(".jpeg") ||
            uri.endsWith(".svg") || uri.endsWith(".ico") || uri.endsWith(".woff") ||
            uri.endsWith(".woff2")

        // Root without valid pin → show PIN entry page
        if (uri == "/" && !isAuthorized(session)) {
            return serveAsset("/pin.html")
        }

        if (!isSubResource && !isAuthorized(session)) {
            return newFixedLengthResponse(
                Response.Status.FORBIDDEN,
                "text/plain",
                "403 Forbidden: Invalid or missing PIN"
            )
        }

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
