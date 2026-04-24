package com.castla.mirror.policy

/**
 * Input state for audio policy evaluation — snapshot of all signals at evaluation time.
 */
data class AudioPolicyInput(
    val audioEnabled: Boolean,
    val requestedCodec: String?,
    val currentCodec: String?,
    val browserConnected: Boolean,
    val captureActive: Boolean
)

/**
 * Decision output from audio policy evaluation.
 */
data class AudioPolicyDecision(
    val shouldCapture: Boolean,
    val codecMode: String?,
    val restartRequired: Boolean
)

/**
 * Pure policy object that determines audio capture behavior.
 *
 * Capture is gated by audioEnabled + browserConnected. The audio-off setting
 * cannot be bypassed by codec requests.
 */
object AudioPolicy {

    fun evaluate(input: AudioPolicyInput): AudioPolicyDecision {
        val shouldCapture = input.audioEnabled && input.browserConnected
        val codecMode = if (shouldCapture) (input.requestedCodec ?: input.currentCodec) else null
        // null currentCodec means default start (opus). Treat "opus" request against null as same-path.
        val effectiveCurrent = input.currentCodec ?: "opus"
        val codecChanged = input.requestedCodec != null && input.requestedCodec != effectiveCurrent
        val restartRequired = shouldCapture && input.captureActive && codecChanged

        return AudioPolicyDecision(
            shouldCapture = shouldCapture,
            codecMode = codecMode,
            restartRequired = restartRequired
        )
    }
}
