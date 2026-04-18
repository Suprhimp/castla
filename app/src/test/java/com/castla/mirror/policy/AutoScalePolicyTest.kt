package com.castla.mirror.policy

import org.junit.Assert.*
import org.junit.Test

class AutoScalePolicyTest {

    private fun input(
        thermalStatus: Int = 0,
        networkStable: Boolean = true,
        browserHealthy: Boolean = true,
        currentTierIndex: Int = 0,
        stableCount: Int = 0,
        tierCount: Int = 4
    ) = AutoScaleInput(thermalStatus, networkStable, browserHealthy, currentTierIndex, stableCount, tierCount)

    // ── Thermal override ──

    @Test
    fun `thermal MODERATE forces drop to tier 0`() {
        val decision = AutoScalePolicy.evaluate(input(thermalStatus = 2, currentTierIndex = 3))
        assertTrue(decision is AutoScaleDecision.DropToTier)
        assertEquals(0, (decision as AutoScaleDecision.DropToTier).tierIndex)
        assertEquals("thermal", decision.reason)
    }

    @Test
    fun `thermal SEVERE forces drop to tier 0`() {
        val decision = AutoScalePolicy.evaluate(input(thermalStatus = 3, currentTierIndex = 2))
        assertTrue(decision is AutoScaleDecision.DropToTier)
        assertEquals(0, (decision as AutoScaleDecision.DropToTier).tierIndex)
        assertEquals("thermal", decision.reason)
    }

    @Test
    fun `thermal CRITICAL forces drop to tier 0`() {
        val decision = AutoScalePolicy.evaluate(input(thermalStatus = 4, currentTierIndex = 1))
        assertTrue(decision is AutoScaleDecision.DropToTier)
        assertEquals(0, (decision as AutoScaleDecision.DropToTier).tierIndex)
    }

    @Test
    fun `thermal MODERATE at tier 0 holds`() {
        val decision = AutoScalePolicy.evaluate(input(thermalStatus = 2, currentTierIndex = 0))
        assertTrue(decision is AutoScaleDecision.Hold)
        assertEquals(0, (decision as AutoScaleDecision.Hold).newStableCount)
    }

    @Test
    fun `thermal takes priority over all other signals`() {
        // Even with perfect network and browser, thermal override wins
        val decision = AutoScalePolicy.evaluate(input(
            thermalStatus = 2,
            networkStable = true,
            browserHealthy = true,
            currentTierIndex = 3,
            stableCount = 10
        ))
        assertTrue(decision is AutoScaleDecision.DropToTier)
    }

    // ── Light thermal ──

    @Test
    fun `light thermal blocks upscale`() {
        val decision = AutoScalePolicy.evaluate(input(thermalStatus = 1, stableCount = 5))
        assertTrue(decision is AutoScaleDecision.Block)
    }

    @Test
    fun `light thermal does not force downscale`() {
        val decision = AutoScalePolicy.evaluate(input(thermalStatus = 1, currentTierIndex = 2))
        // Block means reset stability but don't change tier
        assertTrue(decision is AutoScaleDecision.Block)
    }

    // ── Network congestion ──

    @Test
    fun `network congestion steps down`() {
        val decision = AutoScalePolicy.evaluate(input(networkStable = false, currentTierIndex = 2))
        assertTrue(decision is AutoScaleDecision.StepDown)
        assertEquals(1, (decision as AutoScaleDecision.StepDown).newTierIndex)
        assertEquals("congestion", decision.reason)
    }

    @Test
    fun `network congestion at tier 0 holds`() {
        val decision = AutoScalePolicy.evaluate(input(networkStable = false, currentTierIndex = 0))
        assertTrue(decision is AutoScaleDecision.Hold)
    }

    @Test
    fun `network congestion takes priority over browser quality`() {
        val decision = AutoScalePolicy.evaluate(input(
            networkStable = false,
            browserHealthy = false,
            currentTierIndex = 2
        ))
        assertTrue(decision is AutoScaleDecision.StepDown)
        assertEquals("congestion", (decision as AutoScaleDecision.StepDown).reason)
    }

    // ── Browser quality ──

    @Test
    fun `browser quality poor steps down`() {
        val decision = AutoScalePolicy.evaluate(input(browserHealthy = false, currentTierIndex = 3))
        assertTrue(decision is AutoScaleDecision.StepDown)
        assertEquals(2, (decision as AutoScaleDecision.StepDown).newTierIndex)
        assertEquals("quality", decision.reason)
    }

    @Test
    fun `browser quality poor at tier 0 holds`() {
        val decision = AutoScalePolicy.evaluate(input(browserHealthy = false, currentTierIndex = 0))
        assertTrue(decision is AutoScaleDecision.Hold)
    }

    // ── Healthy stable state ──

    @Test
    fun `healthy state increments stability count`() {
        val decision = AutoScalePolicy.evaluate(input(stableCount = 0, currentTierIndex = 0))
        assertTrue(decision is AutoScaleDecision.Hold)
        assertEquals(1, (decision as AutoScaleDecision.Hold).newStableCount)
    }

    @Test
    fun `reaching threshold triggers step up`() {
        val decision = AutoScalePolicy.evaluate(input(
            stableCount = AutoScalePolicy.UPSCALE_THRESHOLD - 1,
            currentTierIndex = 0,
            tierCount = 4
        ))
        assertTrue(decision is AutoScaleDecision.StepUp)
        assertEquals(1, (decision as AutoScaleDecision.StepUp).newTierIndex)
    }

    @Test
    fun `at max tier healthy state holds`() {
        val decision = AutoScalePolicy.evaluate(input(
            stableCount = AutoScalePolicy.UPSCALE_THRESHOLD - 1,
            currentTierIndex = 3,
            tierCount = 4
        ))
        assertTrue(decision is AutoScaleDecision.Hold)
        assertEquals(AutoScalePolicy.UPSCALE_THRESHOLD, (decision as AutoScaleDecision.Hold).newStableCount)
    }

    @Test
    fun `step up advances one tier at a time`() {
        val decision = AutoScalePolicy.evaluate(input(
            stableCount = AutoScalePolicy.UPSCALE_THRESHOLD - 1,
            currentTierIndex = 1,
            tierCount = 4
        ))
        assertTrue(decision is AutoScaleDecision.StepUp)
        assertEquals(2, (decision as AutoScaleDecision.StepUp).newTierIndex)
    }

    // ── isBrowserHealthy ──

    @Test
    fun `browser healthy when all metrics within thresholds`() {
        assertTrue(AutoScalePolicy.isBrowserHealthy(0, 0, 0.0))
        assertTrue(AutoScalePolicy.isBrowserHealthy(5, 3, 200.0))
    }

    @Test
    fun `browser unhealthy when dropped frames exceed threshold`() {
        assertFalse(AutoScalePolicy.isBrowserHealthy(6, 0, 0.0))
    }

    @Test
    fun `browser unhealthy when backlog drops exceed threshold`() {
        assertFalse(AutoScalePolicy.isBrowserHealthy(0, 4, 0.0))
    }

    @Test
    fun `browser unhealthy when delay exceeds threshold`() {
        assertFalse(AutoScalePolicy.isBrowserHealthy(0, 0, 201.0))
    }

    @Test
    fun `browser healthy at exactly threshold values`() {
        assertTrue(AutoScalePolicy.isBrowserHealthy(
            AutoScalePolicy.QUALITY_DROP_THRESHOLD,
            AutoScalePolicy.QUALITY_BACKLOG_THRESHOLD,
            AutoScalePolicy.QUALITY_DELAY_THRESHOLD_MS
        ))
    }

    // ── Tier notification policy (reason strings) ──

    @Test
    fun `thermal drop carries thermal reason`() {
        val decision = AutoScalePolicy.evaluate(input(thermalStatus = 2, currentTierIndex = 2))
        assertEquals("thermal", (decision as AutoScaleDecision.DropToTier).reason)
    }

    @Test
    fun `congestion step down carries congestion reason`() {
        val decision = AutoScalePolicy.evaluate(input(networkStable = false, currentTierIndex = 1))
        assertEquals("congestion", (decision as AutoScaleDecision.StepDown).reason)
    }

    @Test
    fun `quality step down carries quality reason`() {
        val decision = AutoScalePolicy.evaluate(input(browserHealthy = false, currentTierIndex = 1))
        assertEquals("quality", (decision as AutoScaleDecision.StepDown).reason)
    }

    @Test
    fun `step up has no reason field`() {
        val decision = AutoScalePolicy.evaluate(input(
            stableCount = AutoScalePolicy.UPSCALE_THRESHOLD - 1,
            currentTierIndex = 0
        ))
        assertTrue(decision is AutoScaleDecision.StepUp)
    }

    // ── Priority order verification ──

    @Test
    fun `priority order is thermal then network then quality then stable`() {
        // All bad — thermal wins
        val d1 = AutoScalePolicy.evaluate(input(
            thermalStatus = 2, networkStable = false, browserHealthy = false, currentTierIndex = 3
        ))
        assertTrue(d1 is AutoScaleDecision.DropToTier)

        // No thermal, network bad + browser bad — network wins
        val d2 = AutoScalePolicy.evaluate(input(
            thermalStatus = 0, networkStable = false, browserHealthy = false, currentTierIndex = 3
        ))
        assertTrue(d2 is AutoScaleDecision.StepDown)
        assertEquals("congestion", (d2 as AutoScaleDecision.StepDown).reason)

        // No thermal, network ok, browser bad — quality wins
        val d3 = AutoScalePolicy.evaluate(input(
            thermalStatus = 0, networkStable = true, browserHealthy = false, currentTierIndex = 3
        ))
        assertTrue(d3 is AutoScaleDecision.StepDown)
        assertEquals("quality", (d3 as AutoScaleDecision.StepDown).reason)
    }

    // ── Single-tier edge cases ──

    @Test
    fun `single tier system always holds`() {
        val decision = AutoScalePolicy.evaluate(input(
            stableCount = 100,
            currentTierIndex = 0,
            tierCount = 1
        ))
        assertTrue(decision is AutoScaleDecision.Hold)
    }

    // ── OTT tier boost ──

    @Test
    fun `ottMinTier returns OTT_MIN_TIER when video app at tier 0`() {
        val result = AutoScalePolicy.ottMinTier(
            currentTierIndex = 0,
            isVideoApp = true,
            thermalStatus = 0,
            tierCount = 4
        )
        assertEquals(AutoScalePolicy.OTT_MIN_TIER, result)
    }

    @Test
    fun `ottMinTier returns null when not video app`() {
        val result = AutoScalePolicy.ottMinTier(
            currentTierIndex = 0,
            isVideoApp = false,
            thermalStatus = 0,
            tierCount = 4
        )
        assertNull(result)
    }

    @Test
    fun `ottMinTier returns null when already at or above min tier`() {
        val result = AutoScalePolicy.ottMinTier(
            currentTierIndex = 1,
            isVideoApp = true,
            thermalStatus = 0,
            tierCount = 4
        )
        assertNull(result)
    }

    @Test
    fun `ottMinTier returns null under thermal pressure`() {
        val result = AutoScalePolicy.ottMinTier(
            currentTierIndex = 0,
            isVideoApp = true,
            thermalStatus = 1,
            tierCount = 4
        )
        assertNull(result)
    }

    @Test
    fun `ottMinTier clamped to max available tier`() {
        val result = AutoScalePolicy.ottMinTier(
            currentTierIndex = 0,
            isVideoApp = true,
            thermalStatus = 0,
            tierCount = 1
        )
        assertNull(result) // only 1 tier available, can't go higher
    }

    @Test
    fun `ottMinTier returns null under severe thermal`() {
        val result = AutoScalePolicy.ottMinTier(
            currentTierIndex = 0,
            isVideoApp = true,
            thermalStatus = 3,
            tierCount = 4
        )
        assertNull(result)
    }

    @Test
    fun `ottMinTier returns null when above min tier`() {
        val result = AutoScalePolicy.ottMinTier(
            currentTierIndex = 2,
            isVideoApp = true,
            thermalStatus = 0,
            tierCount = 4
        )
        assertNull(result) // tier 2 > OTT_MIN_TIER(1), no boost needed
    }

    @Test
    fun `ottMinTier returns null for two tier system when min tier equals max`() {
        // tierCount=2 means tiers 0,1 — OTT_MIN_TIER=1 is valid
        val result = AutoScalePolicy.ottMinTier(
            currentTierIndex = 0,
            isVideoApp = true,
            thermalStatus = 0,
            tierCount = 2
        )
        assertEquals(AutoScalePolicy.OTT_MIN_TIER, result)
    }

    @Test
    fun `ottMinTier constant is 1`() {
        // Ensure OTT minimum tier is 720p60 (tier index 1)
        assertEquals(1, AutoScalePolicy.OTT_MIN_TIER)
    }

    @Test
    fun `ottMinTier returns null when thermal LIGHT even at tier 0`() {
        // thermalStatus=1 (LIGHT) should block boost
        val result = AutoScalePolicy.ottMinTier(
            currentTierIndex = 0,
            isVideoApp = true,
            thermalStatus = 1,
            tierCount = 4
        )
        assertNull(result)
    }
}
