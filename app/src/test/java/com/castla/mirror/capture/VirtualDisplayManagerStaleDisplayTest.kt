package com.castla.mirror.capture

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.castla.mirror.shizuku.IPrivilegedService
import io.mockk.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test

/**
 * Tests for stale display detection in VirtualDisplayManager.
 * When the PrivilegedService throws SecurityException (stale display ID),
 * VDM should catch it, invalidate the displayId, and return false.
 */
class VirtualDisplayManagerStaleDisplayTest {

    private lateinit var manager: VirtualDisplayManager
    private lateinit var mockService: IPrivilegedService

    @Before
    fun setup() {
        // Mock Android framework statics to avoid "Method not mocked" errors
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0

        mockkStatic(Looper::class)
        val mockLooper = mockk<Looper>(relaxed = true)
        every { Looper.getMainLooper() } returns mockLooper
        every { Looper.myLooper() } returns mockLooper

        // Mock Handler construction
        mockkConstructor(Handler::class)
        every { anyConstructed<Handler>().post(any()) } answers {
            // Execute the runnable immediately
            firstArg<Runnable>().run()
            true
        }

        manager = VirtualDisplayManager()
        mockService = mockk(relaxed = true)

        // Inject mock service and a valid displayId via reflection
        setField("privilegedService", mockService)
        setField("displayId", 42)
        setField("isBound", true)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun setField(name: String, value: Any?) {
        val field = VirtualDisplayManager::class.java.getDeclaredField(name)
        field.isAccessible = true
        field.set(manager, value)
    }

    // ── launchAppOnDisplay ──

    @Test
    fun `launchAppOnDisplay succeeds normally`() {
        val result = manager.launchAppOnDisplay("com.example.app")
        assertTrue(result)
        assertEquals(42, manager.getDisplayId())
    }

    @Test
    fun `launchAppOnDisplay returns false and invalidates on SecurityException`() {
        every { mockService.launchAppOnDisplay(42, "com.example.app") } throws SecurityException("Permission Denial")

        val result = manager.launchAppOnDisplay("com.example.app")
        assertFalse(result)
        assertEquals(-1, manager.getDisplayId())
        assertFalse(manager.hasVirtualDisplay())
    }

    @Test
    fun `launchAppOnDisplay returns false on generic exception without invalidating`() {
        every { mockService.launchAppOnDisplay(42, "com.example.app") } throws RuntimeException("some error")

        val result = manager.launchAppOnDisplay("com.example.app")
        assertFalse(result)
        // Generic exceptions should NOT invalidate displayId
        assertEquals(42, manager.getDisplayId())
    }

    @Test
    fun `launchAppOnDisplay returns false when displayId is negative`() {
        setField("displayId", -1)
        val result = manager.launchAppOnDisplay("com.example.app")
        assertFalse(result)
    }

    @Test
    fun `launchAppOnDisplay returns false when packageName is empty`() {
        val result = manager.launchAppOnDisplay("")
        assertFalse(result)
    }

    // ── launchAppWithExtraOnDisplay ──

    @Test
    fun `launchAppWithExtraOnDisplay succeeds normally`() {
        val result = manager.launchAppWithExtraOnDisplay("com.example.app", "key", "value")
        assertTrue(result)
        assertEquals(42, manager.getDisplayId())
    }

    @Test
    fun `launchAppWithExtraOnDisplay returns false and invalidates on SecurityException`() {
        every {
            mockService.launchAppWithExtraOnDisplay(42, "com.example.app", "key", "value")
        } throws SecurityException("Permission Denial")

        val result = manager.launchAppWithExtraOnDisplay("com.example.app", "key", "value")
        assertFalse(result)
        assertEquals(-1, manager.getDisplayId())
    }

    @Test
    fun `launchAppWithExtraOnDisplay returns false on generic exception without invalidating`() {
        every {
            mockService.launchAppWithExtraOnDisplay(42, "com.example.app", "key", "value")
        } throws RuntimeException("error")

        val result = manager.launchAppWithExtraOnDisplay("com.example.app", "key", "value")
        assertFalse(result)
        assertEquals(42, manager.getDisplayId())
    }

    // ── resizeDisplay ──

    @Test
    fun `resizeDisplay succeeds normally`() {
        val result = manager.resizeDisplay(42, 1280, 720, 160)
        assertTrue(result)
    }

    @Test
    fun `resizeDisplay returns false on exception`() {
        every {
            mockService.resizeVirtualDisplay(42, 1280, 720, 160)
        } throws IllegalStateException("Virtual display 42 not found")

        val result = manager.resizeDisplay(42, 1280, 720, 160)
        assertFalse(result)
    }

    @Test
    fun `resizeDisplay returns false when displayId is negative`() {
        val result = manager.resizeDisplay(-1, 1280, 720, 160)
        assertFalse(result)
    }

    // ── launchHomeOnDisplay ──

    @Test
    fun `launchHomeOnDisplay succeeds normally`() {
        val result = manager.launchHomeOnDisplay()
        assertTrue(result)
    }

    @Test
    fun `launchHomeOnDisplay returns false when displayId is negative`() {
        setField("displayId", -1)
        val result = manager.launchHomeOnDisplay()
        assertFalse(result)
    }

    @Test
    fun `launchHomeOnDisplay returns false on exception`() {
        every { mockService.launchHomeOnDisplay(42) } throws RuntimeException("error")

        val result = manager.launchHomeOnDisplay()
        assertFalse(result)
    }

    // ── hasVirtualDisplay state transitions ──

    @Test
    fun `hasVirtualDisplay returns true when displayId is valid and service bound`() {
        assertTrue(manager.hasVirtualDisplay())
    }

    @Test
    fun `hasVirtualDisplay returns false after SecurityException invalidates display`() {
        every { mockService.launchAppOnDisplay(42, "com.example.app") } throws SecurityException("stale")

        assertTrue(manager.hasVirtualDisplay())
        manager.launchAppOnDisplay("com.example.app")
        assertFalse(manager.hasVirtualDisplay())
    }

    @Test
    fun `hasVirtualDisplay returns false when service is null`() {
        setField("privilegedService", null)
        assertFalse(manager.hasVirtualDisplay())
    }
}
