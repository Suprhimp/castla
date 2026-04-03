package com.castla.mirror.policy

/**
 * Input state for audio policy evaluation — snapshot of all signals at evaluation time.
 */
data class AudioPolicyInput(
    val audioEnabled: Boolean,
    val requestedCodec: String?,
    val currentCodec: String?,
    val browserConnected: Boolean,
    val muteLocalAudio: Boolean,
    val captureActive: Boolean
)

/**
 * Decision output from audio policy evaluation.
 */
data class AudioPolicyDecision(
    val shouldCapture: Boolean,
    val codecMode: String?,
    val shouldMuteLocal: Boolean,
    val restartRequired: Boolean
)

/**
 * Pure policy object that determines audio capture behavior.
 *
 * Separates two independent concerns:
 * - "Capture audio for streaming" (controlled by audioEnabled + browserConnected)
 * - "Mute local phone audio" (controlled by muteLocalAudio, only when capturing)
 *
 * This ensures music apps and video apps follow the same audio policy,
 * and that the audio-off setting cannot be bypassed by codec requests.
 */
object AudioPolicy {

    fun evaluate(input: AudioPolicyInput): AudioPolicyDecision {
        val shouldCapture = input.audioEnabled && input.browserConnected
        val codecMode = if (shouldCapture) (input.requestedCodec ?: input.currentCodec) else null
        val shouldMuteLocal = shouldCapture && input.muteLocalAudio
        val codecChanged = input.requestedCodec != null && input.requestedCodec != input.currentCodec
        val restartRequired = shouldCapture && input.captureActive && codecChanged

        return AudioPolicyDecision(
            shouldCapture = shouldCapture,
            codecMode = codecMode,
            shouldMuteLocal = shouldMuteLocal,
            restartRequired = restartRequired
        )
    }
}
