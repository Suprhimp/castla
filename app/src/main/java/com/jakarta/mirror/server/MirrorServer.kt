package com.jakarta.mirror.server

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
        private const val TOKEN_LENGTH = 32
    }

    private val videoSockets = CopyOnWriteArrayList<VideoStreamSocket>()
    private val controlSockets = CopyOnWriteArrayList<ControlSocket>()
    private var touchListener: ((TouchEvent) -> Unit)? = null
    private var keyframeRequester: (() -> Unit)? = null

    /** Random session token generated on each server start for authentication */
    val sessionToken: String = generateToken()

    data class TouchEvent(
        val action: String,
        val x: Float,
        val y: Float,
        val pointerId: Int
    )

    private fun generateToken(): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        val random = SecureRandom()
        return (1..TOKEN_LENGTH).map { chars[random.nextInt(chars.length)] }.joinToString("")
    }

    private fun isAuthorized(session: IHTTPSession): Boolean {
        val params = session.parms ?: return false
        return params["token"] == sessionToken
    }

    fun setTouchListener(listener: (TouchEvent) -> Unit) {
        touchListener = listener
    }

    fun setKeyframeRequester(requester: () -> Unit) {
        keyframeRequester = requester
    }

    fun broadcastFrame(data: ByteArray, isKeyFrame: Boolean) {
        val header = if (isKeyFrame) byteArrayOf(0x01) else byteArrayOf(0x00)
        val frame = header + data

        val deadSockets = mutableListOf<VideoStreamSocket>()
        for (socket in videoSockets) {
            try {
                socket.send(frame)
            } catch (e: IOException) {
                Log.w(TAG, "Failed to send frame to client", e)
                deadSockets.add(socket)
            }
        }
        videoSockets.removeAll(deadSockets.toSet())
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
    }

    fun unregisterControlSocket(socket: ControlSocket) {
        controlSockets.remove(socket)
        Log.i(TAG, "Control client disconnected (total: ${controlSockets.size})")
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
            else -> VideoStreamSocket(handshake, this)
        }
    }

    override fun serveHttp(session: IHTTPSession): Response {
        var uri = session.uri

        // Static sub-resources (JS, CSS, images) are loaded by the browser without token params.
        // Only the initial page load (/ or /index.html) and WS connections require token auth.
        val isSubResource = uri.endsWith(".js") || uri.endsWith(".css") ||
            uri.endsWith(".png") || uri.endsWith(".jpg") || uri.endsWith(".jpeg") ||
            uri.endsWith(".svg") || uri.endsWith(".ico") || uri.endsWith(".woff") ||
            uri.endsWith(".woff2")

        if (!isSubResource && !isAuthorized(session)) {
            return newFixedLengthResponse(
                Response.Status.FORBIDDEN,
                "text/plain",
                "403 Forbidden: Invalid or missing token"
            )
        }

        if (uri == "/") uri = "/index.html"

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
