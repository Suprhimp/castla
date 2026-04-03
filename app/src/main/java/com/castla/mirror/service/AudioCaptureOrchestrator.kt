package com.castla.mirror.service

import com.castla.mirror.policy.AudioPolicy
import com.castla.mirror.policy.AudioPolicyInput

/**
 * Orchestrates audio capture start/stop/restart decisions.
 * All side effects go through the [Actions] interface so tests
 * can verify the exact call sequence without Android dependencies.
 *
 * Defer strategy: on first start, if no audio socket has connected yet
 * we defer until the socket opens. Once the socket opens, we give a
 * brief grace period for codec negotiation (e.g., requestPcm).
 * If the browser never opens an audio socket, the fallback timer fires.
 */
class AudioCaptureOrchestrator(private val actions: Actions) {

    interface Actions {
        fun startCapture(codec: String?)
        fun stopCapture()
        fun applyMute(shouldMute: Boolean)
        fun grantAudioPermission()
        /** Schedule a callback to [onDeferredTimerExpired] after [delayMs]. Return a cancel handle. */
        fun scheduleDeferredStart(delayMs: Long): Any?
        fun cancelDeferredStart(handle: Any?)
    }

    var audioEnabled = false
    var muteLocalAudio = false
    var browserConnected = false
    var currentCodec: String? = null
    var captureActive = false
        private set

    /** Whether at least one audio WebSocket has connected this session. */
    var audioSocketConnected = false
        private set

    private var deferPending = false
    private var deferHandle: Any? = null

    /**
     * Central entry point — evaluates policy and drives actions.
     * @param codecOverride explicit codec request from browser (e.g., "pcm")
     */
    fun ensure(codecOverride: String? = null): EnsureResult {
        // Codec arrives during defer window → cancel defer, start immediately
        if (codecOverride != null && deferPending) {
            cancelDefer()
            return doStart(codecOverride)
        }

        val decision = evaluatePolicy(codecOverride)

        if (!decision.shouldCapture) {
            cancelDefer()
            if (captureActive) {
                actions.stopCapture()
                captureActive = false
                actions.applyMute(false)
            }
            return EnsureResult.STOPPED
        }

        // Already capturing, no restart needed
        if (captureActive && !decision.restartRequired) {
            actions.applyMute(decision.shouldMuteLocal)
            return EnsureResult.KEPT
        }

        // First start with unknown codec: defer for codec negotiation
        if (!captureActive && currentCodec == null && codecOverride == null && !audioSocketConnected) {
            if (!deferPending) {
                deferPending = true
                // Fallback timer in case audio socket never connects
                deferHandle = actions.scheduleDeferredStart(FALLBACK_DEFER_MS)
                return EnsureResult.DEFERRED
            }
            // Already deferring — don't double-schedule
            return EnsureResult.DEFERRED
        }

        return doStart(codecOverride)
    }

    /**
     * Called when an audio WebSocket connects. If we were deferring, start
     * a short grace period for codec negotiation, then start.
     */
    fun onAudioSocketConnected() {
        audioSocketConnected = true
        if (deferPending) {
            // Replace fallback timer with a short grace for codec negotiation
            actions.cancelDeferredStart(deferHandle)
            deferHandle = actions.scheduleDeferredStart(NEGOTIATION_GRACE_MS)
        }
    }

    /**
     * Called when the deferred timer expires.
     */
    fun onDeferredTimerExpired() {
        if (deferPending) {
            deferPending = false
            deferHandle = null
            doStart(null)
        }
    }

    fun stop() {
        cancelDefer()
        if (captureActive) {
            actions.stopCapture()
            captureActive = false
        }
        currentCodec = null
        audioSocketConnected = false
        actions.applyMute(false)
    }

    private fun cancelDefer() {
        if (deferPending) {
            deferPending = false
            actions.cancelDeferredStart(deferHandle)
            deferHandle = null
        }
    }

    private fun evaluatePolicy(codecOverride: String?) = AudioPolicy.evaluate(AudioPolicyInput(
        audioEnabled = audioEnabled,
        requestedCodec = codecOverride,
        currentCodec = currentCodec,
        browserConnected = browserConnected,
        muteLocalAudio = muteLocalAudio,
        captureActive = captureActive
    ))

    private fun doStart(codecOverride: String?): EnsureResult {
        cancelDefer()

        val decision = evaluatePolicy(codecOverride)
        if (!decision.shouldCapture) return EnsureResult.STOPPED

        if (captureActive && !decision.restartRequired) {
            actions.applyMute(decision.shouldMuteLocal)
            return EnsureResult.KEPT
        }

        // Stop existing if restarting
        if (captureActive) {
            actions.stopCapture()
            captureActive = false
            actions.applyMute(false)
        }

        actions.grantAudioPermission()
        actions.applyMute(decision.shouldMuteLocal)

        if (codecOverride != null) currentCodec = codecOverride

        actions.startCapture(decision.codecMode)
        captureActive = true
        return EnsureResult.STARTED
    }

    enum class EnsureResult {
        STARTED, STOPPED, KEPT, DEFERRED
    }

    companion object {
        /** Max wait when audio socket hasn't connected at all. */
        const val FALLBACK_DEFER_MS = 500L
        /** Short grace once audio socket connects, for codec negotiation. */
        const val NEGOTIATION_GRACE_MS = 150L
    }
}
