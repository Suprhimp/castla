package com.castla.mirror.server

import android.content.Context
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import fi.iki.elonen.NanoWSD.WebSocket
import org.json.JSONObject
import com.castla.mirror.billing.LicenseManager

data class TouchEvent(val action: String, val x: Float, val y: Float, val pointerId: Int)

class MirrorServer(private val context: Context) : NanoWSD(DEFAULT_PORT) {

    companion object {
        private const val TAG = "MirrorServer"
        const val DEFAULT_PORT = 9090
    }

    private val videoSockets = mutableSetOf<VideoStreamSocket>()
    private val controlSockets = mutableSetOf<ControlSocket>()
    private val audioSockets = mutableSetOf<AudioStreamSocket>()

    private var onTouchListener: ((TouchEvent) -> Unit)? = null
    private var onCodecModeListener: ((String) -> Unit)? = null
    private var onViewportChangeListener: ((Int, Int) -> Unit)? = null
    private var onTextInputListener: ((String) -> Unit)? = null
    private var onKeyEventListener: ((Int) -> Unit)? = null
    private var onCompositionUpdateListener: ((Int, String) -> Unit)? = null
    private var onAudioCodecListener: ((String) -> Unit)? = null
    private var onKeyframeRequest: (() -> Unit)? = null
    
    // Web Launcher specific listeners
    private var onGoHomeListener: (() -> Unit)? = null
    private var onAppLaunchListener: ((String, String?) -> Unit)? = null

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

    fun setViewportChangeListener(listener: (Int, Int) -> Unit) {
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

    fun setKeyframeRequester(requester: () -> Unit) {
        onKeyframeRequest = requester
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
    
    fun setAppLaunchListener(listener: (String, String?) -> Unit) {
        onAppLaunchListener = listener
    }

    private fun updateConnectionState() {
        val connected = videoSockets.isNotEmpty() || controlSockets.isNotEmpty()
        if (connected != isBrowserConnected) {
            isBrowserConnected = connected
            onBrowserConnectionListener?.invoke(connected)
        }
    }

    fun registerVideoSocket(socket: VideoStreamSocket) {
        videoSockets.add(socket)
        Log.i(TAG, "Video client connected (total: ${videoSockets.size})")

        cachedSpsPps?.let {
            socket.sendBinary(it)
            Log.i(TAG, "Sent cached SPS/PPS to new video client")
        }

        updateConnectionState()
        onKeyframeRequest?.invoke()
    }

    fun unregisterVideoSocket(socket: VideoStreamSocket) {
        videoSockets.remove(socket)
        Log.i(TAG, "Video client disconnected (total: ${videoSockets.size})")
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

    private var frameSeqNum: Int = 0

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

    fun broadcastSpsPps(data: ByteArray) {
        val buffer = ByteArray(8 + data.size)
        fillVideoHeader(buffer, 0x02, 0)
        System.arraycopy(data, 0, buffer, 8, data.size)
        cachedSpsPps = buffer

        val deadSockets = mutableListOf<VideoStreamSocket>()
        for (socket in videoSockets) {
            try {
                socket.sendBinary(buffer)
            } catch (e: Exception) {
                deadSockets.add(socket)
            }
        }
        deadSockets.forEach { unregisterVideoSocket(it) }
    }

    fun broadcastFrame(data: ByteArray, isKeyFrame: Boolean) {
        val buffer = ByteArray(8 + data.size)
        val flags: Byte = if (isKeyFrame) 0x01 else 0x00
        val seq = ++frameSeqNum

        fillVideoHeader(buffer, flags, seq)
        System.arraycopy(data, 0, buffer, 8, data.size)

        val deadSockets = mutableListOf<VideoStreamSocket>()
        for (socket in videoSockets) {
            try {
                socket.sendBinary(buffer)
            } catch (e: Exception) {
                deadSockets.add(socket)
            }
        }
        deadSockets.forEach { unregisterVideoSocket(it) }
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
    
    fun onKeyframeRequest() {
        onKeyframeRequest?.invoke()
    }
    
    fun onCodecModeRequest(mode: String) {
        onCodecModeListener?.invoke(mode)
    }
    
    fun onViewportChange(width: Int, height: Int) {
        onViewportChangeListener?.invoke(width, height)
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
    
    fun onAppLaunchRequest(pkg: String, componentName: String? = null) {
        onAppLaunchListener?.invoke(pkg, componentName)
    }

    override fun openWebSocket(handshake: IHTTPSession): WebSocket {
        val uri = handshake.uri
        if (uri.startsWith("/ws/video") && videoSockets.isNotEmpty()) {
            Log.w(TAG, "Rejecting second video connection from ${handshake.remoteIpAddress}")
            // Just use a regular socket since UnauthorizedSocket doesn't exist
            return VideoStreamSocket(handshake, this)
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
                        put("category", classifyAppString(pkgName, label))
                        
                        // Check if it's a DRM-restricted OTT app (handled by WebBrowserActivity)
                        val isWeb = setOf(
                            "com.google.android.youtube", "com.netflix.mediaclient", 
                            "com.disney.disneyplus", "com.disney.disneyplus.kr",
                            "com.wavve.player", "net.cj.cjhv.gs.tving", 
                            "com.coupang.play", "com.frograms.watcha"
                        ).contains(pkgName)
                        put("isWeb", isWeb)
                    }
                    jsonArray.put(obj)
                }
            }
            
            val responseObj = JSONObject().apply {
                val isPremium = LicenseManager.isPremiumNow
                put("isPremium", isPremium)
                put("fitMode", if (isPremium) "cover" else "contain")
                put("autoFit", isPremium)
                put("layoutMode", "single")
                put("showAdBanner", !isPremium)
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
    
    private fun classifyAppString(pkg: String, label: String): String {
        val p = pkg.lowercase()
        val l = label.lowercase()
        
        val navPkgs = setOf("com.skt.tmap", "com.skt.skaf.l001mtm091", "com.locnall.kimgisa", "com.kakao.taxi", "com.kakaonavi", "com.nhn.android.nmap", "com.nhn.android.navermap", "com.google.android.apps.maps", "com.waze", "net.daum.android.map", "com.thinkware.inavic", "com.mnav.atlan", "com.mappy.app", "com.here.app.maps", "com.mapbox.mapboxandroiddemo")
        val videoPkgs = setOf("com.google.android.youtube", "com.netflix.mediaclient", "com.disney.disneyplus", "com.disney.disneyplus.kr", "com.wavve.player", "net.cj.cjhv.gs.tving", "com.coupang.play", "com.amazon.avod.thirdpartyclient", "com.amazon.avod", "tv.twitch.android.app", "com.frograms.watcha", "kr.co.captv.pooq", "com.hbo.hbomax", "com.apple.atve.androidtv.appletv", "com.bbc.iplayer.android", "com.sbs.vod.sbsnow", "com.kbs.kbsn", "com.imbc.mbcvod", "com.vikinc.vikinchannel", "kr.co.nowcom.mobile.aladdin", "com.dmp.hoyatv")
        val musicPkgs = setOf("com.spotify.music", "com.google.android.apps.youtube.music", "com.iloen.melon", "com.kt.android.genie", "com.sktelecom.flomusic", "com.naver.vibe", "com.soribada.android", "com.soundcloud.android", "com.pandora.android", "com.amazon.mp3", "com.apple.android.music", "com.shazam.android", "fm.castbox.audiobook.radio.podcast", "com.samsung.android.app.podcast", "com.google.android.apps.podcasts")
        
        if (navPkgs.any { p.startsWith(it) } || p.contains("map") || p.contains("navi") || p.contains("waze") || l.contains("지도") || l.contains("내비")) return "NAVIGATION"
        if (videoPkgs.any { p.startsWith(it) } || p.contains("video") || p.contains("movie") || p.contains("ott") || p.contains("tv") || l.contains("동영상") || l.contains("영화")) return "VIDEO"
        if (musicPkgs.any { p.startsWith(it) } || p.contains("music") || p.contains("audio") || p.contains("radio") || l.contains("음악") || l.contains("라디오")) return "MUSIC"
        
        return "OTHER"
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
                else -> "application/octet-stream"
            }
            newChunkedResponse(Response.Status.OK, mimeType, stream)
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found")
        }
    }
}
