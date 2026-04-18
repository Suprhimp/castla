package com.castla.mirror.shizuku

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.castla.mirror.diagnostics.DiagnosticEvent
import com.castla.mirror.diagnostics.MirrorDiagnostics
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private const val OUTER_SCRIPT = "/data/local/tmp/shizuku_watchdog_outer.sh"
private const val INNER_SCRIPT = "/data/local/tmp/shizuku_watchdog_inner.sh"
private const val OUTER_PID_FILE = "/data/local/tmp/shizuku_watchdog_outer.pid"
private const val INNER_PID_FILE = "/data/local/tmp/shizuku_watchdog_inner.pid"
private const val HEARTBEAT_FILE = "/data/local/tmp/shizuku_watchdog.heartbeat"
private const val LEGACY_SCRIPT = "/data/local/tmp/shizuku_watchdog.sh"
private const val LIB_DIR_BASE = "/data/local/tmp/shizuku_lib"
private const val HEARTBEAT_MAX_AGE_SECONDS = 15
private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

sealed class ShizukuState {
    object NotInstalled : ShizukuState()
    object NotRunning : ShizukuState()
    data class Running(val permitted: Boolean) : ShizukuState()
}

class ShizukuSetup {

    companion object {
        private const val TAG = "ShizukuSetup"
        private const val REQUEST_CODE = 1001
        const val USER_SERVICE_VERSION = 107
    }

    private val _state = MutableStateFlow<ShizukuState>(ShizukuState.NotInstalled)
    val state: StateFlow<ShizukuState> = _state

    private val _serviceConnected = MutableStateFlow(false)
    val serviceConnected: StateFlow<Boolean> = _serviceConnected

    var privilegedService: IPrivilegedService? = null
        private set

    private val serviceArgs = Shizuku.UserServiceArgs(
        ComponentName(
            com.castla.mirror.BuildConfig.APPLICATION_ID,
            PrivilegedService::class.java.name
        )
    ).daemon(false).processNameSuffix("privileged").version(USER_SERVICE_VERSION)

    /** Guard to prevent duplicate bind calls while a bind is in progress. */
    private var bindingInProgress = false
    private var userServiceBound = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            privilegedService = IPrivilegedService.Stub.asInterface(binder)
            _serviceConnected.value = true
            bindingInProgress = false
            userServiceBound = true
            Log.i(TAG, "Privileged service connected")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            privilegedService = null
            _serviceConnected.value = false
            bindingInProgress = false
            userServiceBound = false
            Log.i(TAG, "Privileged service disconnected")
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        Log.i(TAG, "Shizuku binder received")
        MirrorDiagnostics.log(DiagnosticEvent.SHIZUKU_BINDER_READY)
        updateState()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        Log.i(TAG, "Shizuku binder dead")
        MirrorDiagnostics.log(DiagnosticEvent.SHIZUKU_BINDER_DEAD)
        privilegedService = null
        _serviceConnected.value = false
        bindingInProgress = false
        _state.value = ShizukuState.NotRunning
    }

    private val permissionResultListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == REQUEST_CODE) {
            val granted = grantResult == PackageManager.PERMISSION_GRANTED
            Log.i(TAG, "Permission result: granted=$granted")
            _state.value = ShizukuState.Running(permitted = granted)
            if (granted) {
                bindPrivilegedService()
            }
        }
    }

    fun init(bindService: Boolean = true) {
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionResultListener)
        updateState()
        // Auto-bind if already permitted
        if (bindService && isAvailable() && hasPermission()) {
            bindPrivilegedService()
        }
    }

    fun attachPrivilegedService(service: IPrivilegedService?) {
        privilegedService = service
        _serviceConnected.value = service != null
        bindingInProgress = false
    }

    fun requestPermission() {
        if (Shizuku.isPreV11()) {
            Log.w(TAG, "Shizuku pre-v11 not supported")
            return
        }
        Shizuku.requestPermission(REQUEST_CODE)
    }

    fun isAvailable(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (_: Exception) {
            false
        }
    }

    fun hasPermission(): Boolean {
        return try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) {
            false
        }
    }

    fun bindPrivilegedService() {
        if (_serviceConnected.value) {
            Log.d(TAG, "bindPrivilegedService skipped: already connected")
            return
        }
        if (bindingInProgress) {
            Log.d(TAG, "bindPrivilegedService skipped: bind already in progress")
            return
        }
        try {
            bindingInProgress = true
            runOnMainSync {
                Shizuku.bindUserService(serviceArgs, serviceConnection)
            }
            userServiceBound = true
            Log.i(TAG, "Binding privileged service...")
        } catch (e: Exception) {
            bindingInProgress = false
            Log.e(TAG, "Failed to bind privileged service", e)
        }
    }

    private fun runOnMainSync(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
            return
        }
        val latch = CountDownLatch(1)
        var error: Throwable? = null
        mainHandler.post {
            try {
                block()
            } catch (t: Throwable) {
                error = t
            } finally {
                latch.countDown()
            }
        }
        latch.await(2, TimeUnit.SECONDS)
        error?.let { throw it }
    }

    private fun updateState() {
        _state.value = when {
            !isAvailable() -> ShizukuState.NotRunning
            hasPermission() -> ShizukuState.Running(permitted = true)
            else -> ShizukuState.Running(permitted = false)
        }
    }

    /**
     * Execute a shell command via Shizuku's privileged service (ADB-level privileges).
     * Returns the command output, or null on failure.
     */
    fun exec(command: String): String? {
        val service = privilegedService
        if (service == null) {
            Log.w(TAG, "Privileged service not connected for exec")
            // Try to bind if we have permission
            if (isAvailable() && hasPermission()) {
                bindPrivilegedService()
            }
            return null
        }
        return try {
            val output = service.execCommand(command)
            output
        } catch (e: android.os.DeadObjectException) {
            Log.w(TAG, "Privileged service dead during exec, marking null")
            privilegedService = null
            _serviceConnected.value = false
            null
        } catch (e: Exception) {
            Log.e(TAG, "exec failed: $command", e)
            null
        }
    }

    /**
     * Add IP alias via INetd binder (most reliable method with ADB-level access).
     */
    fun addInterfaceAddress(ifName: String, address: String, prefixLength: Int): Boolean {
        val service = privilegedService ?: return false
        return try {
            service.addInterfaceAddress(ifName, address, prefixLength)
        } catch (e: Exception) {
            Log.e(TAG, "addInterfaceAddress failed", e)
            false
        }
    }

    /**
     * Run comprehensive Tesla network setup — tries all methods to add IP alias.
     * Returns diagnostic log.
     */
    fun setupTeslaNetwork(ifName: String, virtualIp: String = "100.99.9.9"): String? {
        val service = privilegedService
        if (service == null) {
            Log.w(TAG, "setupTeslaNetwork: service not connected")
            if (isAvailable() && hasPermission()) {
                bindPrivilegedService()
            }
            return null
        }
        return try {
            service.setupTeslaNetworking(ifName, virtualIp)
        } catch (e: Exception) {
            Log.e(TAG, "setupTeslaNetworking failed", e)
            null
        }
    }

    /**
     * Restart WiFi tethering with CGNAT IP (100.64.0.1/24).
     * Returns diagnostic log, or null if service not connected.
     */
    fun restartTetheringWithCgnat(): String? {
        val service = privilegedService
        if (service == null) {
            Log.w(TAG, "restartTetheringWithCgnat: service not connected")
            if (isAvailable() && hasPermission()) {
                bindPrivilegedService()
            }
            return null
        }
        return try {
            service.restartTetheringWithCgnat()
        } catch (e: Exception) {
            Log.e(TAG, "restartTetheringWithCgnat failed", e)
            "Exception: ${e.message}"
        }
    }

    /**
     * Start WiFi tethering (hotspot) via the privileged service.
     * Returns true if the request was submitted.
     */
    fun startWifiTethering(): Boolean {
        val result = exec("__HOTSPOT_ON__")
        Log.i(TAG, "startWifiTethering result: $result")
        return result != null && result.startsWith("OK")
    }

    fun stopWifiTethering(): Boolean {
        val result = exec("__HOTSPOT_OFF__")
        Log.i(TAG, "stopWifiTethering result: $result")
        return result != null && result.startsWith("OK")
    }

    /** Mutex serializing hardening passes so concurrent callers see consistent results. */
    private val hardenMutex = Any()

    /**
     * Idempotent, thread-safe. Applies best-effort process fortification
     * (doze whitelist, appops, OOM-adj) and (re)installs the dual-layer watchdog
     * if it is not currently healthy.
     *
     * Concurrent callers block on [hardenMutex]; the second caller re-evaluates
     * fortify + watchdog verification freshly so the returned boolean always
     * reflects the post-call state.
     *
     * Returns true iff watchdog verification is healthy after this call.
     * Fortify is advisory and never gates the return value — its per-step
     * rc values are logged and attached to the [DiagnosticEvent.SHIZUKU_FORTIFIED]
     * event detail for on-device debugging.
     */
    fun ensureShizukuHardened(): Boolean = synchronized(hardenMutex) {
        val service = privilegedService
        if (service == null) {
            Log.w(TAG, "ensureShizukuHardened: service not connected")
            return@synchronized false
        }

        val fortifyResults = runFortify(service)
        val fortifySummary = fortifyResults.joinToString(",") { "${it.name}=${it.rc}" }
        Log.i(TAG, "fortify: $fortifySummary")
        MirrorDiagnostics.log(DiagnosticEvent.SHIZUKU_FORTIFIED, fortifySummary)

        val verifyReason = verifyWatchdog(service)
        val healthy = if (verifyReason == "HEALTHY") {
            true
        } else {
            Log.i(TAG, "watchdog unhealthy ($verifyReason) — reinstalling")
            installWatchdog(service)
        }
        Log.i(TAG, "ensureShizukuHardened: healthy=$healthy")
        healthy
    }

    /**
     * Returns age in seconds of the watchdog heartbeat file, or -1 if missing/unreadable.
     * Callers can feed this into [ShizukuHealth.classify] for UI state.
     */
    fun getWatchdogHeartbeatAgeSeconds(): Long {
        val service = privilegedService ?: return -1L
        return try {
            val raw = service.execCommand(
                "HB=\$(cat $HEARTBEAT_FILE 2>/dev/null); NOW=\$(date +%s); " +
                "case \"\$HB\" in ''|*[!0-9]*) echo -1 ;; *) echo \$((NOW - HB)) ;; esac"
            )?.trim() ?: return -1L
            raw.toLongOrNull() ?: -1L
        } catch (_: Exception) {
            -1L
        }
    }

    /**
     * Runs fortification steps via the marker protocol; returns all step results.
     * Every sub-command is best-effort — the caller treats failures as advisory.
     */
    private fun runFortify(service: IPrivilegedService): List<StepResult> {
        val steps = listOf(
            "doze_whitelist" to "dumpsys deviceidle whitelist +$SHIZUKU_PACKAGE",
            "appops_run_any" to "cmd appops set $SHIZUKU_PACKAGE RUN_ANY_IN_BACKGROUND allow",
            "appops_run_bg"  to "cmd appops set $SHIZUKU_PACKAGE RUN_IN_BACKGROUND allow",
            "oom_adj_server" to "for PID in \$(pidof shizuku_server 2>/dev/null); do " +
                                "echo -900 > /proc/\$PID/oom_score_adj 2>/dev/null; done; echo ok"
        )
        val script = ShellDiag.buildScript(steps)
        val out = try {
            service.execCommand(script) ?: ""
        } catch (e: Exception) {
            Log.w(TAG, "runFortify: execCommand threw", e)
            ""
        }
        return ShellDiag.parse(out)
    }

    /**
     * Runs the shell health gate. Returns "HEALTHY" or "UNHEALTHY: <reason>".
     * Returns "UNHEALTHY: exec_failed" on IPC failure.
     */
    private fun verifyWatchdog(service: IPrivilegedService): String {
        val script = """
            fail() { echo "UNHEALTHY: ${'$'}1"; exit 0; }
            check_pid_file() {
                PF=${'$'}1; SPATH=${'$'}2; LABEL=${'$'}3
                [ -f "${'$'}PF" ] || fail "missing_${'$'}LABEL"
                PID=${'$'}(cat "${'$'}PF" 2>/dev/null)
                case "${'$'}PID" in
                    ''|*[!0-9]*) fail "nonnumeric_pid_${'$'}LABEL" ;;
                esac
                [ -r "/proc/${'$'}PID/cmdline" ] || fail "dead_${'$'}LABEL"
                # Exact argv-element match: split NUL-delimited cmdline into lines
                # and require one line to equal the full script path.
                tr '\0' '\n' < "/proc/${'$'}PID/cmdline" 2>/dev/null | grep -Fxq "${'$'}SPATH" \
                    || fail "cmdline_mismatch_${'$'}LABEL"
            }
            check_pid_file $OUTER_PID_FILE $OUTER_SCRIPT outer
            check_pid_file $INNER_PID_FILE $INNER_SCRIPT inner
            HB=${'$'}(cat $HEARTBEAT_FILE 2>/dev/null)
            case "${'$'}HB" in
                ''|*[!0-9]*) fail "heartbeat_malformed" ;;
            esac
            NOW=${'$'}(date +%s)
            AGE=${'$'}((NOW - HB))
            [ "${'$'}AGE" -lt 0 ] && fail "heartbeat_future_${'$'}AGE"
            [ "${'$'}AGE" -gt $HEARTBEAT_MAX_AGE_SECONDS ] && fail "heartbeat_stale_${'$'}{AGE}s"
            echo "HEALTHY"
        """.trimIndent()
        return try {
            service.execCommand(script)?.trim() ?: "UNHEALTHY: null_output"
        } catch (e: Exception) {
            Log.w(TAG, "verifyWatchdog: execCommand threw", e)
            "UNHEALTHY: exec_failed"
        }
    }

    /**
     * Stages new watchdog scripts, atomically swaps them in, tears down old
     * processes, spawns the outer supervisor, and re-runs verification.
     * Returns true iff verification reports HEALTHY after install.
     */
    private fun installWatchdog(service: IPrivilegedService): Boolean {
        return try {
            // Resolve Shizuku APK path (required for app_process classpath)
            val pmOutput = service.execCommand("pm path $SHIZUKU_PACKAGE")?.trim() ?: ""
            val shizukuApk = pmOutput.lineSequence()
                .firstOrNull { it.startsWith("package:") }
                ?.removePrefix("package:")?.trim()
            if (shizukuApk.isNullOrEmpty()) {
                Log.e(TAG, "installWatchdog: could not find Shizuku APK path")
                return false
            }
            val abi = service.execCommand("getprop ro.product.cpu.abi")?.trim()?.ifEmpty { null }
                ?: "arm64-v8a"
            val libDir = "$LIB_DIR_BASE/$abi"

            val innerScript = buildInnerScript(shizukuApk, libDir)
            val outerScript = buildOuterScript()

            // STAGE: write to .new paths (old processes still running untouched)
            val stageSteps = listOf(
                "ensure_libdir" to "mkdir -p $libDir",
                "extract_librish" to
                    "unzip -o $shizukuApk lib/$abi/librish.so -d /data/local/tmp/shizuku_lib_tmp && " +
                    "cp /data/local/tmp/shizuku_lib_tmp/lib/$abi/librish.so $libDir/ && " +
                    "rm -rf /data/local/tmp/shizuku_lib_tmp",
                "write_inner" to "cat > ${INNER_SCRIPT}.new <<'__CASTLA_INNER_EOF__'\n$innerScript\n__CASTLA_INNER_EOF__",
                "write_outer" to "cat > ${OUTER_SCRIPT}.new <<'__CASTLA_OUTER_EOF__'\n$outerScript\n__CASTLA_OUTER_EOF__",
                "chmod_inner" to "chmod 755 ${INNER_SCRIPT}.new",
                "chmod_outer" to "chmod 755 ${OUTER_SCRIPT}.new"
            )
            val stageOut = service.execCommand(ShellDiag.buildScript(stageSteps)) ?: ""
            val stageResults = ShellDiag.parse(stageOut)
            val stageFailed = stageResults.any { it.rc != 0 }
            if (stageFailed) {
                Log.e(TAG, "installWatchdog stage failed: ${stageResults.joinToString(",") { "${it.name}=${it.rc}" }}")
                return false
            }

            // SWAP + TEARDOWN + SPAWN: each step named so rc values are recovered
            val teardownCurrent = """
                for PF in $OUTER_PID_FILE $INNER_PID_FILE; do
                    [ -f "${'$'}PF" ] || continue
                    PID=${'$'}(cat "${'$'}PF" 2>/dev/null)
                    case "${'$'}PID" in
                        ''|*[!0-9]*) rm -f "${'$'}PF"; continue ;;
                    esac
                    # Exact argv-element match via NUL splitting
                    if tr '\0' '\n' < "/proc/${'$'}PID/cmdline" 2>/dev/null \
                            | grep -Fxq -e "$OUTER_SCRIPT" -e "$INNER_SCRIPT"; then
                        kill "${'$'}PID" 2>/dev/null
                    fi
                    rm -f "${'$'}PF"
                done
                true
            """.trimIndent()

            val teardownLegacy = """
                for CMDFILE in /proc/*/cmdline; do
                    LPID=${'$'}(echo "${'$'}CMDFILE" | sed -n 's#^/proc/\([0-9]*\)/cmdline${'$'}#\1#p')
                    [ -z "${'$'}LPID" ] && continue
                    # Split argv elements on NUL for exact matching
                    ARGS=${'$'}(tr '\0' '\n' < "${'$'}CMDFILE" 2>/dev/null)
                    [ -z "${'$'}ARGS" ] && continue
                    # Only act if legacy script path is an exact argv element
                    echo "${'$'}ARGS" | grep -Fxq "$LEGACY_SCRIPT" || continue
                    # Skip if this process is one of the new-version watchdogs
                    if echo "${'$'}ARGS" | grep -Fxq -e "$OUTER_SCRIPT" -e "$INNER_SCRIPT"; then
                        continue
                    fi
                    kill "${'$'}LPID" 2>/dev/null
                done
                rm -f $LEGACY_SCRIPT
            """.trimIndent()

            val swapSteps = listOf(
                "mv_inner" to "mv -f ${INNER_SCRIPT}.new $INNER_SCRIPT",
                "mv_outer" to "mv -f ${OUTER_SCRIPT}.new $OUTER_SCRIPT",
                "teardown_current" to teardownCurrent,
                "teardown_legacy" to teardownLegacy,
                "clear_heartbeat" to "rm -f $HEARTBEAT_FILE",
                "spawn_outer" to "nohup setsid sh $OUTER_SCRIPT </dev/null >/dev/null 2>&1 & echo spawned"
            )
            val swapOut = service.execCommand(ShellDiag.buildScript(swapSteps)) ?: ""
            val swapResults = ShellDiag.parse(swapOut)
            val swapSummary = swapResults.joinToString(",") { "${it.name}=${it.rc}" }
            val swapFailed = swapResults.any { it.rc != 0 } || swapResults.size != swapSteps.size
            if (swapFailed) {
                Log.e(TAG, "installWatchdog swap failed: $swapSummary")
                return false
            }
            Log.i(TAG, "installWatchdog swap ok: $swapSummary")

            // Let the inner loop write heartbeat + PID files at least once
            Thread.sleep(2000L)

            val verify = verifyWatchdog(service)
            if (verify != "HEALTHY") {
                Log.w(TAG, "installWatchdog: verify reports $verify")
                return false
            }
            Log.i(TAG, "installWatchdog: verify HEALTHY")
            true
        } catch (e: Exception) {
            Log.e(TAG, "installWatchdog failed", e)
            false
        }
    }

    private fun buildInnerScript(shizukuApk: String, libDir: String): String = """
#!/bin/sh
trap '' HUP TERM INT QUIT
APK_PATH="$shizukuApk"
LIB_DIR="$libDir"
export LD_LIBRARY_PATH="${'$'}LIB_DIR"
echo ${'$'}${'$'} > $INNER_PID_FILE
# Best-effort: keep our own OOM score low too
echo -900 > /proc/${'$'}${'$'}/oom_score_adj 2>/dev/null

while true; do
    date +%s > $HEARTBEAT_FILE 2>/dev/null
    if ! pidof shizuku_server > /dev/null 2>&1; then
        log -t shizuku_watchdog "server down, restarting"
        setsid nohup app_process -Djava.class.path="${'$'}APK_PATH" /system/bin \
            --nice-name=shizuku_server rikka.shizuku.server.ShizukuService \
            </dev/null >/dev/null 2>&1 &
        sleep 3
        for PID in ${'$'}(pidof shizuku_server 2>/dev/null); do
            echo -900 > /proc/${'$'}PID/oom_score_adj 2>/dev/null
        done
        sleep 12
    fi
    sleep 5
done
""".trimIndent()

    private fun buildOuterScript(): String = """
#!/bin/sh
trap '' HUP TERM INT QUIT
INNER=$INNER_SCRIPT
echo ${'$'}${'$'} > $OUTER_PID_FILE
echo -900 > /proc/${'$'}${'$'}/oom_score_adj 2>/dev/null

while true; do
    ALIVE=0
    if [ -f $INNER_PID_FILE ]; then
        IPID=${'$'}(cat $INNER_PID_FILE 2>/dev/null)
        case "${'$'}IPID" in
            ''|*[!0-9]*) ;;
            *)
                # Exact argv-element match: split NUL-delimited cmdline into lines
                if tr '\0' '\n' < /proc/${'$'}IPID/cmdline 2>/dev/null | grep -Fxq "$INNER_SCRIPT"; then
                    ALIVE=1
                fi
                ;;
        esac
    fi
    if [ ${'$'}ALIVE -eq 0 ]; then
        log -t shizuku_watchdog "inner down, respawning"
        setsid nohup sh "${'$'}INNER" </dev/null >/dev/null 2>&1 &
    fi
    # Deterministic jitter per outer PID (0..4 seconds) on top of 5s base
    sleep ${'$'}((5 + ${'$'}${'$'} % 5))
done
""".trimIndent()

    fun release() {
        if (userServiceBound) {
            try {
                runOnMainSync {
                    Shizuku.unbindUserService(serviceArgs, serviceConnection, true)
                }
            } catch (_: Exception) {}
        }
        privilegedService = null
        _serviceConnected.value = false
        bindingInProgress = false
        userServiceBound = false
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        Shizuku.removeRequestPermissionResultListener(permissionResultListener)
    }
}
