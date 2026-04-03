package com.castla.mirror.policy

import org.junit.Assert.*
import org.junit.Test

class DisconnectPolicyTest {

    // ── Grace period ──

    @Test
    fun `default grace is 3 seconds when screen is on`() {
        assertEquals(3_000L, DisconnectPolicy.graceMs(isScreenOff = false))
    }

    @Test
    fun `extended grace is 15 seconds when screen is off`() {
        assertEquals(15_000L, DisconnectPolicy.graceMs(isScreenOff = true))
    }

    // ── Teardown decision ──

    @Test
    fun `should not teardown when screen is off regardless of browser state`() {
        assertFalse(DisconnectPolicy.shouldTeardown(isScreenOff = true, isBrowserConnected = false))
        assertFalse(DisconnectPolicy.shouldTeardown(isScreenOff = true, isBrowserConnected = true))
    }

    @Test
    fun `should teardown when screen is on and browser disconnected`() {
        assertTrue(DisconnectPolicy.shouldTeardown(isScreenOff = false, isBrowserConnected = false))
    }

    @Test
    fun `should not teardown when browser is connected`() {
        assertFalse(DisconnectPolicy.shouldTeardown(isScreenOff = false, isBrowserConnected = true))
    }

    // ── Edge cases ──

    @Test
    fun `grace constants are positive`() {
        assertTrue(DisconnectPolicy.DEFAULT_GRACE_MS > 0)
        assertTrue(DisconnectPolicy.SCREEN_OFF_GRACE_MS > 0)
    }

    @Test
    fun `screen off grace is longer than default`() {
        assertTrue(DisconnectPolicy.SCREEN_OFF_GRACE_MS > DisconnectPolicy.DEFAULT_GRACE_MS)
    }
}
