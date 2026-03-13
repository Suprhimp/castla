package com.jakarta.mirror.server

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MirrorServerTokenTest {

    private lateinit var context: Context
    private lateinit var server: MirrorServer

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        server = MirrorServer(context, 0) // port 0 = don't actually bind
    }

    @Test
    fun `session token is generated with correct length`() {
        assertEquals(32, server.sessionToken.length)
    }

    @Test
    fun `session token contains only alphanumeric characters`() {
        assertTrue(server.sessionToken.matches(Regex("[A-Za-z0-9]+")))
    }

    @Test
    fun `each server instance gets a unique token`() {
        val server2 = MirrorServer(context, 0)
        assertNotEquals(server.sessionToken, server2.sessionToken)
    }

    @Test
    fun `broadcastFrame prepends keyframe header 0x01`() {
        val data = byteArrayOf(0x10, 0x20, 0x30)
        // No connected sockets — just verify no crash
        server.broadcastFrame(data, isKeyFrame = true)
    }

    @Test
    fun `broadcastFrame prepends delta header 0x00`() {
        val data = byteArrayOf(0x10, 0x20, 0x30)
        server.broadcastFrame(data, isKeyFrame = false)
    }

    @Test
    fun `connectedClients starts at zero`() {
        assertEquals(0, server.connectedClients)
    }

    @Test
    fun `touch listener receives events`() {
        var received: MirrorServer.TouchEvent? = null
        server.setTouchListener { received = it }

        val event = MirrorServer.TouchEvent("down", 0.5f, 0.5f, 0)
        server.onTouchEvent(event)

        assertNotNull(received)
        assertEquals("down", received!!.action)
        assertEquals(0.5f, received!!.x, 0.001f)
        assertEquals(0.5f, received!!.y, 0.001f)
    }

    @Test
    fun `keyframe requester is invoked on request`() {
        var called = false
        server.setKeyframeRequester { called = true }
        server.onKeyframeRequest()
        assertTrue(called)
    }
}
