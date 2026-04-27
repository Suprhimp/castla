package com.castla.mirror.service

/** Pure helper for parsing `bounds=[L,T][R,B]` from a dumpsys task block. */
internal object TaskBoundsParser {

    private val BOUNDS_REGEX = Regex("bounds=\\[(-?\\d+),(-?\\d+)\\]\\[(-?\\d+),(-?\\d+)\\]")

    data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int)

    fun parseTaskBoundsFromBlock(body: String): Bounds? {
        if (body.isEmpty()) return null
        val match = BOUNDS_REGEX.find(body) ?: return null
        return Bounds(
            left = match.groupValues[1].toInt(),
            top = match.groupValues[2].toInt(),
            right = match.groupValues[3].toInt(),
            bottom = match.groupValues[4].toInt(),
        )
    }
}
