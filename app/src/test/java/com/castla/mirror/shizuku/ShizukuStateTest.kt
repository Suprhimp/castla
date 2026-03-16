package com.castla.mirror.shizuku

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for ShizukuState sealed class behavior.
 */
class ShizukuStateTest {

    @Test
    fun `NotInstalled is singleton`() {
        assertSame(ShizukuState.NotInstalled, ShizukuState.NotInstalled)
    }

    @Test
    fun `NotRunning is singleton`() {
        assertSame(ShizukuState.NotRunning, ShizukuState.NotRunning)
    }

    @Test
    fun `Running with permitted true`() {
        val state = ShizukuState.Running(permitted = true)
        assertTrue(state.permitted)
    }

    @Test
    fun `Running with permitted false`() {
        val state = ShizukuState.Running(permitted = false)
        assertFalse(state.permitted)
    }

    @Test
    fun `Running data class equality`() {
        assertEquals(ShizukuState.Running(true), ShizukuState.Running(true))
        assertNotEquals(ShizukuState.Running(true), ShizukuState.Running(false))
    }

    @Test
    fun `all states are distinct types`() {
        val states: List<ShizukuState> = listOf(
            ShizukuState.NotInstalled,
            ShizukuState.NotRunning,
            ShizukuState.Running(true)
        )
        assertEquals(3, states.size)
        assertTrue(states[0] is ShizukuState.NotInstalled)
        assertTrue(states[1] is ShizukuState.NotRunning)
        assertTrue(states[2] is ShizukuState.Running)
    }
}
