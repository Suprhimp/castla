package com.castla.mirror.service

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class AudioCaptureOrchestratorTest {

    private lateinit var orch: AudioCaptureOrchestrator
    private lateinit var log: MutableList<String>
    private var deferScheduled: Boolean = false
    private var deferCancelled: Boolean = false

    @Before
    fun setup() {
        log = mutableListOf()
        deferScheduled = false
        deferCancelled = false
        orch = AudioCaptureOrchestrator(object : AudioCaptureOrchestrator.Actions {
            override fun startCapture(codec: String?) { log.add("start:${codec ?: "default"}") }
            override fun stopCapture() { log.add("stop") }
            override fun grantAudioPermission() { log.add("grant") }
            override fun scheduleDeferredStart(delayMs: Long): Any? {
                deferScheduled = true
                log.add("defer:${delayMs}ms")
                return "timer"
            }
            override fun cancelDeferredStart(handle: Any?) {
                deferCancelled = true
                log.add("cancel_defer")
            }
        })
    }

    // ── Audio OFF blocks everything ──

    @Test
    fun `audio disabled - no capture even with browser connected`() {
        orch.audioEnabled = false
        orch.browserConnected = true
        val result = orch.ensure()
        assertEquals(AudioCaptureOrchestrator.EnsureResult.STOPPED, result)
        assertFalse(orch.captureActive)
        assertTrue(log.isEmpty())
    }

    @Test
    fun `audio disabled - codec request ignored`() {
        orch.audioEnabled = false
        orch.browserConnected = true
        val result = orch.ensure(codecOverride = "pcm")
        assertEquals(AudioCaptureOrchestrator.EnsureResult.STOPPED, result)
        assertFalse(orch.captureActive)
    }

    @Test
    fun `audio disabled - stops active capture`() {
        startCapturing("opus")
        log.clear()

        orch.audioEnabled = false
        val result = orch.ensure()
        assertEquals(AudioCaptureOrchestrator.EnsureResult.STOPPED, result)
        assertFalse(orch.captureActive)
        assertTrue(log.contains("stop"))
    }

    // ── First start defers until audio socket ──

    @Test
    fun `first start with no audio socket defers with fallback timer`() {
        orch.audioEnabled = true
        orch.browserConnected = true
        val result = orch.ensure()
        assertEquals(AudioCaptureOrchestrator.EnsureResult.DEFERRED, result)
        assertFalse(orch.captureActive)
        assertTrue(deferScheduled)
        assertTrue(log.contains("defer:${AudioCaptureOrchestrator.FALLBACK_DEFER_MS}ms"))
    }

    @Test
    fun `audio socket connect reschedules with shorter grace`() {
        orch.audioEnabled = true
        orch.browserConnected = true
        orch.ensure() // deferred
        log.clear()
        deferScheduled = false

        orch.onAudioSocketConnected()
        assertTrue(deferCancelled)
        assertTrue(deferScheduled) // re-scheduled with shorter grace
        assertTrue(log.contains("defer:${AudioCaptureOrchestrator.NEGOTIATION_GRACE_MS}ms"))
    }

    @Test
    fun `deferred timer expiry starts with default codec`() {
        orch.audioEnabled = true
        orch.browserConnected = true
        orch.ensure() // deferred
        orch.onDeferredTimerExpired()
        assertTrue(orch.captureActive)
        assertTrue(log.contains("start:default"))
    }

    @Test
    fun `pcm request during deferred window starts directly as pcm - no gap`() {
        orch.audioEnabled = true
        orch.browserConnected = true
        orch.ensure() // deferred

        log.clear()
        val result = orch.ensure(codecOverride = "pcm")
        assertEquals(AudioCaptureOrchestrator.EnsureResult.STARTED, result)
        assertTrue(orch.captureActive)
        assertEquals("pcm", orch.currentCodec)
        assertTrue(log.contains("start:pcm"))
        assertTrue(deferCancelled)
        // No stop — we never started a default capture
        assertFalse(log.contains("stop"))
    }

    @Test
    fun `audio socket already connected skips defer`() {
        orch.audioEnabled = true
        orch.browserConnected = true
        orch.currentCodec = "opus" // known codec
        val result = orch.ensure(codecOverride = "opus")
        assertEquals(AudioCaptureOrchestrator.EnsureResult.STARTED, result)
        assertFalse(deferScheduled)
    }

    // ── Same codec request doesn't restart ──

    @Test
    fun `same codec re-request does not restart`() {
        startCapturing("pcm")
        log.clear()

        val result = orch.ensure(codecOverride = "pcm")
        assertEquals(AudioCaptureOrchestrator.EnsureResult.KEPT, result)
        assertFalse(log.any { it.startsWith("start") })
        assertFalse(log.contains("stop"))
    }

    // ── Codec change restarts ──

    @Test
    fun `codec change opus to pcm restarts`() {
        startCapturing("opus")
        log.clear()

        val result = orch.ensure(codecOverride = "pcm")
        assertEquals(AudioCaptureOrchestrator.EnsureResult.STARTED, result)
        assertTrue(log.contains("stop"))
        assertTrue(log.contains("start:pcm"))
    }

    // ── Browser disconnect stops capture ──

    @Test
    fun `browser disconnect stops capture`() {
        startCapturing("opus")
        log.clear()

        orch.browserConnected = false
        val result = orch.ensure()
        assertEquals(AudioCaptureOrchestrator.EnsureResult.STOPPED, result)
        assertFalse(orch.captureActive)
        assertTrue(log.contains("stop"))
    }

    // ── Reconnect ──

    @Test
    fun `reconnect after stop defers for new codec negotiation`() {
        startCapturing("pcm")
        orch.stop()
        log.clear()

        orch.audioEnabled = true
        orch.browserConnected = true
        val result = orch.ensure()
        assertEquals(AudioCaptureOrchestrator.EnsureResult.DEFERRED, result)
        assertNull(orch.currentCodec)
        assertFalse(orch.audioSocketConnected)
    }

    // ── Stop cleans up properly ──

    @Test
    fun `stop clears all state`() {
        startCapturing("opus")
        log.clear()

        orch.stop()
        assertFalse(orch.captureActive)
        assertNull(orch.currentCodec)
        assertFalse(orch.audioSocketConnected)
        assertTrue(log.contains("stop"))
    }

    // ── Helper ──

    private fun startCapturing(codec: String) {
        orch.audioEnabled = true
        orch.browserConnected = true
        orch.currentCodec = codec
        orch.ensure(codecOverride = codec)
    }
}
