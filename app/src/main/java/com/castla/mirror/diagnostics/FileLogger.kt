package com.castla.mirror.diagnostics

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Persistent on-disk logger with size-bounded rotation. All persisted writes
 * are sanitized via [DiagnosticSanitizer] so URLs, intent extras, and shell
 * command bodies never hit disk.
 *
 * Storage: `<filesDir>/logs/mirror.log` (current) + `mirror.log.1` (rotated).
 * Cap ~512 KB per file by default → ~1 MB total on disk.
 *
 * If init fails (e.g. read-only filesystem), the logger silently degrades to
 * no-op mode rather than crashing the app.
 */
object FileLogger {

    private const val TAG = "FileLogger"
    private const val DEFAULT_MAX_FILE_BYTES = 512_000L

    private val lock = Any()
    @Volatile private var initialized = false
    @Volatile private var degraded = false
    private var logsDir: File? = null
    private var maxFileBytes: Long = DEFAULT_MAX_FILE_BYTES

    private val timestampFmt = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US)
    }

    /** Production init from [Context]. Idempotent; safe on init failure. */
    fun init(context: Context) {
        synchronized(lock) {
            if (initialized) return
            tryInit(File(context.filesDir, "logs"), DEFAULT_MAX_FILE_BYTES)
        }
    }

    /** Test-only init taking an explicit parent directory and cap. */
    internal fun initForTest(parent: File, maxFileBytes: Long) {
        synchronized(lock) {
            if (initialized) return
            tryInit(File(parent, "logs"), maxFileBytes)
        }
    }

    /** Test-only: clear initialization state so subsequent init() rebinds. */
    internal fun resetForTest() {
        synchronized(lock) {
            initialized = false
            degraded = false
            logsDir = null
            maxFileBytes = DEFAULT_MAX_FILE_BYTES
        }
    }

    private fun tryInit(dir: File, maxBytes: Long) {
        try {
            if (!dir.exists() && !dir.mkdirs()) {
                Log.w(TAG, "Could not create logs dir: $dir — degraded mode")
                degraded = true
                initialized = true
                return
            }
            if (!dir.isDirectory) {
                Log.w(TAG, "Path exists but is not a directory: $dir — degraded mode")
                degraded = true
                initialized = true
                return
            }
            this.logsDir = dir
            this.maxFileBytes = maxBytes
            this.degraded = false
            this.initialized = true
        } catch (t: Throwable) {
            Log.w(TAG, "FileLogger init failed — degraded mode", t)
            degraded = true
            initialized = true
        }
    }

    fun i(tag: String, msg: String) = write("I", tag, msg, null)
    fun w(tag: String, msg: String, t: Throwable? = null) = write("W", tag, msg, t)
    fun e(tag: String, msg: String, t: Throwable? = null) = write("E", tag, msg, t)

    fun getLogFiles(): List<File> {
        synchronized(lock) {
            val dir = logsDir ?: return emptyList()
            val current = File(dir, "mirror.log")
            val rotated = File(dir, "mirror.log.1")
            return listOf(current, rotated).filter { it.exists() && it.length() > 0 }
        }
    }

    fun clear() {
        synchronized(lock) {
            val dir = logsDir ?: return
            File(dir, "mirror.log").delete()
            File(dir, "mirror.log.1").delete()
        }
    }

    private fun write(level: String, tag: String, msg: String, t: Throwable?) {
        if (!initialized || degraded) return
        val safe = DiagnosticSanitizer.safeMessage(msg)
        val ts = timestampFmt.get()?.format(Date()) ?: ""
        val tname = Thread.currentThread().name
        val line = "$ts $level $tag: $safe (t=$tname)"
        synchronized(lock) {
            val dir = logsDir ?: return
            try {
                val current = File(dir, "mirror.log")
                if (current.exists() && current.length() >= maxFileBytes) {
                    val rotated = File(dir, "mirror.log.1")
                    if (rotated.exists()) rotated.delete()
                    current.renameTo(rotated)
                }
                FileWriter(current, true).use { fw ->
                    PrintWriter(fw).use { pw ->
                        pw.println(line)
                        if (t != null) t.printStackTrace(pw)
                    }
                }
            } catch (failure: Throwable) {
                Log.w(TAG, "Failed to write log entry", failure)
            }
        }
    }
}
