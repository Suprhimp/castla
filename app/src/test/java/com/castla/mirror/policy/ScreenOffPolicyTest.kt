package com.castla.mirror.policy

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ScreenOffPolicyTest {

    private lateinit var policy: ScreenOffPolicy

    @Before
    fun setup() {
        policy = ScreenOffPolicy()
    }

    // ── Initial state ──

    @Test
    fun `initial state is ACTIVE`() {
        assertEquals(ScreenOffState.ACTIVE, policy.state)
    }

    // ── Screen-off transitions ──

    @Test
    fun `screen off triggers PANEL_OFF strategy`() {
        val action = policy.onScreenOff(panelOffSupported = true)
        assertEquals(ScreenOffAction.TURN_PANEL_OFF, action)
        assertEquals(ScreenOffState.PANEL_OFF_PENDING, policy.state)
    }

    @Test
    fun `screen off falls back to KEEP_ALIVE when panel-off not supported`() {
        val action = policy.onScreenOff(panelOffSupported = false)
        assertEquals(ScreenOffAction.START_KEEP_ALIVE, action)
        assertEquals(ScreenOffState.KEEP_ALIVE_ACTIVE, policy.state)
    }

    @Test
    fun `panel-off confirmed transitions to PANEL_OFF_ACTIVE`() {
        policy.onScreenOff(panelOffSupported = true)
        policy.onPanelOffResult(success = true)
        assertEquals(ScreenOffState.PANEL_OFF_ACTIVE, policy.state)
    }

    @Test
    fun `panel-off failure falls back to KEEP_ALIVE`() {
        policy.onScreenOff(panelOffSupported = true)
        val fallback = policy.onPanelOffResult(success = false)
        assertEquals(ScreenOffAction.START_KEEP_ALIVE, fallback)
        assertEquals(ScreenOffState.KEEP_ALIVE_ACTIVE, policy.state)
    }

    @Test
    fun `panel-off failure marks device as unsupported`() {
        policy.onScreenOff(panelOffSupported = true)
        policy.onPanelOffResult(success = false)
        assertFalse(policy.isPanelOffSupported)
    }

    // ── Screen-on transitions ──

    @Test
    fun `screen on from PANEL_OFF_ACTIVE restores panel`() {
        policy.onScreenOff(panelOffSupported = true)
        policy.onPanelOffResult(success = true)
        val action = policy.onScreenOn()
        assertEquals(ScreenOffAction.RESTORE_PANEL, action)
        assertEquals(ScreenOffState.ACTIVE, policy.state)
    }

    @Test
    fun `screen on from KEEP_ALIVE stops keep-alive`() {
        policy.onScreenOff(panelOffSupported = false)
        val action = policy.onScreenOn()
        assertEquals(ScreenOffAction.STOP_KEEP_ALIVE, action)
        assertEquals(ScreenOffState.ACTIVE, policy.state)
    }

    @Test
    fun `screen on from ACTIVE is no-op`() {
        val action = policy.onScreenOn()
        assertEquals(ScreenOffAction.NONE, action)
        assertEquals(ScreenOffState.ACTIVE, policy.state)
    }

    // ── Repeated screen-off while already off ──

    @Test
    fun `screen off while already PANEL_OFF_ACTIVE is no-op`() {
        policy.onScreenOff(panelOffSupported = true)
        policy.onPanelOffResult(success = true)
        val action = policy.onScreenOff(panelOffSupported = true)
        assertEquals(ScreenOffAction.NONE, action)
        assertEquals(ScreenOffState.PANEL_OFF_ACTIVE, policy.state)
    }

    @Test
    fun `screen off while KEEP_ALIVE_ACTIVE is no-op`() {
        policy.onScreenOff(panelOffSupported = false)
        val action = policy.onScreenOff(panelOffSupported = false)
        assertEquals(ScreenOffAction.NONE, action)
        assertEquals(ScreenOffState.KEEP_ALIVE_ACTIVE, policy.state)
    }

    // ── DisconnectPolicy integration ──

    @Test
    fun `isScreenOff returns true when panel-off active`() {
        policy.onScreenOff(panelOffSupported = true)
        policy.onPanelOffResult(success = true)
        assertTrue(policy.isScreenOff)
    }

    @Test
    fun `isScreenOff returns true when keep-alive active`() {
        policy.onScreenOff(panelOffSupported = false)
        assertTrue(policy.isScreenOff)
    }

    @Test
    fun `isScreenOff returns false when active`() {
        assertFalse(policy.isScreenOff)
    }

    @Test
    fun `isScreenOff returns true during panel-off pending`() {
        policy.onScreenOff(panelOffSupported = true)
        assertTrue(policy.isScreenOff)
    }

    // ── Panel-off support override ──

    @Test
    fun `panel-off supported by default`() {
        assertTrue(policy.isPanelOffSupported)
    }

    @Test
    fun `after failure, next screen-off uses keep-alive`() {
        policy.onScreenOff(panelOffSupported = true)
        policy.onPanelOffResult(success = false)
        policy.onScreenOn()

        // Next screen-off: caller should check isPanelOffSupported
        val action = policy.onScreenOff(panelOffSupported = policy.isPanelOffSupported)
        assertEquals(ScreenOffAction.START_KEEP_ALIVE, action)
    }

    // ── Reset ──

    @Test
    fun `reset returns to ACTIVE and clears panel-off support flag`() {
        policy.onScreenOff(panelOffSupported = true)
        policy.onPanelOffResult(success = false)
        policy.reset()
        assertEquals(ScreenOffState.ACTIVE, policy.state)
        assertTrue(policy.isPanelOffSupported)
    }
}
