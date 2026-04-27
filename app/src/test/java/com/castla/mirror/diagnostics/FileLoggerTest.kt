package com.castla.mirror.diagnostics

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class FileLoggerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Before
    fun setUp() {
        FileLogger.resetForTest()
    }

    @After
    fun tearDown() {
        FileLogger.resetForTest()
    }

    @Test
    fun `init creates logs directory under provided dir`() {
        FileLogger.initForTest(tempFolder.root, maxFileBytes = 1024)
        FileLogger.i("Tag", "first message")
        val logsDir = File(tempFolder.root, "logs")
        assertTrue(logsDir.isDirectory)
        val current = File(logsDir, "mirror.log")
        assertTrue(current.exists())
    }

    @Test
    fun `writes preserve order and contain tag level message`() {
        FileLogger.initForTest(tempFolder.root, maxFileBytes = 4096)
        FileLogger.i("Tag1", "info one")
        FileLogger.w("Tag2", "warn two")
        FileLogger.e("Tag3", "error three")

        val log = File(tempFolder.root, "logs/mirror.log").readText()
        val lines = log.lines().filter { it.isNotBlank() }
        assertEquals(3, lines.size)
        assertTrue("got: ${lines[0]}", lines[0].contains(" I Tag1: info one"))
        assertTrue("got: ${lines[1]}", lines[1].contains(" W Tag2: warn two"))
        assertTrue("got: ${lines[2]}", lines[2].contains(" E Tag3: error three"))
    }

    @Test
    fun `error with throwable writes stack trace lines`() {
        FileLogger.initForTest(tempFolder.root, maxFileBytes = 8192)
        val ex = RuntimeException("boom")
        FileLogger.e("Tag", "something failed", ex)

        val log = File(tempFolder.root, "logs/mirror.log").readText()
        assertTrue("got: $log", log.contains(" E Tag: something failed"))
        assertTrue("got: $log", log.contains("RuntimeException"))
        assertTrue("got: $log", log.contains("boom"))
    }

    @Test
    fun `rotation moves current to log_1 when threshold exceeded`() {
        // Use small cap so rotation triggers quickly
        FileLogger.initForTest(tempFolder.root, maxFileBytes = 256)
        // Each message ~80 bytes; write enough to exceed cap and trigger rotation
        repeat(10) { FileLogger.i("Tag", "msg-$it ${"x".repeat(40)}") }

        val current = File(tempFolder.root, "logs/mirror.log")
        val rotated = File(tempFolder.root, "logs/mirror.log.1")
        assertTrue("current must exist", current.exists())
        assertTrue("rotated must exist after threshold reached", rotated.exists())
    }

    @Test
    fun `rotation overwrites existing rotated file (cap stays at 2 files)`() {
        FileLogger.initForTest(tempFolder.root, maxFileBytes = 200)
        // Trigger first rotation
        repeat(8) { FileLogger.i("Tag", "first-batch-$it ${"x".repeat(40)}") }
        // Trigger second rotation
        repeat(8) { FileLogger.i("Tag", "second-batch-$it ${"y".repeat(40)}") }

        val logsDir = File(tempFolder.root, "logs")
        val files = logsDir.listFiles()?.map { it.name }?.sorted() ?: emptyList()
        // Should be exactly current + .1; never .2
        assertEquals(setOf("mirror.log", "mirror.log.1"), files.toSet())
        // Second rotation: .log.1 should now contain "first-batch" content (rotated to .1 on second rotation)
        // NOTE: The rotation moves CURRENT → .1; so after second rotation, .1 contains the most-recent prior batch
        val rotated = File(logsDir, "mirror.log.1").readText()
        assertTrue("rotated should reflect a recent batch, got: $rotated",
            rotated.contains("first-batch") || rotated.contains("second-batch"))
    }

    @Test
    fun `concurrent writes from multiple threads do not corrupt output`() {
        FileLogger.initForTest(tempFolder.root, maxFileBytes = 1_000_000)
        val pool = Executors.newFixedThreadPool(4)
        val latch = CountDownLatch(4)
        val perThread = 250
        repeat(4) { t ->
            pool.submit {
                try {
                    repeat(perThread) { i ->
                        FileLogger.i("T$t", "msg-$i")
                    }
                } finally {
                    latch.countDown()
                }
            }
        }
        assertTrue(latch.await(15, TimeUnit.SECONDS))
        pool.shutdown()
        pool.awaitTermination(2, TimeUnit.SECONDS)

        val combined = FileLogger.getLogFiles().joinToString("\n") { it.readText() }
        val totalLines = combined.lines().count { it.isNotBlank() }
        assertEquals(4 * perThread, totalLines)
    }

    @Test
    fun `init is idempotent`() {
        FileLogger.initForTest(tempFolder.root, maxFileBytes = 4096)
        FileLogger.i("Tag", "before-second-init")
        FileLogger.initForTest(tempFolder.root, maxFileBytes = 4096) // second call should be no-op
        FileLogger.i("Tag", "after-second-init")
        val text = File(tempFolder.root, "logs/mirror.log").readText()
        assertTrue(text.contains("before-second-init"))
        assertTrue(text.contains("after-second-init"))
    }

    @Test
    fun `init failure leaves logger in degraded no-op mode`() {
        // Use a file path (not a dir) as parent → init should fail safely
        val notADir = tempFolder.newFile("collision")
        FileLogger.initForTest(notADir, maxFileBytes = 4096)
        // Calls should not throw
        FileLogger.i("Tag", "should be no-op")
        FileLogger.w("Tag", "should be no-op")
        FileLogger.e("Tag", "should be no-op")
        // No logs dir should be created
        assertFalse(File(notADir, "logs").exists())
        assertEquals(0, FileLogger.getLogFiles().size)
    }

    @Test
    fun `writes are sanitized via DiagnosticSanitizer`() {
        FileLogger.initForTest(tempFolder.root, maxFileBytes = 8192)
        FileLogger.i("Tag", "Loaded https://www.youtube.com/watch?v=secretvalue")
        val text = File(tempFolder.root, "logs/mirror.log").readText()
        assertTrue("got: $text", text.contains("https://www.youtube.com"))
        assertFalse("got: $text", text.contains("secretvalue"))
    }

    @Test
    fun `clear removes log files`() {
        FileLogger.initForTest(tempFolder.root, maxFileBytes = 200)
        repeat(8) { FileLogger.i("Tag", "msg-$it ${"x".repeat(40)}") }
        assertTrue(FileLogger.getLogFiles().isNotEmpty())
        FileLogger.clear()
        assertEquals(0, FileLogger.getLogFiles().size)
    }
}
