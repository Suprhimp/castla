package com.jakarta.mirror.server

import android.content.Context
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import java.io.IOException
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
    private var touchListener: ((TouchEvent) -> Unit)? = null
    private var keyframeRequester: (() -> Unit)? = null

    data class TouchEvent(
        val action: String,
        val x: Float,
        val y: Float,
        val pointerId: Int
    )

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

    // WebSocket routing
    override fun openWebSocket(handshake: IHTTPSession): WebSocket {
        val uri = handshake.uri
        return when {
            uri.startsWith("/ws/video") -> VideoStreamSocket(handshake, this)
            uri.startsWith("/ws/control") -> ControlSocket(handshake, this)
            else -> VideoStreamSocket(handshake, this) // default
        }
    }

    // HTTP file serving for non-WebSocket requests
    override fun serveHttp(session: IHTTPSession): Response {
        var uri = session.uri
        if (uri == "/") uri = "/index.html"

        // Remove leading slash for asset path
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
}
