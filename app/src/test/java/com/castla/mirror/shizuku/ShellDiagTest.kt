package com.castla.mirror.shizuku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellDiagTest {

    @Test
    fun `empty stdout yields empty list`() {
        assertTrue(ShellDiag.parse("").isEmpty())
    }

    @Test
    fun `parses single step with rc 0`() {
        val stdout = """
            __STEP_BEGIN__ name=hello
            hi
            __STEP_END__ name=hello rc=0
        """.trimIndent()
        val result = ShellDiag.parse(stdout)
        assertEquals(1, result.size)
        assertEquals("hello", result[0].name)
        assertEquals(0, result[0].rc)
        assertEquals("hi", result[0].output)
    }

    @Test
    fun `parses multiple steps`() {
        val stdout = """
            __STEP_BEGIN__ name=one
            first
            __STEP_END__ name=one rc=0
            __STEP_BEGIN__ name=two
            second
            __STEP_END__ name=two rc=0
        """.trimIndent()
        val result = ShellDiag.parse(stdout)
        assertEquals(2, result.size)
        assertEquals("one", result[0].name)
        assertEquals("first", result[0].output)
        assertEquals("two", result[1].name)
        assertEquals("second", result[1].output)
    }

    @Test
    fun `parses step with nonzero rc`() {
        val stdout = """
            __STEP_BEGIN__ name=fail
            permission denied
            __STEP_END__ name=fail rc=13
        """.trimIndent()
        val result = ShellDiag.parse(stdout)
        assertEquals(1, result.size)
        assertEquals(13, result[0].rc)
        assertEquals("permission denied", result[0].output)
    }

    @Test
    fun `parses step with stderr interleaved via 2 gt 1`() {
        // With "{ cmd; } 2>&1", stderr is merged into stdout line-by-line
        val stdout = """
            __STEP_BEGIN__ name=mixed
            stdout line
            stderr line
            another stdout
            __STEP_END__ name=mixed rc=0
        """.trimIndent()
        val result = ShellDiag.parse(stdout)
        assertEquals(1, result.size)
        val lines = result[0].output.lines()
        assertEquals(3, lines.size)
        assertEquals("stdout line", lines[0])
        assertEquals("stderr line", lines[1])
        assertEquals("another stdout", lines[2])
    }

    @Test
    fun `parses multi-line step output`() {
        val stdout = """
            __STEP_BEGIN__ name=multi
            line 1
            line 2
            line 3
            __STEP_END__ name=multi rc=0
        """.trimIndent()
        val result = ShellDiag.parse(stdout)
        assertEquals(1, result.size)
        assertEquals("line 1\nline 2\nline 3", result[0].output)
    }

    @Test
    fun `truncated step without END marker gets rc -1`() {
        val stdout = """
            __STEP_BEGIN__ name=truncated
            partial output
        """.trimIndent()
        val result = ShellDiag.parse(stdout)
        assertEquals(1, result.size)
        assertEquals("truncated", result[0].name)
        assertEquals(-1, result[0].rc)
        assertEquals("partial output", result[0].output)
    }

    @Test
    fun `second BEGIN without preceding END closes previous as truncated`() {
        val stdout = """
            __STEP_BEGIN__ name=first
            never closed
            __STEP_BEGIN__ name=second
            done
            __STEP_END__ name=second rc=0
        """.trimIndent()
        val result = ShellDiag.parse(stdout)
        assertEquals(2, result.size)
        assertEquals("first", result[0].name)
        assertEquals(-1, result[0].rc)
        assertEquals("never closed", result[0].output)
        assertEquals("second", result[1].name)
        assertEquals(0, result[1].rc)
    }

    @Test
    fun `mismatched END name closes current as truncated`() {
        val stdout = """
            __STEP_BEGIN__ name=alpha
            oops
            __STEP_END__ name=beta rc=0
        """.trimIndent()
        val result = ShellDiag.parse(stdout)
        assertEquals(1, result.size)
        assertEquals("alpha", result[0].name)
        assertEquals(-1, result[0].rc)
    }

    @Test
    fun `lines outside any step are ignored`() {
        val stdout = """
            garbage before
            __STEP_BEGIN__ name=ok
            hi
            __STEP_END__ name=ok rc=0
            garbage after
        """.trimIndent()
        val result = ShellDiag.parse(stdout)
        assertEquals(1, result.size)
        assertEquals("hi", result[0].output)
    }

    @Test
    fun `buildScript emits correct markers per step`() {
        val script = ShellDiag.buildScript(
            listOf(
                "step_a" to "echo hi",
                "step_b" to "false"
            )
        )
        assertTrue(script.contains("__STEP_BEGIN__ name=step_a"))
        assertTrue(script.contains("{ echo hi; } 2>&1"))
        assertTrue(script.contains("__STEP_END__ name=step_a rc=\$?"))
        assertTrue(script.contains("__STEP_BEGIN__ name=step_b"))
        assertTrue(script.contains("{ false; } 2>&1"))
        assertTrue(script.contains("__STEP_END__ name=step_b rc=\$?"))
    }

    @Test
    fun `buildScript with empty step list yields empty script`() {
        val script = ShellDiag.buildScript(emptyList())
        assertTrue(script.isEmpty())
    }

    @Test
    fun `roundtrip buildScript and parse recover all step names and rc`() {
        // Simulate the shell executing the script and emitting output
        val steps = listOf("alpha" to "echo a", "beta" to "echo b")
        // buildScript output is shell code; we don't run it here, but we can
        // simulate what the shell would print by manually emitting markers:
        val simulated = steps.joinToString("\n") { (name, _) ->
            "__STEP_BEGIN__ name=$name\nout_$name\n__STEP_END__ name=$name rc=0"
        }
        val parsed = ShellDiag.parse(simulated)
        assertEquals(2, parsed.size)
        assertEquals("alpha", parsed[0].name)
        assertEquals("out_alpha", parsed[0].output)
        assertEquals(0, parsed[0].rc)
        assertEquals("beta", parsed[1].name)
        assertEquals("out_beta", parsed[1].output)
    }
}
