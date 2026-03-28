package com.castla.mirror.server

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for TouchEvent data class.
 */
class TouchEventTest {

    @Test
    fun `TouchEvent stores all fields correctly`() {
        val event = TouchEvent("down", 0.5f, 0.75f, 2)
        assertEquals("down", event.action)
        assertEquals(0.5f, event.x, 0.001f)
        assertEquals(0.75f, event.y, 0.001f)
        assertEquals(2, event.pointerId)
    }

    @Test
    fun `TouchEvent data class equality`() {
        val e1 = TouchEvent("down", 0.5f, 0.5f, 0)
        val e2 = TouchEvent("down", 0.5f, 0.5f, 0)
        assertEquals(e1, e2)
    }

    @Test
    fun `TouchEvent data class inequality`() {
        val e1 = TouchEvent("down", 0.5f, 0.5f, 0)
        val e2 = TouchEvent("up", 0.5f, 0.5f, 0)
        assertNotEquals(e1, e2)
    }

    @Test
    fun `TouchEvent copy works`() {
        val original = TouchEvent("down", 0.5f, 0.5f, 0)
        val moved = original.copy(action = "move", x = 0.6f)
        assertEquals("move", moved.action)
        assertEquals(0.6f, moved.x, 0.001f)
        assertEquals(0.5f, moved.y, 0.001f) // unchanged
    }

    @Test
    fun `boundary coordinates are valid`() {
        // Normalized coords should be 0-1
        val topLeft = TouchEvent("down", 0.0f, 0.0f, 0)
        val bottomRight = TouchEvent("down", 1.0f, 1.0f, 0)
        assertTrue(topLeft.x >= 0f && topLeft.x <= 1f)
        assertTrue(bottomRight.x >= 0f && bottomRight.x <= 1f)
    }
}
