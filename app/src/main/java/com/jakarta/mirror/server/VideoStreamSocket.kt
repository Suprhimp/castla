package com.jakarta.mirror.server

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import java.io.IOException

class VideoStreamSocket(
    handshake: NanoHTTPD.IHTTPSession,
    private val server: MirrorServer
) : NanoWSD.WebSocket(handshake) {

    companion object {
        private const val TAG = "VideoStreamSocket"
    }

    override fun onOpen() {
        server.registerVideoSocket(this)
    }

    override fun onClose(code: NanoWSD.WebSocketFrame.CloseCode?, reason: String?, initiatedByRemote: Boolean) {
        server.unregisterVideoSocket(this)
    }

    override fun onMessage(message: NanoWSD.WebSocketFrame) {
        // Client can request keyframes via text message
        val text = message.textPayload
        if (text == "requestKeyframe") {
            server.onKeyframeRequest()
        }
    }

    override fun onPong(pong: NanoWSD.WebSocketFrame?) {}

    override fun onException(exception: IOException?) {
        Log.w(TAG, "WebSocket exception", exception)
        server.unregisterVideoSocket(this)
    }

    fun send(data: ByteArray) {
        try {
            send(NanoWSD.WebSocketFrame(
                NanoWSD.WebSocketFrame.OpCode.Binary,
                true,
                data
            ))
        } catch (e: IOException) {
            Log.w(TAG, "Send failed", e)
            throw e
        }
    }
}
