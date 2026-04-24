package com.castla.mirror.policy

import org.junit.Assert.*
import org.junit.Test

class AudioPolicyTest {

    private fun input(
        audioEnabled: Boolean = true,
        requestedCodec: String? = null,
        currentCodec: String? = null,
        browserConnected: Boolean = true,
        captureActive: Boolean = false
    ) = AudioPolicyInput(audioEnabled, requestedCodec, currentCodec, browserConnected, captureActive)

    // ── Audio disabled — never capture ──

    @Test
    fun `audio disabled - no capture regardless of codec request`() {
        val decision = AudioPolicy.evaluate(input(audioEnabled = false, requestedCodec = "opus"))
        assertFalse(decision.shouldCapture)
        assertNull(decision.codecMode)
    }

    @Test
    fun `audio disabled - pcm request still blocked`() {
        val decision = AudioPolicy.evaluate(input(audioEnabled = false, requestedCodec = "pcm"))
        assertFalse(decision.shouldCapture)
    }

    @Test
    fun `audio disabled - browser connected still no capture`() {
        val decision = AudioPolicy.evaluate(input(audioEnabled = false, browserConnected = true))
        assertFalse(decision.shouldCapture)
    }

    // ── Audio enabled, browser connected — should capture ──

    @Test
    fun `audio enabled, browser connected - should capture with default codec`() {
        val decision = AudioPolicy.evaluate(input(audioEnabled = true, browserConnected = true))
        assertTrue(decision.shouldCapture)
        assertNull(decision.codecMode)
    }

    @Test
    fun `audio enabled, browser connected, opus requested - opus codec`() {
        val decision = AudioPolicy.evaluate(input(audioEnabled = true, browserConnected = true, requestedCodec = "opus"))
        assertTrue(decision.shouldCapture)
        assertEquals("opus", decision.codecMode)
    }

    @Test
    fun `audio enabled, browser connected, pcm requested - pcm codec`() {
        val decision = AudioPolicy.evaluate(input(audioEnabled = true, browserConnected = true, requestedCodec = "pcm"))
        assertTrue(decision.shouldCapture)
        assertEquals("pcm", decision.codecMode)
    }

    // ── Browser disconnected — no capture ──

    @Test
    fun `audio enabled but browser disconnected - no capture`() {
        val decision = AudioPolicy.evaluate(input(audioEnabled = true, browserConnected = false))
        assertFalse(decision.shouldCapture)
    }

    // ── Restart detection ──

    @Test
    fun `codec change from opus to pcm requires restart`() {
        val decision = AudioPolicy.evaluate(input(
            audioEnabled = true, browserConnected = true,
            requestedCodec = "pcm", currentCodec = "opus", captureActive = true
        ))
        assertTrue(decision.shouldCapture)
        assertTrue(decision.restartRequired)
    }

    @Test
    fun `codec change from pcm to opus requires restart`() {
        val decision = AudioPolicy.evaluate(input(
            audioEnabled = true, browserConnected = true,
            requestedCodec = "opus", currentCodec = "pcm", captureActive = true
        ))
        assertTrue(decision.restartRequired)
    }

    @Test
    fun `same codec requested - no restart`() {
        val decision = AudioPolicy.evaluate(input(
            audioEnabled = true, browserConnected = true,
            requestedCodec = "pcm", currentCodec = "pcm", captureActive = true
        ))
        assertTrue(decision.shouldCapture)
        assertFalse(decision.restartRequired)
    }

    @Test
    fun `no restart needed when not currently capturing`() {
        val decision = AudioPolicy.evaluate(input(
            audioEnabled = true, browserConnected = true, requestedCodec = "pcm", captureActive = false
        ))
        assertTrue(decision.shouldCapture)
        assertFalse(decision.restartRequired)
    }

    @Test
    fun `no restart when no codec change requested`() {
        val decision = AudioPolicy.evaluate(input(
            audioEnabled = true, browserConnected = true, requestedCodec = null, captureActive = true
        ))
        assertTrue(decision.shouldCapture)
        assertFalse(decision.restartRequired)
    }

    @Test
    fun `pcm request with null current restarts - different from default opus`() {
        val decision = AudioPolicy.evaluate(input(
            audioEnabled = true, browserConnected = true,
            requestedCodec = "pcm", currentCodec = null, captureActive = true
        ))
        assertTrue(decision.restartRequired)
    }

    @Test
    fun `opus request with null current does not restart - same as default`() {
        val decision = AudioPolicy.evaluate(input(
            audioEnabled = true, browserConnected = true,
            requestedCodec = "opus", currentCodec = null, captureActive = true
        ))
        assertFalse(decision.restartRequired)
    }

    // ── Consistency: same policy for any app type ──

    @Test
    fun `policy is pure function - same inputs always produce same outputs`() {
        val inputA = input(audioEnabled = true, browserConnected = true)
        val inputB = input(audioEnabled = true, browserConnected = true)
        assertEquals(AudioPolicy.evaluate(inputA), AudioPolicy.evaluate(inputB))
    }
}
