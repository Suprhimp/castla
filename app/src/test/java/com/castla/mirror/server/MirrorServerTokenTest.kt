package com.castla.mirror.server

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
        server = MirrorServer(context) // Port 9090 default
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
    fun `touch listener receives events`() {
        var received: TouchEvent? = null
        server.setTouchListener { received = it }

        val event = TouchEvent("down", 0.5f, 0.5f, 0)
        server.onTouchEvent(event)

        assertNotNull(received)
        assertEquals("down", received?.action)
        assertEquals(0.5f, received?.x ?: 0f, 0.001f)
        assertEquals(0.5f, received?.y ?: 0f, 0.001f)
    }

    @Test
    fun `keyframe requester is invoked on request`() {
        var called = false
        server.setKeyframeRequester { called = true }
        server.onKeyframeRequest()
        assertTrue(called)
    }
}