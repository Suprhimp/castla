package com.castla.mirror.utils

/**
 * Pure utility for split-screen layout math. No Android dependencies so this is
 * unit-testable on the JVM.
 *
 * Defines minimum usable widths for the LEFT (phone-aspect) and RIGHT (web)
 * panes; if a display is narrower than the sum, split mode is not viable and
 * callers must fall back to fullscreen.
 */
object SplitMath {
    const val LEFT_PANE_MIN_PX = 360
    const val RIGHT_PANE_MIN_PX = 320
    const val MIN_TOTAL_PX = LEFT_PANE_MIN_PX + RIGHT_PANE_MIN_PX

    fun isSplitViable(width: Int, height: Int): Boolean =
        width >= MIN_TOTAL_PX && height > 0

    /**
     * Returns the LEFT pane width for a viable display.
     * Targets a 9:16 phone aspect (height*9/16) clamped to [MIN, width-RIGHT_MIN].
     * @throws IllegalArgumentException when [isSplitViable] would return false.
     */
    fun computeLeftPaneWidth(width: Int, height: Int): Int {
        require(isSplitViable(width, height)) {
            "Split not viable for ${width}x${height}; min total = $MIN_TOTAL_PX"
        }
        val target = (height * 9f / 16f).toInt()
        val maxLeft = width - RIGHT_PANE_MIN_PX
        return target.coerceIn(LEFT_PANE_MIN_PX, maxLeft)
    }
}
