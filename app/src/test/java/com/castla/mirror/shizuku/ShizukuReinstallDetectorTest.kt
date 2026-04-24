package com.castla.mirror.shizuku

import org.junit.Assert.assertEquals
import org.junit.Test

class ShizukuReinstallDetectorTest {

    @Test
    fun firstLaunch_whenSavedIsZero_returnsForceRebind() {
        val action = ShizukuReinstallDetector.detect(
            savedLastUpdateTime = 0L,
            currentLastUpdateTime = 1_700_000_000_000L
        )
        assertEquals(ShizukuReinstallDetector.Action.ForceRebind, action)
    }

    @Test
    fun reinstall_whenTimestampsDiffer_returnsForceRebind() {
        val action = ShizukuReinstallDetector.detect(
            savedLastUpdateTime = 1_700_000_000_000L,
            currentLastUpdateTime = 1_700_000_500_000L
        )
        assertEquals(ShizukuReinstallDetector.Action.ForceRebind, action)
    }

    @Test
    fun normalRestart_whenTimestampsMatch_returnsNone() {
        val action = ShizukuReinstallDetector.detect(
            savedLastUpdateTime = 1_700_000_000_000L,
            currentLastUpdateTime = 1_700_000_000_000L
        )
        assertEquals(ShizukuReinstallDetector.Action.None, action)
    }

    @Test
    fun zeroCurrent_whenPackageInfoUnavailable_returnsNone() {
        // Defensive: if currentLastUpdateTime is 0 (packageManager lookup failed),
        // don't spuriously force a rebind on every launch.
        val action = ShizukuReinstallDetector.detect(
            savedLastUpdateTime = 0L,
            currentLastUpdateTime = 0L
        )
        assertEquals(ShizukuReinstallDetector.Action.None, action)
    }
}
