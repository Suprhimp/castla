package com.castla.mirror.server

import android.content.Context
import android.content.res.AssetManager
import fi.iki.elonen.NanoHTTPD
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream

@RunWith(RobolectricTestRunner::class)
class MirrorServerAuthTest {

    private lateinit var context: Context
    private lateinit var assetManager: AssetManager
    private lateinit var server: MirrorServer

    @Before
    fun setup() {
        assetManager = mockk(relaxed = true)
        context = mockk(relaxed = true)
        every { context.assets } returns assetManager
        // Since we removed PIN, there is no sessionPin param and the second argument was removed.
        server = MirrorServer(context)
    }

    private fun mockSession(uri: String, params: Map<String, String> = emptyMap()): NanoHTTPD.IHTTPSession {
        val session = mockk<NanoHTTPD.IHTTPSession>(relaxed = true)
        every { session.uri } returns uri
        every { session.parms } returns params
        every { session.remoteIpAddress } returns "192.168.1.100"
        return session
    }

    /**
     * Call the protected serveHttp method via reflection for testing.
     */
    private fun callServeHttp(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val method = MirrorServer::class.java.getDeclaredMethod("serveHttp", NanoHTTPD.IHTTPSession::class.java)
        method.isAccessible = true
        return method.invoke(server, session) as NanoHTTPD.Response
    }

    @Test
    fun `root serves index page directly`() {
        every { assetManager.open("web/index.html") } returns ByteArrayInputStream("<html>test</html>".toByteArray())
        val session = mockSession("/", emptyMap())
        val response = callServeHttp(session)
        assertEquals(NanoHTTPD.Response.Status.OK, response.status)
        assertEquals("text/html", response.mimeType)
    }

    @Test
    fun `JS sub-resource serves correctly`() {
        every { assetManager.open("web/js/main.js") } returns ByteArrayInputStream("console.log('test')".toByteArray())
        val session = mockSession("/js/main.js", emptyMap())
        val response = callServeHttp(session)
        assertEquals(NanoHTTPD.Response.Status.OK, response.status)
        assertEquals("application/javascript", response.mimeType)
    }

    @Test
    fun `CSS sub-resource serves correctly`() {
        every { assetManager.open("web/css/player.css") } returns ByteArrayInputStream("body{}".toByteArray())
        val session = mockSession("/css/player.css", emptyMap())
        val response = callServeHttp(session)
        assertEquals(NanoHTTPD.Response.Status.OK, response.status)
        assertEquals("text/css", response.mimeType)
    }

    @Test
    fun `PNG sub-resource serves correctly`() {
        every { assetManager.open("web/img/logo.png") } returns ByteArrayInputStream(byteArrayOf(0x89.toByte(), 0x50))
        val session = mockSession("/img/logo.png", emptyMap())
        val response = callServeHttp(session)
        assertEquals(NanoHTTPD.Response.Status.OK, response.status)
        assertEquals("image/png", response.mimeType)
    }

    @Test
    fun `missing asset returns 404`() {
        every { assetManager.open(any()) } throws java.io.IOException("not found")
        val session = mockSession("/nonexistent.js", emptyMap())
        val response = callServeHttp(session)
        assertEquals(NanoHTTPD.Response.Status.NOT_FOUND, response.status)
    }
}