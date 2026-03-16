package com.castla.mirror.server

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import org.json.JSONObject
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

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
            // Binary frames: 10-byte touch protocol [action:u8][id:u8][x:f32LE][y:f32LE]
            if (message.opCode == NanoWSD.WebSocketFrame.OpCode.Binary) {
                handleBinaryTouch(message.binaryPayload)
                return
            }

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
                "textInput" -> {
                    val text = json.optString("text", "")
                    if (text.isNotEmpty()) {
                        server.onTextInput(text)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse control message", e)
        }
    }

    private fun handleBinaryTouch(data: ByteArray) {
        if (data.size < 10) return
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val action = when (buf.get().toInt() and 0xFF) {
            0 -> "down"
            1 -> "up"
            2 -> "move"
            else -> return
        }
        val id = buf.get().toInt() and 0xFF
        val x = buf.float
        val y = buf.float
        server.onTouchEvent(MirrorServer.TouchEvent(action, x, y, id))
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
