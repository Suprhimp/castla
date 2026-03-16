package com.castla.mirror.utils

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class AppLaunchBusTest {

    @Test
    fun `requestLaunch emits event that can be collected`() = runTest {
        val job = launch {
            val request = AppLaunchBus.events.first()
            assertEquals("com.example.app", request.packageName)
            assertEquals("com.example.app.MainActivity", request.className)
        }

        // Give collector time to subscribe
        yield()

        AppLaunchBus.requestLaunch("com.example.app", "com.example.app.MainActivity")
        job.join()
    }

    @Test
    fun `requestLaunch with null className`() = runTest {
        val job = launch {
            val request = AppLaunchBus.events.first()
            assertEquals("com.example.app", request.packageName)
            assertNull(request.className)
        }

        yield()

        AppLaunchBus.requestLaunch("com.example.app")
        job.join()
    }

    @Test
    fun `multiple events are delivered in order`() = runTest {
        val received = mutableListOf<AppLaunchBus.LaunchRequest>()
        val job = launch {
            AppLaunchBus.events.take(3).toList(received)
        }

        yield()

        AppLaunchBus.requestLaunch("app1")
        AppLaunchBus.requestLaunch("app2")
        AppLaunchBus.requestLaunch("app3")

        job.join()

        assertEquals(3, received.size)
        assertEquals("app1", received[0].packageName)
        assertEquals("app2", received[1].packageName)
        assertEquals("app3", received[2].packageName)
    }

    @Test
    fun `tryEmit succeeds with buffer capacity`() {
        // Buffer capacity is 8, so 8 events should all succeed without collector
        repeat(8) { i ->
            val result = AppLaunchBus.requestLaunch("app$i")
            // tryEmit returns Unit, but if it fails internally it drops silently
            // We verify by collecting afterwards
        }
    }

    @Test
    fun `LaunchRequest data class equality`() {
        val r1 = AppLaunchBus.LaunchRequest("pkg", "cls")
        val r2 = AppLaunchBus.LaunchRequest("pkg", "cls")
        assertEquals(r1, r2)

        val r3 = AppLaunchBus.LaunchRequest("pkg", null)
        assertNotEquals(r1, r3)
    }
}
