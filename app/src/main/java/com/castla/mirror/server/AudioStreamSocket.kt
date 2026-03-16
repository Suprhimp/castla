package com.castla.mirror.server

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import java.io.IOException

class AudioStreamSocket(
    handshake: NanoHTTPD.IHTTPSession,
    private val server: MirrorServer
) : NanoWSD.WebSocket(handshake) {

    companion object {
        private const val TAG = "AudioStreamSocket"
    }

    override fun onOpen() {
        server.registerAudioSocket(this)
    }

    override fun onClose(code: NanoWSD.WebSocketFrame.CloseCode?, reason: String?, initiatedByRemote: Boolean) {
        server.unregisterAudioSocket(this)
    }

    override fun onMessage(message: NanoWSD.WebSocketFrame) {
        // No client-to-server audio messages expected
    }

    override fun onPong(pong: NanoWSD.WebSocketFrame?) {}

    override fun onException(exception: IOException?) {
        Log.w(TAG, "WebSocket exception", exception)
        server.unregisterAudioSocket(this)
    }

    fun sendBinary(data: ByteArray) {
        try {
            send(data)
        } catch (e: IOException) {
            Log.w(TAG, "Send failed", e)
            throw e
        }
    }
}
