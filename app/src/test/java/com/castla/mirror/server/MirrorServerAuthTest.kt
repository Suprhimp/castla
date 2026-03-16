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
    fun `root without pin serves PIN entry page`() {
        every { assetManager.open("web/pin.html") } returns ByteArrayInputStream("<html>pin</html>".toByteArray())
        val session = mockSession("/", emptyMap())
        val response = callServeHttp(session)
        assertEquals(NanoHTTPD.Response.Status.OK, response.status)
    }

    @Test
    fun `root with valid pin serves stream page`() {
        every { assetManager.open("web/index.html") } returns ByteArrayInputStream("<html>test</html>".toByteArray())
        val session = mockSession("/", mapOf("pin" to server.sessionPin))
        val response = callServeHttp(session)
        assertEquals(NanoHTTPD.Response.Status.OK, response.status)
    }

    @Test
    fun `root with wrong pin serves PIN entry page`() {
        every { assetManager.open("web/pin.html") } returns ByteArrayInputStream("<html>pin</html>".toByteArray())
        val session = mockSession("/", mapOf("pin" to "9999"))
        val response = callServeHttp(session)
        assertEquals(NanoHTTPD.Response.Status.OK, response.status)
    }

    @Test
    fun `non-root page with wrong pin returns 403`() {
        val session = mockSession("/index.html", mapOf("pin" to "wrongpin"))
        val response = callServeHttp(session)
        assertEquals(NanoHTTPD.Response.Status.FORBIDDEN, response.status)
    }

    @Test
    fun `JS sub-resource skips pin check`() {
        every { assetManager.open("web/js/main.js") } returns ByteArrayInputStream("console.log('test')".toByteArray())
        val session = mockSession("/js/main.js", emptyMap())
        val response = callServeHttp(session)
        assertEquals(NanoHTTPD.Response.Status.OK, response.status)
    }

    @Test
    fun `CSS sub-resource skips pin check`() {
        every { assetManager.open("web/css/player.css") } returns ByteArrayInputStream("body{}".toByteArray())
        val session = mockSession("/css/player.css", emptyMap())
        val response = callServeHttp(session)
        assertEquals(NanoHTTPD.Response.Status.OK, response.status)
    }

    @Test
    fun `PNG sub-resource skips pin check`() {
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
    fun `index_html request with pin gets correct MIME type`() {
        every { assetManager.open("web/index.html") } returns ByteArrayInputStream("<html></html>".toByteArray())
        val session = mockSession("/index.html", mapOf("pin" to server.sessionPin))
        val response = callServeHttp(session)
        assertEquals("text/html", response.mimeType)
    }
}
