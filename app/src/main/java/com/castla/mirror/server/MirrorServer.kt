package com.castla.mirror.server

import android.content.Context
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import fi.iki.elonen.NanoWSD.WebSocket
import org.json.JSONObject
import com.castla.mirror.billing.LicenseManager
import com.castla.mirror.utils.AppCategoryClassifier

data class TouchEvent(val action: String, val x: Float, val y: Float, val pointerId: Int, val pane: String = "primary")

class MirrorServer(private val context: Context) : NanoWSD(DEFAULT_PORT) {

    companion object {
        private const val TAG = "MirrorServer"
        const val DEFAULT_PORT = 9090
    }

    private val primaryVideoSockets = mutableSetOf<VideoStreamSocket>()
    private val secondaryVideoSockets = mutableSetOf<VideoStreamSocket>()
    private val controlSockets = mutableSetOf<ControlSocket>()
    private val audioSockets = mutableSetOf<AudioStreamSocket>()

    private var onTouchListener: ((TouchEvent) -> Unit)? = null
    private var onCodecModeListener: ((String) -> Unit)? = null
    private var onViewportChangeListener: ((String, Int, Int, String) -> Unit)? = null
    private var onTextInputListener: ((String) -> Unit)? = null
    private var onKeyEventListener: ((Int) -> Unit)? = null
    private var onCompositionUpdateListener: ((Int, String) -> Unit)? = null
    private var onAudioCodecListener: ((String) -> Unit)? = null
    private var onPrimaryKeyframeRequest: (() -> Unit)? = null
    private var onSecondaryKeyframeRequest: (() -> Unit)? = null
    private var networkCongestionListener: (() -> Unit)? = null
    
    // Web Launcher specific listeners
    private var onGoHomeListener: (() -> Unit)? = null
    private var onAppLaunchListener: ((String, String?, Boolean, String) -> Unit)? = null

    // Track active connection status
    private var isBrowserConnected = false
    private var onBrowserConnectionListener: ((Boolean) -> Unit)? = null

    // Listeners for DesktopActivity interaction
    private var onPurchaseRequestListener: (() -> Unit)? = null

    private var cachedSpsPps: ByteArray? = null

    fun setTouchListener(listener: (TouchEvent) -> Unit) {
        onTouchListener = listener
    }

    fun setCodecModeListener(listener: (String) -> Unit) {
        onCodecModeListener = listener
    }

    fun setViewportChangeListener(listener: (String, Int, Int, String) -> Unit) {
        onViewportChangeListener = listener
    }

    fun setTextInputListener(listener: (String) -> Unit) {
        onTextInputListener = listener
    }

    fun setKeyEventListener(listener: (Int) -> Unit) {
        onKeyEventListener = listener
    }

    fun setCompositionUpdateListener(listener: (Int, String) -> Unit) {
        onCompositionUpdateListener = listener
    }

    fun setAudioCodecListener(listener: (String) -> Unit) {
        onAudioCodecListener = listener
    }

    fun setKeyframeRequester(channel: String = "primary", requester: () -> Unit) {
        if (channel == "secondary") onSecondaryKeyframeRequest = requester else onPrimaryKeyframeRequest = requester
    }
    
    fun setNetworkCongestionListener(listener: () -> Unit) {
        networkCongestionListener = listener
    }

    fun setBrowserConnectionListener(listener: ((Boolean) -> Unit)?) {
        onBrowserConnectionListener = listener
        // Fire immediately if already connected
        if (isBrowserConnected) listener?.invoke(true)
    }

    fun setPurchaseRequestListener(listener: (() -> Unit)?) {
        onPurchaseRequestListener = listener
    }
    
    fun setGoHomeListener(listener: () -> Unit) {
        onGoHomeListener = listener
    }
    
    fun setAppLaunchListener(listener: (String, String?, Boolean, String) -> Unit) {
        onAppLaunchListener = listener
    }

    private fun updateConnectionState() {
        val connected = primaryVideoSockets.isNotEmpty() || secondaryVideoSockets.isNotEmpty() || controlSockets.isNotEmpty()
        if (connected != isBrowserConnected) {
            isBrowserConnected = connected
            onBrowserConnectionListener?.invoke(connected)
        }
    }

    fun registerVideoSocket(channel: String, socket: VideoStreamSocket) {
        val sockets = if (channel == "secondary") secondaryVideoSockets else primaryVideoSockets
        sockets.add(socket)
        Log.i(TAG, "$channel video client connected (total: ${sockets.size})")

        val cached = if (channel == "secondary") cachedSecondarySpsPps else cachedPrimarySpsPps
        cached?.let {
            socket.sendBinary(it)
            Log.i(TAG, "Sent cached SPS/PPS to new $channel video client")
        }

        updateConnectionState()
        onKeyframeRequest(channel)
    }

    fun unregisterVideoSocket(channel: String, socket: VideoStreamSocket) {
        val sockets = if (channel == "secondary") secondaryVideoSockets else primaryVideoSockets
        sockets.remove(socket)
        Log.i(TAG, "$channel video client disconnected (total: ${sockets.size})")
        updateConnectionState()
    }

    fun registerControlSocket(socket: ControlSocket) {
        controlSockets.add(socket)
        Log.i(TAG, "Control client connected (total: ${controlSockets.size})")
        updateConnectionState()
    }

    fun unregisterControlSocket(socket: ControlSocket) {
        controlSockets.remove(socket)
        Log.i(TAG, "Control client disconnected (total: ${controlSockets.size})")
        updateConnectionState()
    }

    fun registerAudioSocket(socket: AudioStreamSocket) {
        audioSockets.add(socket)
        Log.i(TAG, "Audio client connected (total: ${audioSockets.size})")
        cachedAudioConfig?.let {
            socket.sendBinary(it)
            Log.i(TAG, "Replayed audio config to new client (${it.size} bytes)")
        }
    }

    fun unregisterAudioSocket(socket: AudioStreamSocket) {
        audioSockets.remove(socket)
        Log.i(TAG, "Audio client disconnected (total: ${audioSockets.size})")
    }

    private var primaryFrameSeqNum: Int = 0
    private var secondaryFrameSeqNum: Int = 0

    private fun fillVideoHeader(data: ByteArray, flags: Byte, seq: Int) {
        val tsMs = android.os.SystemClock.elapsedRealtime().toInt()
        data[0] = flags
        data[1] = (seq and 0xFF).toByte()
        data[2] = ((seq shr 8) and 0xFF).toByte()
        data[3] = (tsMs and 0xFF).toByte()
        data[4] = ((tsMs shr 8) and 0xFF).toByte()
        data[5] = ((tsMs shr 16) and 0xFF).toByte()
        data[6] = ((tsMs shr 24) and 0xFF).toByte()
        data[7] = 0.toByte() // reserved
    }

    private fun buildVideoHeader(flags: Byte, seq: Int): ByteArray {
        val tsMs = android.os.SystemClock.elapsedRealtime().toInt()
        val buf = java.nio.ByteBuffer.allocate(8).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buf.put(flags)
        buf.putShort(seq.toShort())  // seqLo, seqHi (2 bytes LE)
        buf.putInt(tsMs)             // tsMs_0~3 (4 bytes LE)
        buf.put(0.toByte())          // reserved
        return buf.array()
    }

    private var cachedPrimarySpsPps: ByteArray? = null
    private var cachedSecondarySpsPps: ByteArray? = null

    fun broadcastSpsPps(data: ByteArray, channel: String = "primary") {
        val buffer = ByteArray(8 + data.size)
        fillVideoHeader(buffer, 0x02, 0)
        System.arraycopy(data, 0, buffer, 8, data.size)
        val sockets = if (channel == "secondary") secondaryVideoSockets else primaryVideoSockets
        if (channel == "secondary") cachedSecondarySpsPps = buffer else cachedPrimarySpsPps = buffer

        val deadSockets = mutableListOf<VideoStreamSocket>()
        for (socket in sockets) {
            try {
                socket.sendBinary(buffer)
            } catch (e: Exception) {
                deadSockets.add(socket)
            }
        }
        deadSockets.forEach { unregisterVideoSocket(channel, it) }
    }

    fun broadcastFrame(data: ByteArray, isKeyFrame: Boolean, channel: String = "primary") {
        val seq = if (channel == "secondary") ++secondaryFrameSeqNum else ++primaryFrameSeqNum
        val flags: Byte = if (isKeyFrame) 0x01 else 0x00
        
        // Check if this is a pre-allocated array from VideoEncoder (size > 8)
        val frame = if (data.size > 8 && data[8] == 0.toByte() && data[9] == 0.toByte() && data[10] == 0.toByte() && data[11] == 1.toByte()) {
            // New VideoEncoder: The 8-byte padding is already at the start, just fill it in
            fillVideoHeader(data, flags, seq)
            data
        } else if (data.size > 8 && (data[0] == 0.toByte() && data[1] == 0.toByte() && data[2] == 0.toByte() && data[3] == 0.toByte())) {
            // New VideoEncoder: The 8 bytes are empty. Fill them.
            fillVideoHeader(data, flags, seq)
            data
        } else {
            // Fallback for MJPEG Encoder or old pipelines that don't pre-allocate 8 bytes
            val header = buildVideoHeader(flags, seq)
            header + data
        }

        val sockets = if (channel == "secondary") secondaryVideoSockets else primaryVideoSockets
        val deadSockets = mutableListOf<VideoStreamSocket>()
        for (socket in sockets) {
            try {
                socket.sendBinary(frame)
            } catch (e: Exception) {
                deadSockets.add(socket)
            }
        }
        deadSockets.forEach { unregisterVideoSocket(channel, it) }
    }

    private var cachedAudioConfig: ByteArray? = null

    fun broadcastAudio(data: ByteArray) {
        if (data.isNotEmpty() && data[0] == 0x00.toByte()) {
            cachedAudioConfig = data
        }

        val deadSockets = mutableListOf<AudioStreamSocket>()
        for (socket in audioSockets) {
            try {
                socket.sendBinary(data)
            } catch (e: Exception) {
                deadSockets.add(socket)
            }
        }
        deadSockets.forEach { unregisterAudioSocket(it) }
    }

    fun broadcastControlMessage(json: String) {
        val deadSockets = mutableListOf<ControlSocket>()
        for (socket in controlSockets) {
            try {
                socket.send(json)
            } catch (e: Exception) {
                deadSockets.add(socket)
            }
        }
        deadSockets.forEach { unregisterControlSocket(it) }
    }
    
    // Callbacks from ControlSocket
    fun onTouchEvent(event: TouchEvent) {
        onTouchListener?.invoke(event)
    }
    
    fun onKeyframeRequest(channel: String = "primary") {
        if (channel == "secondary") onSecondaryKeyframeRequest?.invoke() else onPrimaryKeyframeRequest?.invoke()
    }
    
    fun onNetworkCongestion() {
        networkCongestionListener?.invoke()
    }
    
    fun onCodecModeRequest(mode: String) {
        onCodecModeListener?.invoke(mode)
    }
    
    fun onViewportChange(pane: String, width: Int, height: Int, layoutMode: String = "") {
        onViewportChangeListener?.invoke(pane, width, height, layoutMode)
    }
    
    fun onTextInput(text: String) {
        onTextInputListener?.invoke(text)
    }
    
    fun onKeyEvent(keyCode: Int) {
        onKeyEventListener?.invoke(keyCode)
    }
    
    fun onCompositionUpdate(backspaces: Int, text: String) {
        onCompositionUpdateListener?.invoke(backspaces, text)
    }
    
    fun onPurchaseRequest() {
        onPurchaseRequestListener?.invoke()
    }
    
    fun onGoHomeRequest() {
        onGoHomeListener?.invoke()
    }
    
    fun onAudioCodecRequest(codec: String) {
        onAudioCodecListener?.invoke(codec)
    }
    
    fun onAppLaunchRequest(pkg: String, componentName: String? = null, splitMode: Boolean = false, pane: String = if (splitMode) "secondary" else "primary") {
        onAppLaunchListener?.invoke(pkg, componentName, splitMode, pane)
    }

    override fun openWebSocket(handshake: IHTTPSession): WebSocket {
        val uri = handshake.uri
        val channel = handshake.parameters["channel"]?.firstOrNull()
            ?: if (uri.contains("secondary")) "secondary" else "primary"

        return when {
            uri.startsWith("/ws/video") -> VideoStreamSocket(handshake, this, channel)
            uri.startsWith("/ws/control") -> ControlSocket(handshake, this)
            uri.startsWith("/ws/audio") -> AudioStreamSocket(handshake, this)
            else -> VideoStreamSocket(handshake, this, channel)
        }
    }

    override fun serveHttp(session: IHTTPSession): Response {
        var uri = session.uri
        if (uri == "/") uri = "/index.html"
        
        // Handle API routes for Native Web Launcher
        if (uri == "/api/apps") {
            return serveAppList()
        } else if (uri.startsWith("/api/icon")) {
            val pkg = session.parameters["pkg"]?.firstOrNull()
            if (pkg != null) {
                return serveAppIcon(pkg)
            }
        }
        
        return serveAsset(uri)
    }
    
    private fun serveAppList(): Response {
        try {
            val pm = context.packageManager
            val ottWebUrls = mapOf(
                "com.google.android.youtube" to "https://m.youtube.com",
                "com.netflix.mediaclient" to "https://www.netflix.com",
                "com.disney.disneyplus" to "https://www.disneyplus.com",
                "com.disney.disneyplus.kr" to "https://www.disneyplus.com",
                "com.wavve.player" to "https://m.wavve.com",
                "net.cj.cjhv.gs.tving" to "https://www.tving.com",
                "com.coupang.play" to "https://www.coupangplay.com",
                "com.frograms.watcha" to "https://watcha.com"
            )
            val intent = android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
                addCategory(android.content.Intent.CATEGORY_LAUNCHER)
            }
            val resolveInfos = pm.queryIntentActivities(intent, android.content.pm.PackageManager.MATCH_ALL)
            
            val jsonArray = org.json.JSONArray()
            resolveInfos.forEach { ri ->
                if (ri.activityInfo.packageName != context.packageName) {
                    val obj = JSONObject().apply {
                        val pkgName = ri.activityInfo.packageName
                        val className = ri.activityInfo.name
                        val componentName = android.content.ComponentName(pkgName, className)
                            .flattenToShortString()
                        val label = ri.loadLabel(pm).toString()
                        put("packageName", pkgName)
                        put("className", className)
                        put("componentName", componentName)
                        put("label", label)
                        put("category", AppCategoryClassifier.classify(pkgName, label))
                        
                        // Check if it's a DRM-restricted OTT app (handled by WebBrowserActivity)
                        val webUrl = ottWebUrls[pkgName]
                        put("isWeb", webUrl != null)
                        put("webUrl", webUrl ?: JSONObject.NULL)
                    }
                    jsonArray.put(obj)
                }
            }
            
            val responseObj = JSONObject().apply {
                val isPremium = LicenseManager.isPremiumNow
                put("isPremium", isPremium)
                put("fitMode", "contain")
                put("autoFit", true)
                put("layoutMode", "single")
                put("showAdBanner", false)
                put("apps", jsonArray)
            }
            
            return newFixedLengthResponse(Response.Status.OK, "application/json", responseObj.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to serve app list", e)
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", e.message)
        }
    }
    
    private fun serveAppIcon(packageName: String): Response {
        try {
            val pm = context.packageManager
            val icon = pm.getApplicationIcon(packageName)
            val bmp = android.graphics.Bitmap.createBitmap(
                icon.intrinsicWidth.coerceAtLeast(1), 
                icon.intrinsicHeight.coerceAtLeast(1), 
                android.graphics.Bitmap.Config.ARGB_8888
            )
            val canvas = android.graphics.Canvas(bmp)
            icon.setBounds(0, 0, canvas.width, canvas.height)
            icon.draw(canvas)
            
            val stream = java.io.ByteArrayOutputStream()
            bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream)
            val bytes = stream.toByteArray()
            
            return newFixedLengthResponse(Response.Status.OK, "image/png", java.io.ByteArrayInputStream(bytes), bytes.size.toLong())
        } catch (e: Exception) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Icon not found")
        }
    }

    private fun serveAsset(uri: String): Response {
        return try {
            var path = uri.trimStart('/')
            if (path.isEmpty()) path = "index.html"
            val stream = context.assets.open("web/$path")
            val mimeType = when {
                path.endsWith(".html") -> "text/html"
                path.endsWith(".js") -> "application/javascript"
                path.endsWith(".css") -> "text/css"
                path.endsWith(".ico") -> "image/x-icon"
                path.endsWith(".png") -> "image/png"
                path.endsWith(".svg") -> "image/svg+xml"
                path.endsWith(".webp") -> "image/webp"
                path.endsWith(".jpg") || path.endsWith(".jpeg") -> "image/jpeg"
                else -> "application/octet-stream"
            }
            newChunkedResponse(Response.Status.OK, mimeType, stream)
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found")
        }
    }
}