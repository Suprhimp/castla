package com.castla.mirror.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImeTargetParserTest {

    @Test
    fun `empty input returns empty set`() {
        assertEquals(emptySet<Int>(), ImeTargetParser.displaysWithInputTarget(""))
    }

    @Test
    fun `parses real device snippet with two targets`() {
        val snippet = """
            imeLayeringTarget in display# 0 Window{74bf179 u0 com.google.android.youtube/com.google.android.youtube.app.honeycomb.Shell${'$'}HomeActivity}
            imeInputTarget in display# 0 Window{74bf179 u0 com.google.android.youtube/com.google.android.youtube.app.honeycomb.Shell${'$'}HomeActivity}
            imeControlTarget in display# 0 Window{74bf179 u0 com.google.android.youtube/com.google.android.youtube.app.honeycomb.Shell${'$'}HomeActivity}
            imeInputTarget in display# 53 Window{7e42531 u0 com.nhn.android.nmap/com.naver.map.LaunchActivity}
            imeControlTarget in display# 53 Window{7e42531 u0 com.nhn.android.nmap/com.naver.map.LaunchActivity}
            imeControlTarget in display# 54 Window{e011af6 u0 com.castla.mirror/com.castla.mirror.ui.WebBrowserActivity}
        """.trimIndent()

        assertEquals(setOf(0, 53), ImeTargetParser.displaysWithInputTarget(snippet))
    }

    @Test
    fun `null Window is excluded`() {
        val snippet = "imeInputTarget in display# 53 null"
        assertEquals(emptySet<Int>(), ImeTargetParser.displaysWithInputTarget(snippet))
    }

    @Test
    fun `extra whitespace tolerated`() {
        val snippet = "imeInputTarget in display#   53   Window{abc u0 pkg/.Act}"
        assertEquals(setOf(53), ImeTargetParser.displaysWithInputTarget(snippet))
    }

    @Test
    fun `duplicate lines collapse to one entry`() {
        val snippet = """
            imeInputTarget in display# 53 Window{a u0 pkg/.A}
            imeInputTarget in display# 53 Window{b u0 pkg/.B}
        """.trimIndent()
        assertEquals(setOf(53), ImeTargetParser.displaysWithInputTarget(snippet))
    }

    @Test
    fun `non imeInputTarget lines are excluded`() {
        val snippet = """
            imeLayeringTarget in display# 0 Window{74bf179 u0 pkg/.A}
            imeControlTarget in display# 0 Window{74bf179 u0 pkg/.A}
        """.trimIndent()
        assertTrue(ImeTargetParser.displaysWithInputTarget(snippet).isEmpty())
    }
}
