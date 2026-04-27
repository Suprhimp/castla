package com.castla.mirror.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitMathTest {

    @Test
    fun `1280x720 produces 9 to 16 left pane width within bounds`() {
        // 720 * 9 / 16 = 405 ; clamps min=360, max=1280-320=960 → 405
        assertEquals(405, SplitMath.computeLeftPaneWidth(1280, 720))
    }

    @Test
    fun `1920x1080 clamps within left and right minima`() {
        // 1080 * 9 / 16 = 607 ; min=360, max=1920-320=1600 → 607
        assertEquals(607, SplitMath.computeLeftPaneWidth(1920, 1080))
    }

    @Test
    fun `narrow display clamps left pane to minimum`() {
        // 600 * 9 / 16 = 337 ; min=360 → 360
        assertEquals(360, SplitMath.computeLeftPaneWidth(800, 600))
    }

    @Test
    fun `tight right pane respects right minimum`() {
        // 400 * 9 / 16 = 225 ; clamped up to min=360
        // width=700, max=700-320=380 → 360 (within [360, 380])
        val left = SplitMath.computeLeftPaneWidth(700, 400)
        assertEquals(360, left)
        // Right pane width = 700 - 360 = 340 >= RIGHT_PANE_MIN_PX (320)
        assertTrue((700 - left) >= SplitMath.RIGHT_PANE_MIN_PX)
    }

    @Test
    fun `display below minimum total is not viable`() {
        // 600 < MIN_TOTAL_PX=680 → not viable
        assertFalse(SplitMath.isSplitViable(600, 400))
    }

    @Test
    fun `zero or negative dimensions are not viable`() {
        assertFalse(SplitMath.isSplitViable(0, 0))
        assertFalse(SplitMath.isSplitViable(1280, 0))
        assertFalse(SplitMath.isSplitViable(-100, 720))
    }

    @Test
    fun `at minimum total width split is viable`() {
        // width=680, height=480 → viable
        assertTrue(SplitMath.isSplitViable(680, 480))
        val left = SplitMath.computeLeftPaneWidth(680, 480)
        // 480 * 9 / 16 = 270 ; min=360, max=680-320=360 → exactly 360
        assertEquals(360, left)
    }

    @Test
    fun `compute left width throws when not viable`() {
        assertThrows(IllegalArgumentException::class.java) {
            SplitMath.computeLeftPaneWidth(600, 400)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SplitMath.computeLeftPaneWidth(0, 0)
        }
    }

    @Test
    fun `min pane constants are set per plan`() {
        assertEquals(360, SplitMath.LEFT_PANE_MIN_PX)
        assertEquals(320, SplitMath.RIGHT_PANE_MIN_PX)
        assertEquals(680, SplitMath.MIN_TOTAL_PX)
    }
}
