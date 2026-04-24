package com.castla.mirror.shizuku

/**
 * Result of a single step within a ShellDiag-instrumented shell script.
 *
 * `rc` is the POSIX exit code captured via `$?` after the step.
 * A sentinel `rc = -1` indicates the step's END marker was never emitted
 * (e.g. stdout was truncated or the shell process exited mid-step).
 */
data class StepResult(
    val name: String,
    val rc: Int,
    val output: String
)

/**
 * Builds shell scripts annotated with begin/end markers so that each step's
 * stdout+stderr and exit code can be recovered from the combined stdout stream.
 *
 * This is needed because [com.castla.mirror.shizuku.PrivilegedService.execCommand]
 * returns only stdout — there's no per-command exit code in the IPC surface.
 *
 * Marker protocol:
 * ```
 * __STEP_BEGIN__ name=<step>
 * <combined stdout+stderr of the step>
 * __STEP_END__ name=<step> rc=<exit-code>
 * ```
 *
 * Steps with missing END markers (truncated output) are surfaced with `rc = -1`.
 */
object ShellDiag {

    private const val BEGIN_PREFIX = "__STEP_BEGIN__ name="
    private const val END_PREFIX = "__STEP_END__ name="
    private const val RC_PREFIX = " rc="

    /** Build a shell script that runs each (name, command) pair with markers. */
    fun buildScript(steps: List<Pair<String, String>>): String = buildString {
        for ((name, cmd) in steps) {
            append("echo '").append(BEGIN_PREFIX).append(name).append("'\n")
            append("{ ").append(cmd).append("; } 2>&1\n")
            append("echo \"").append(END_PREFIX).append(name).append(RC_PREFIX).append("\$?\"\n")
        }
    }

    /**
     * Parse stdout produced by a ShellDiag-instrumented script.
     *
     * Malformed/truncated input is tolerated — incomplete steps (BEGIN without END)
     * get [StepResult.rc] = -1. Lines outside any step are ignored.
     */
    fun parse(stdout: String): List<StepResult> {
        val results = mutableListOf<StepResult>()
        var currentName: String? = null
        val buffer = StringBuilder()

        for (rawLine in stdout.lineSequence()) {
            val line = rawLine
            if (line.startsWith(BEGIN_PREFIX)) {
                // If a previous step is still open, close it as incomplete
                if (currentName != null) {
                    results += StepResult(
                        name = currentName,
                        rc = -1,
                        output = buffer.toString().trimEnd('\n')
                    )
                }
                currentName = line.removePrefix(BEGIN_PREFIX)
                buffer.setLength(0)
            } else if (line.startsWith(END_PREFIX) && currentName != null) {
                val rest = line.removePrefix(END_PREFIX)
                val rcIdx = rest.indexOf(RC_PREFIX)
                val endName: String
                val rc: Int
                if (rcIdx < 0) {
                    endName = rest
                    rc = -1
                } else {
                    endName = rest.substring(0, rcIdx)
                    rc = rest.substring(rcIdx + RC_PREFIX.length).trim().toIntOrNull() ?: -1
                }
                if (endName == currentName) {
                    results += StepResult(
                        name = currentName,
                        rc = rc,
                        output = buffer.toString().trimEnd('\n')
                    )
                    currentName = null
                    buffer.setLength(0)
                } else {
                    // Mismatched END — close current as incomplete, ignore this END
                    results += StepResult(
                        name = currentName,
                        rc = -1,
                        output = buffer.toString().trimEnd('\n')
                    )
                    currentName = null
                    buffer.setLength(0)
                }
            } else if (currentName != null) {
                buffer.append(line).append('\n')
            }
        }

        // Tail: if a step was still open at EOF, surface it as truncated
        if (currentName != null) {
            results += StepResult(
                name = currentName,
                rc = -1,
                output = buffer.toString().trimEnd('\n')
            )
        }

        return results
    }
}
