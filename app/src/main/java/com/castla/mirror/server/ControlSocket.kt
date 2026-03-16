package com.castla.mirror.server

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import org.json.JSONObject
import java.io.IOException

class ControlSocket(
    handshake: NanoHTTPD.IHTTPSession,
    private val server: MirrorServer
) : NanoWSD.WebSocket(handshake) {

    companion object {
        private const val TAG = "ControlSocket"
    }

    override fun onOpen() {
        server.registerControlSocket(this)
    }

    override fun onClose(code: NanoWSD.WebSocketFrame.CloseCode?, reason: String?, initiatedByRemote: Boolean) {
        server.unregisterControlSocket(this)
    }

    override fun onMessage(message: NanoWSD.WebSocketFrame) {
        try {
            val json = JSONObject(message.textPayload)
            val type = json.optString("type", "")

            when (type) {
                "touch" -> {
                    val event = MirrorServer.TouchEvent(
                        action = json.getString("action"),
                        x = json.getDouble("x").toFloat(),
                        y = json.getDouble("y").toFloat(),
                        pointerId = json.optInt("id", 0)
                    )
                    server.onTouchEvent(event)
                }
                "requestKeyframe" -> {
                    server.onKeyframeRequest()
                }
                "codec" -> {
                    val mode = json.optString("mode", "h264")
                    server.onCodecModeRequest(mode)
                }
                "viewport" -> {
                    val width = json.optInt("width", 0)
                    val height = json.optInt("height", 0)
                    if (width > 0 && height > 0) {
                        server.onViewportChange(width, height)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse control message", e)
        }
    }

    /**
     * Send a text message to this control socket client.
     */
    fun sendMessage(text: String) {
        try {
            send(text)
        } catch (e: IOException) {
            Log.w(TAG, "Failed to send control message", e)
        }
    }

    override fun onPong(pong: NanoWSD.WebSocketFrame?) {}

    override fun onException(exception: IOException?) {
        Log.w(TAG, "Control socket exception", exception)
        server.unregisterControlSocket(this)
    }
}
