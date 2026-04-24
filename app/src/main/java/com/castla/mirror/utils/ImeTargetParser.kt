package com.castla.mirror.utils

/**
 * Parses `imeInputTarget in display# N Window{...}` lines from `dumpsys window`
 * output and returns the set of display IDs that currently have a text field
 * input target. Used as a Samsung-specific fallback signal when the global IME
 * is suppressed due to cross-display focus mismatch, so the global
 * `dumpsys input_method` state never reports `mInputShown=true`.
 */
object ImeTargetParser {
    private val PATTERN = Regex("""imeInputTarget in display#\s*(\d+)\s+Window\{""")

    fun displaysWithInputTarget(dumpsysWindow: String): Set<Int> {
        val out = mutableSetOf<Int>()
        for (m in PATTERN.findAll(dumpsysWindow)) {
            m.groupValues[1].toIntOrNull()?.let { out.add(it) }
        }
        return out
    }
}
