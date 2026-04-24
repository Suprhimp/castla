package com.castla.mirror.shizuku

import org.junit.Assert.assertEquals
import org.junit.Test

class ShizukuHealthTest {

    @Test
    fun `age -5 classifies as Unknown`() {
        assertEquals(ShizukuHealth.State.Unknown, ShizukuHealth.classify(-5L))
    }

    @Test
    fun `age -1 classifies as Unknown`() {
        assertEquals(ShizukuHealth.State.Unknown, ShizukuHealth.classify(-1L))
    }

    @Test
    fun `age 0 classifies as Healthy`() {
        assertEquals(ShizukuHealth.State.Healthy, ShizukuHealth.classify(0L))
    }

    @Test
    fun `age 29 classifies as Healthy`() {
        assertEquals(ShizukuHealth.State.Healthy, ShizukuHealth.classify(29L))
    }

    @Test
    fun `age 30 classifies as Warning`() {
        assertEquals(ShizukuHealth.State.Warning, ShizukuHealth.classify(30L))
    }

    @Test
    fun `age 119 classifies as Warning`() {
        assertEquals(ShizukuHealth.State.Warning, ShizukuHealth.classify(119L))
    }

    @Test
    fun `age 120 classifies as Critical`() {
        assertEquals(ShizukuHealth.State.Critical, ShizukuHealth.classify(120L))
    }

    @Test
    fun `very stale age classifies as Critical`() {
        assertEquals(ShizukuHealth.State.Critical, ShizukuHealth.classify(3600L))
    }
}
