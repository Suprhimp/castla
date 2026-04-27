package com.castla.mirror.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TaskBoundsParserTest {

    @Test
    fun `parses bounds from task body with positive coordinates`() {
        val body = """
            mTaskOrganizer=null
            bounds=[405,0][1280,720]
            mResolvedOverrideConfiguration={1.0 ?mcc?mnc en_US}
        """.trimIndent()
        val rect = TaskBoundsParser.parseTaskBoundsFromBlock(body)
        assertEquals(405, rect?.left)
        assertEquals(0, rect?.top)
        assertEquals(1280, rect?.right)
        assertEquals(720, rect?.bottom)
    }

    @Test
    fun `returns null when bounds line is absent`() {
        val body = """
            mTaskOrganizer=null
            mResolvedOverrideConfiguration={1.0 ?mcc?mnc en_US}
            mDisplayId=2
        """.trimIndent()
        assertNull(TaskBoundsParser.parseTaskBoundsFromBlock(body))
    }

    @Test
    fun `parses negative offsets`() {
        val body = "bounds=[-50,0][1280,720]"
        val rect = TaskBoundsParser.parseTaskBoundsFromBlock(body)
        assertEquals(-50, rect?.left)
        assertEquals(0, rect?.top)
    }

    @Test
    fun `parses bounds embedded mid-line with surrounding text`() {
        val body = "* Task{1234 type=standard A=10001:com.example.app U=0 visible=true bounds=[0,0][800,600] mode=freeform displayId=2}"
        val rect = TaskBoundsParser.parseTaskBoundsFromBlock(body)
        assertEquals(0, rect?.left)
        assertEquals(800, rect?.right)
        assertEquals(600, rect?.bottom)
    }

    @Test
    fun `picks the first bounds entry when multiple present`() {
        // Some dumpsys outputs include both effective and configuration bounds.
        // Parser is allowed to pick the first deterministically.
        val body = """
            bounds=[0,0][405,720]
            mResolvedConfiguration.bounds=[100,100][500,500]
        """.trimIndent()
        val rect = TaskBoundsParser.parseTaskBoundsFromBlock(body)
        assertEquals(0, rect?.left)
        assertEquals(405, rect?.right)
    }

    @Test
    fun `returns null on empty input`() {
        assertNull(TaskBoundsParser.parseTaskBoundsFromBlock(""))
    }
}
