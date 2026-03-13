package com.jakarta.mirror.server

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
        server = MirrorServer(context, 0)
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
    fun `HTML page request without token returns 403`() {
        every { assetManager.open(any()) } returns ByteArrayInputStream("test".toByteArray())
        val session = mockSession("/", emptyMap())
        val response = callServeHttp(session)
        assertEquals(NanoHTTPD.Response.Status.FORBIDDEN, response.status)
    }

    @Test
    fun `HTML page request with valid token returns 200`() {
        every { assetManager.open("web/index.html") } returns ByteArrayInputStream("<html>test</html>".toByteArray())
        val session = mockSession("/", mapOf("token" to server.sessionToken))
        val response = callServeHttp(session)
        assertEquals(NanoHTTPD.Response.Status.OK, response.status)
    }

    @Test
    fun `HTML page request with wrong token returns 403`() {
        val session = mockSession("/", mapOf("token" to "wrongtoken"))
        val response = callServeHttp(session)
        assertEquals(NanoHTTPD.Response.Status.FORBIDDEN, response.status)
    }

    @Test
    fun `JS sub-resource skips token check`() {
        every { assetManager.open("web/js/main.js") } returns ByteArrayInputStream("console.log('test')".toByteArray())
        val session = mockSession("/js/main.js", emptyMap())
        val response = callServeHttp(session)
        assertEquals(NanoHTTPD.Response.Status.OK, response.status)
    }

    @Test
    fun `CSS sub-resource skips token check`() {
        every { assetManager.open("web/css/player.css") } returns ByteArrayInputStream("body{}".toByteArray())
        val session = mockSession("/css/player.css", emptyMap())
        val response = callServeHttp(session)
        assertEquals(NanoHTTPD.Response.Status.OK, response.status)
    }

    @Test
    fun `PNG sub-resource skips token check`() {
        every { assetManager.open("web/img/logo.png") } returns ByteArrayInputStream(byteArrayOf(0x89.toByte(), 0x50))
        val session = mockSession("/img/logo.png", emptyMap())
        val response = callServeHttp(session)
        assertEquals(NanoHTTPD.Response.Status.OK, response.status)
    }

    @Test
    fun `missing asset returns 404`() {
        every { assetManager.open(any()) } throws java.io.IOException("not found")
        val session = mockSession("/nonexistent.js", emptyMap())
        val response = callServeHttp(session)
        assertEquals(NanoHTTPD.Response.Status.NOT_FOUND, response.status)
    }

    @Test
    fun `index_html request with token gets correct MIME type`() {
        every { assetManager.open("web/index.html") } returns ByteArrayInputStream("<html></html>".toByteArray())
        val session = mockSession("/index.html", mapOf("token" to server.sessionToken))
        val response = callServeHttp(session)
        assertEquals("text/html", response.mimeType)
    }
}
