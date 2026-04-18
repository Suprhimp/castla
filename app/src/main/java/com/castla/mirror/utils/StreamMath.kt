package com.castla.mirror.utils

object StreamMath {
    /**
     * Calculates the optimal encoding dimensions based on raw screen size and a max height limit.
     * Always returns dimensions aligned to 16 (required by hardware encoders).
     * 
     * @param rawWidth Original screen width
     * @param rawHeight Original screen height
     * @param maxHeight Maximum allowed height (e.g., 720 or 1080)
     * @return Pair of (width, height)
     */
    fun calculateDimensions(rawWidth: Int, rawHeight: Int, maxHeight: Int): Pair<Int, Int> {
        var width = rawWidth
        var height = rawHeight
        
        if (height > maxHeight) {
            val scale = maxHeight.toFloat() / height
            height = maxHeight
            width = (width * scale).toInt()
        }
        
        // Align to 16-multiple for H.264
        width = (width + 15) and 15.inv()
        height = (height + 15) and 15.inv()
        
        return Pair(width, height)
    }

    /**
     * Calculates target bitrate based on pixel count relative to 720p base (4Mbps).
     * @param width The target width
     * @param height The target height
     */
    fun calculateBaseBitrate(width: Int, height: Int): Int {
        val pixels = width.toLong() * height
        val basePixels = 1280L * 720
        return ((4_000_000L * pixels) / basePixels).toInt().coerceIn(1_000_000, 15_000_000)
    }

    /**
     * Calculates bitrate for secondary/split-screen streams.
     * Uses a lower base (3Mbps) and tighter ceiling since secondary
     * content shares bandwidth with the primary stream.
     */
    fun calculateSecondaryBitrate(width: Int, height: Int): Int {
        val pixels = width.toLong() * height
        val basePixels = 1280L * 720
        return ((3_000_000L * pixels) / basePixels).toInt().coerceIn(750_000, 10_000_000)
    }

    /**
     * Bitrate for the video pane in split mode — boosted 1.5x over secondary base
     * to prioritize video quality. Capped at 12Mbps to leave headroom for the companion pane.
     */
    fun calculateSplitVideoBitrate(width: Int, height: Int): Int {
        val pixels = width.toLong() * height
        val basePixels = 1280L * 720
        return ((3_000_000L * pixels * 15) / (basePixels * 10)).toInt().coerceIn(750_000, 12_000_000)
    }

    /**
     * Bitrate for the non-video companion pane in split mode — reduced to 0.6x of secondary base.
     * Frees bandwidth for the video pane while keeping the companion usable.
     */
    fun calculateSplitCompanionBitrate(width: Int, height: Int): Int {
        val pixels = width.toLong() * height
        val basePixels = 1280L * 720
        return ((3_000_000L * pixels * 6) / (basePixels * 10)).toInt().coerceIn(500_000, 6_000_000)
    }

    /**
     * Applies OTT/video-app bitrate boost (1.2x, capped at 15Mbps).
     * Only call when thermal status is NONE (no throttling).
     */
    fun calculateOttBitrate(baseBitrate: Int): Int {
        return minOf((baseBitrate * 1.2).toInt(), 15_000_000)
    }

    @Deprecated("Use calculateOttBitrate instead", replaceWith = ReplaceWith("calculateOttBitrate(baseBitrate)"))
    fun calculateVideoAppBitrate(baseBitrate: Int): Int {
        return calculateOttBitrate(baseBitrate)
    }

    /**
     * Computes the DPI for a virtual display so content remains scaled comfortably.
     */
    fun calculateDpi(height: Int): Int {
        return (height * 240 / 720).coerceIn(120, 320)
    }

    /** Default display density scale (Small). */
    const val DENSITY_SCALE_DEFAULT = 0.7f

    /** All supported density scale values, from largest (original) to most compact. */
    val DENSITY_SCALE_OPTIONS = listOf(1.0f, 0.85f, 0.7f, 0.55f)

    /**
     * Applies a display density scale to the base DPI.
     * @param baseDpi DPI computed by [calculateDpi]
     * @param scale density scale factor (1.0 = large, 0.85 = default, 0.7/0.55 = compact)
     * @return scaled DPI, clamped to [100, 320]
     */
    fun applyDensityScale(baseDpi: Int, scale: Float): Int {
        return (baseDpi * scale).toInt().coerceIn(100, 320)
    }
}
