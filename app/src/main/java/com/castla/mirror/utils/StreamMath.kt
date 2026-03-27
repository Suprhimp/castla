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
     * Calculates OTT boosted bitrate (1.5x)
     */
    fun calculateVideoAppBitrate(baseBitrate: Int): Int {
        return minOf((baseBitrate * 1.5).toInt(), 15_000_000)
    }

    /**
     * Computes the DPI for a virtual display so content remains scaled comfortably.
     */
    fun calculateDpi(height: Int): Int {
        return (height * 240 / 720).coerceIn(120, 320)
    }
}
