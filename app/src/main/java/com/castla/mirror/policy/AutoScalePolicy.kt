package com.castla.mirror.policy

/**
 * Input state for auto-scale evaluation — snapshot of all signals at evaluation time.
 */
data class AutoScaleInput(
    val thermalStatus: Int,
    val networkStable: Boolean,
    val browserHealthy: Boolean,
    val currentTierIndex: Int,
    val stableCount: Int,
    val tierCount: Int
)

/**
 * Decision output from auto-scale evaluation.
 * Each variant carries the information needed to apply the decision and notify the user.
 */
sealed class AutoScaleDecision {
    /** Thermal emergency — drop to lowest tier immediately. */
    data class DropToTier(val tierIndex: Int, val reason: String) : AutoScaleDecision()
    /** Step down one tier due to congestion or quality issues. */
    data class StepDown(val newTierIndex: Int, val reason: String) : AutoScaleDecision()
    /** Conditions stable long enough — step up one tier. */
    data class StepUp(val newTierIndex: Int) : AutoScaleDecision()
    /** No tier change — hold at current tier, carry updated stability count. */
    data class Hold(val newStableCount: Int) : AutoScaleDecision()
    /** Light thermal pressure — reset stability counter, no tier change. */
    object Block : AutoScaleDecision()
}

/**
 * Pure-function auto-scale policy.
 * Determines whether to step up, step down, or hold based on thermal/network/quality signals.
 *
 * Priority order:
 * 1. Thermal >= MODERATE (2): force to tier 0
 * 2. Thermal >= LIGHT (1): block upscale, reset stability
 * 3. Network congestion: step down
 * 4. Browser quality poor: step down
 * 5. All healthy: increment stability, step up when threshold met
 */
object AutoScalePolicy {
    /** Consecutive stable intervals required before stepping up. */
    const val UPSCALE_THRESHOLD = 2
    /** Max dropped frames per report interval before considered unhealthy. */
    const val QUALITY_DROP_THRESHOLD = 5
    /** Max decoder backlog drops per interval before considered unhealthy. */
    const val QUALITY_BACKLOG_THRESHOLD = 3
    /** Max average render delay (ms) before considered unhealthy. */
    const val QUALITY_DELAY_THRESHOLD_MS = 200.0

    fun evaluate(input: AutoScaleInput): AutoScaleDecision {
        // 1. Thermal override — MODERATE (2) or above: drop to tier 0
        if (input.thermalStatus >= 2) {
            return if (input.currentTierIndex > 0) {
                AutoScaleDecision.DropToTier(0, "thermal")
            } else {
                AutoScaleDecision.Hold(0)
            }
        }

        // 2. Light thermal (1): block upscale, reset stability
        if (input.thermalStatus >= 1) {
            return AutoScaleDecision.Block
        }

        // 3. Network congestion: step down if possible
        if (!input.networkStable) {
            return if (input.currentTierIndex > 0) {
                AutoScaleDecision.StepDown(input.currentTierIndex - 1, "congestion")
            } else {
                AutoScaleDecision.Hold(0)
            }
        }

        // 4. Browser playback struggling: step down if possible
        if (!input.browserHealthy) {
            return if (input.currentTierIndex > 0) {
                AutoScaleDecision.StepDown(input.currentTierIndex - 1, "quality")
            } else {
                AutoScaleDecision.Hold(0)
            }
        }

        // 5. All healthy — increment stability, step up if threshold met
        val newStableCount = input.stableCount + 1
        return if (newStableCount >= UPSCALE_THRESHOLD && input.currentTierIndex < input.tierCount - 1) {
            AutoScaleDecision.StepUp(input.currentTierIndex + 1)
        } else {
            AutoScaleDecision.Hold(newStableCount)
        }
    }

    /**
     * Evaluate browser health from quality report metrics.
     * Returns true when all metrics are within acceptable thresholds.
     */
    fun isBrowserHealthy(
        droppedFrames: Int,
        backlogDrops: Int,
        avgDelayMs: Double
    ): Boolean {
        return droppedFrames <= QUALITY_DROP_THRESHOLD &&
                backlogDrops <= QUALITY_BACKLOG_THRESHOLD &&
                avgDelayMs <= QUALITY_DELAY_THRESHOLD_MS
    }
}
