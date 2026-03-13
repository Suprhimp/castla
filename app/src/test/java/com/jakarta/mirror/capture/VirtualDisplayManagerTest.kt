package com.jakarta.mirror.capture

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.view.Surface
import com.jakarta.mirror.shizuku.ShizukuSetup
import io.mockk.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * TDD tests for VirtualDisplayManager (Phase 5).
 * These tests define the expected behavior BEFORE implementation.
 */
@RunWith(RobolectricTestRunner::class)
class VirtualDisplayManagerTest {

    private lateinit var context: Context
    private lateinit var surface: Surface
    private lateinit var manager: VirtualDisplayManager

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        surface = mockk(relaxed = true)
        manager = VirtualDisplayManager()
    }

    @Test
    fun `createVirtualDisplay returns null when Shizuku unavailable`() {
        // Without Shizuku, should return null gracefully
        val display = manager.createVirtualDisplay(1280, 720, 160, surface)
        assertNull(display)
    }

    @Test
    fun `release does not crash when no display was created`() {
        manager.release() // should not throw
    }

    @Test
    fun `release after createVirtualDisplay cleans up`() {
        manager.createVirtualDisplay(1280, 720, 160, surface)
        manager.release() // should not throw
    }

    @Test
    fun `multiple release calls are idempotent`() {
        manager.release()
        manager.release() // should not throw
    }

    @Test
    fun `createVirtualDisplay with zero dimensions does not crash`() {
        val display = manager.createVirtualDisplay(0, 0, 0, surface)
        assertNull(display)
    }

    @Test
    fun `hasVirtualDisplay returns false initially`() {
        assertFalse(manager.hasVirtualDisplay())
    }

    @Test
    fun `getDisplayId returns -1 initially`() {
        assertEquals(-1, manager.getDisplayId())
    }

    @Test
    fun `injectInput does not crash when no display active`() {
        manager.injectInput(0, 0f, 0f, 0) // should not throw
    }
}
