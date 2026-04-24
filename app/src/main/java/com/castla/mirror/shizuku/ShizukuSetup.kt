package com.castla.mirror.shizuku

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
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
// Channel IDs include a version suffix so IMPORTANCE changes in code take
// effect on upgrade — a pre-existing channel's importance sticks at whatever
// the user set (or the original code created), even after the app replaces
// its declaration. Bumping the ID side-steps that.
private const val RESTART_CHANNEL_ID = "shizuku_restart_v2"
private const val RESTART_NOTIFICATION_ID = 0x5A12
private const val RESTART_PENDING_INTENT_REQUEST = 0x5A12
private const val GIVE_UP_CHANNEL_ID = "shizuku_unrecoverable_v2"
private const val GIVE_UP_NOTIFICATION_ID = 0x5A13
private const val LEGACY_RESTART_CHANNEL_ID = "shizuku_restart"
private const val LEGACY_GIVE_UP_CHANNEL_ID = "shizuku_unrecoverable"

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
        /** Cooldown between foreground auto-launches of the Shizuku manager. */
        private const val AUTO_LAUNCH_COOLDOWN_MS = 60_000L
        /**
         * If Shizuku's binder dies within this window after being received, we
         * count the session as a "quick death". Three in a row (with no
         * intervening stable session) means WADB is flapping (typically
         * screen-off WiFi sleep → Android disables adb-wifi → adbd respawn
         * cascade kills shizuku_server). Auto-launch is suppressed past that
         * point so we don't spam Shizuku manager restarts at the user.
         */
        private const val QUICK_DEATH_WINDOW_MS = 15_000L
        private const val MAX_CONSECUTIVE_QUICK_DEATHS = 3
        /**
         * Delay before posting the "Shizuku stopped" notification after
         * [binderDeadListener] fires. Samsung's USB unplug triggers an adbd
         * restart that briefly kills the binder even when wireless ADB is
         * available and Shizuku manager is about to respawn shizuku_server —
         * posting instantly makes every unplug vibrate. If the binder comes
         * back within this window, [cancelPendingRestartNotification] skips
         * the post entirely.
         */
        private const val RESTART_NOTIFICATION_DEBOUNCE_MS = 5_000L
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

    /** applicationContext used by [binderDeadListener] to auto-launch Shizuku manager. */
    private var appContext: Context? = null

    /**
     * Set true once Shizuku's binder has been received at least once in this
     * process lifetime. Used by [launchShizukuManagerIfLostSinceBoot] so we
     * only auto-relaunch after a previously-healthy Shizuku disappears, not
     * on first-ever app launch (where Shizuku may legitimately be stopped and
     * the user should drive the start flow manually).
     */
    @Volatile
    private var hasBeenAvailable = false

    /**
     * Timestamp of the last auto-launch attempt. Used to throttle
     * [launchShizukuManagerIfLostSinceBoot] so we don't re-fire on every
     * onStart — otherwise, when the user dismisses the Shizuku manager, our
     * MainActivity resumes, fires auto-launch again, and the Shizuku UI
     * "keeps coming back" (a loop we saw on Samsung Flip testing 2026-04-18).
     */
    @Volatile
    private var lastAutoLaunchAtMs = 0L

    /**
     * Elapsed-realtime of the most recent [binderReceivedListener] fire. Pair
     * with the [binderDeadListener] timestamp to classify a session as a
     * "quick death" vs. a normal one — see [consecutiveQuickDeaths].
     */
    @Volatile
    private var lastBinderReceivedAtMs = 0L

    /**
     * Count of back-to-back sessions where Shizuku's binder died within
     * [QUICK_DEATH_WINDOW_MS] of coming up. Reset as soon as one session
     * manages to stay alive past that threshold. When this hits
     * [MAX_CONSECUTIVE_QUICK_DEATHS] we stop auto-launching the Shizuku
     * manager and surface a persistent notification instead.
     */
    @Volatile
    private var consecutiveQuickDeaths = 0

    /**
     * Holds WiFi awake for the entire lifetime of the setup (≈ app process
     * lifetime). Without this, Samsung's screen-off WiFi sleep trips
     * `AdbDebuggingManager: Network disconnected. Disabling adbwifi`, which
     * restarts adbd — and every shell-UID child (shizuku_server, watchdog)
     * dies with it. Held via [acquireWifiLock] / [releaseWifiLock].
     */
    private var wifiLock: WifiManager.WifiLock? = null

    /**
     * Pending (debounced) post of the "Shizuku stopped" notification. Armed
     * in [binderDeadListener] and cancelled in [binderReceivedListener] so a
     * transient adbd restart that recovers inside [RESTART_NOTIFICATION_DEBOUNCE_MS]
     * never alerts the user.
     */
    @Volatile
    private var pendingRestartNotification: Runnable? = null

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
        hasBeenAvailable = true
        lastAutoLaunchAtMs = 0L
        lastBinderReceivedAtMs = SystemClock.elapsedRealtime()
        updateState()
        cancelPendingRestartNotification()
        cancelRestartNotification()
        cancelGiveUpNotification()
        // Auto-bind PrivilegedService whenever Shizuku becomes available with permission,
        // so ensureShizukuHardened (driven by serviceConnected observers) runs at app
        // start instead of only when a browser connects. bindPrivilegedService is
        // idempotent via _serviceConnected / bindingInProgress guards.
        if (hasPermission()) {
            bindPrivilegedService()
        }
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        Log.i(TAG, "Shizuku binder dead")
        MirrorDiagnostics.log(DiagnosticEvent.SHIZUKU_BINDER_DEAD)
        privilegedService = null
        _serviceConnected.value = false
        bindingInProgress = false
        _state.value = ShizukuState.NotRunning
        val aliveMs = if (lastBinderReceivedAtMs == 0L) Long.MAX_VALUE
                      else SystemClock.elapsedRealtime() - lastBinderReceivedAtMs
        if (aliveMs < QUICK_DEATH_WINDOW_MS) {
            consecutiveQuickDeaths += 1
            Log.w(TAG, "Quick death #$consecutiveQuickDeaths (alive ${aliveMs}ms)")
        } else {
            consecutiveQuickDeaths = 0
        }
        // Shell-UID watchdog can't recover from adbd restarts (USB unplug on Samsung
        // triggers an adbd respawn that cleans out the entire shell UID — both
        // shizuku_server and the watchdog die together). Our app UID survives, so
        // we post a silent notification whose PendingIntent launches the Shizuku
        // manager Activity; tapping it triggers shizuku_server restart via saved
        // wireless-ADB authorization. We do NOT call startActivity directly
        // because when USB is unplugged the screen is typically off (TOP_SLEEPING),
        // which Android 14 refuses with BAL_BLOCK regardless of our foreground
        // state. The notification is visible on lockscreen and survives screen-off.
        //
        // Posting is debounced by RESTART_NOTIFICATION_DEBOUNCE_MS so a transient
        // adbd respawn that recovers inside the window makes no sound/vibration.
        scheduleRestartNotification()
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

    fun init(context: Context, bindService: Boolean = true) {
        appContext = context.applicationContext
        acquireWifiLock()
        deleteLegacyNotificationChannels()
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionResultListener)
        updateState()
        // Auto-bind if already permitted
        if (bindService && isAvailable() && hasPermission()) {
            bindPrivilegedService()
        }
    }

    private fun deleteLegacyNotificationChannels() {
        val ctx = appContext ?: return
        val mgr = ctx.getSystemService(NotificationManager::class.java) ?: return
        try {
            mgr.deleteNotificationChannel(LEGACY_RESTART_CHANNEL_ID)
            mgr.deleteNotificationChannel(LEGACY_GIVE_UP_CHANNEL_ID)
        } catch (_: Exception) {
        }
    }

    private fun acquireWifiLock() {
        if (wifiLock?.isHeld == true) return
        val ctx = appContext ?: return
        try {
            val wm = ctx.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
            @Suppress("DEPRECATION")
            val lock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Castla::ShizukuWifiLock")
            lock.setReferenceCounted(false)
            lock.acquire()
            wifiLock = lock
            Log.i(TAG, "ShizukuWifiLock acquired (WiFi stays awake across screen-off)")
        } catch (e: Exception) {
            Log.w(TAG, "acquireWifiLock failed", e)
        }
    }

    private fun releaseWifiLock() {
        try {
            wifiLock?.takeIf { it.isHeld }?.release()
        } catch (_: Exception) {
        }
        wifiLock = null
    }

    /**
     * Arm a debounced post of the restart notification. If the binder comes
     * back within [RESTART_NOTIFICATION_DEBOUNCE_MS] the pending runnable is
     * cancelled by [binderReceivedListener] and no notification is shown —
     * so Samsung's USB-unplug adbd-restart-then-recover case stays silent.
     */
    private fun scheduleRestartNotification() {
        cancelPendingRestartNotification()
        val runnable = Runnable {
            pendingRestartNotification = null
            requestShizukuRestart()
        }
        pendingRestartNotification = runnable
        mainHandler.postDelayed(runnable, RESTART_NOTIFICATION_DEBOUNCE_MS)
    }

    private fun cancelPendingRestartNotification() {
        pendingRestartNotification?.let { mainHandler.removeCallbacks(it) }
        pendingRestartNotification = null
    }

    /**
     * Posts a silent notification whose tap action launches the Shizuku
     * manager Activity. Used from [binderDeadListener] (via [scheduleRestartNotification])
     * to recover from adbd-restart cascades that kill shizuku_server + the
     * shell-UID watchdog together (USB unplug on Samsung). Direct startActivity
     * is unreliable here because the screen is typically off (TOP_SLEEPING)
     * → Android 14 BAL_BLOCK.
     *
     * The notification is silent (IMPORTANCE_LOW): visible in the tray and on
     * lockscreen, but no sound / no vibration / no heads-up — Shizuku dies
     * often enough on Samsung that a high-priority alert is spammy.
     *
     * Persists until the user taps it or binder is re-received (see
     * [cancelRestartNotification]). Tapping routes to Shizuku manager which
     * uses its saved wireless ADB authorization to bring shizuku_server back up.
     */
    fun requestShizukuRestart() {
        val ctx = appContext ?: run {
            Log.w(TAG, "requestShizukuRestart: appContext not set — skipping")
            return
        }
        if (!NotificationManagerCompat.from(ctx).areNotificationsEnabled()) {
            Log.w(TAG, "requestShizukuRestart: notifications disabled by user")
            return
        }
        ensureRestartChannel(ctx)
        val shizukuIntent = ctx.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
        if (shizukuIntent == null) {
            Log.w(TAG, "requestShizukuRestart: no launch intent for $SHIZUKU_PACKAGE")
            return
        }
        shizukuIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(
            ctx,
            RESTART_PENDING_INTENT_REQUEST,
            shizukuIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(ctx, RESTART_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("Shizuku stopped")
            .setContentText("Tap to restart — mirroring paused until Shizuku is running.")
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()
        try {
            NotificationManagerCompat.from(ctx)
                .notify(RESTART_NOTIFICATION_ID, notification)
            Log.i(TAG, "requestShizukuRestart: notification posted")
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS permission not granted on Android 13+.
            Log.w(TAG, "requestShizukuRestart: notify threw (missing permission)", e)
        }
    }

    private fun cancelRestartNotification() {
        val ctx = appContext ?: return
        try {
            NotificationManagerCompat.from(ctx).cancel(RESTART_NOTIFICATION_ID)
        } catch (_: Exception) {
            // best-effort
        }
    }

    /**
     * If Shizuku's binder has been alive at some point this process but isn't
     * now (typical USB-unplug → adbd restart scenario), launch the Shizuku
     * manager Activity directly. Intended to be called from an Activity's
     * onStart/onResume — i.e. when the caller is in the foreground and Android
     * 14 Background-Activity-Launch restrictions don't apply.
     *
     * No-op on first-ever app launch (when the user may have intentionally left
     * Shizuku stopped), while Shizuku is alive, and within a cooldown window
     * after a previous attempt (so dismissing the Shizuku UI doesn't loop back
     * into a re-launch every time our Activity resumes).
     *
     * Returns true if a launch was attempted.
     */
    fun launchShizukuManagerIfLostSinceBoot(): Boolean {
        if (!hasBeenAvailable) return false
        if (isAvailable()) return false
        if (consecutiveQuickDeaths >= MAX_CONSECUTIVE_QUICK_DEATHS) {
            Log.w(TAG, "Auto-launch suppressed: $consecutiveQuickDeaths quick deaths")
            postGiveUpNotification()
            return false
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastAutoLaunchAtMs < AUTO_LAUNCH_COOLDOWN_MS) return false
        lastAutoLaunchAtMs = now
        return launchShizukuManager()
    }

    /**
     * Unconditional direct launch of the Shizuku manager Activity. Caller is
     * responsible for being in the foreground so the OS allows the start.
     * Returns true iff startActivity was invoked without throwing.
     */
    fun launchShizukuManager(): Boolean {
        val ctx = appContext ?: run {
            Log.w(TAG, "launchShizukuManager: appContext not set — skipping")
            return false
        }
        val intent = ctx.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
        if (intent == null) {
            Log.w(TAG, "launchShizukuManager: no launch intent for $SHIZUKU_PACKAGE")
            return false
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return try {
            ctx.startActivity(intent)
            Log.i(TAG, "launchShizukuManager: startActivity dispatched")
            true
        } catch (e: Exception) {
            Log.w(TAG, "launchShizukuManager: startActivity threw", e)
            false
        }
    }

    private fun ensureRestartChannel(ctx: Context) {
        val mgr = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(RESTART_CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            RESTART_CHANNEL_ID,
            "Shizuku restart",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shown silently in the tray when Shizuku stops and needs a tap to restart."
            setShowBadge(true)
            enableVibration(false)
            setSound(null, null)
        }
        mgr.createNotificationChannel(channel)
    }

    /**
     * Posts a persistent notification when we've given up on auto-launching
     * Shizuku after repeated quick deaths. The usual cause is Android
     * disabling WADB on screen-off WiFi sleep (see [consecutiveQuickDeaths]
     * and the `AdbDebuggingManager: Network disconnected` log line); the user
     * can recover by plugging USB back in or keeping the screen awake.
     */
    private fun postGiveUpNotification() {
        val ctx = appContext ?: return
        if (!NotificationManagerCompat.from(ctx).areNotificationsEnabled()) return
        ensureGiveUpChannel(ctx)
        val shizukuIntent = ctx.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE) ?: return
        shizukuIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(
            ctx,
            GIVE_UP_NOTIFICATION_ID,
            shizukuIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(ctx, GIVE_UP_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("Shizuku keeps disconnecting")
            .setContentText("Plug USB back in, or keep the screen on to hold the wireless ADB connection.")
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                "Shizuku restarted $consecutiveQuickDeaths times and keeps dying within seconds. " +
                "Android disables wireless debugging when WiFi sleeps, which kills Shizuku. " +
                "Plug USB back in, or keep the screen on until mirroring stabilizes."
            ))
            .setContentIntent(pending)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(GIVE_UP_NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "postGiveUpNotification: notify threw", e)
        }
    }

    private fun cancelGiveUpNotification() {
        val ctx = appContext ?: return
        try {
            NotificationManagerCompat.from(ctx).cancel(GIVE_UP_NOTIFICATION_ID)
        } catch (_: Exception) {
        }
    }

    private fun ensureGiveUpChannel(ctx: Context) {
        val mgr = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(GIVE_UP_CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            GIVE_UP_CHANNEL_ID,
            "Shizuku unrecoverable",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Surfaces when Shizuku keeps dying and auto-recovery can't help."
            setShowBadge(true)
            enableVibration(false)
        }
        mgr.createNotificationChannel(channel)
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
     * Classify the device's persistent USB configuration for the Samsung
     * OneUI "Default USB Configuration" regression (see [UsbConfigChecker]).
     * Requires the privileged service to be connected — returns
     * [UsbConfigChecker.Advisory.Unknown] otherwise, which callers should
     * interpret as "don't warn, can't tell".
     *
     * Reads `persist.sys.usb.config` first (the user-configured default)
     * and falls back to `sys.usb.config` if the persisted prop is empty.
     */
    fun classifyUsbConfig(manufacturer: String): UsbConfigChecker.Advisory {
        val persisted = exec("getprop persist.sys.usb.config")?.trim().orEmpty()
        val cfg = if (persisted.isNotEmpty()) {
            persisted
        } else {
            exec("getprop sys.usb.config")?.trim().orEmpty()
        }
        return UsbConfigChecker.classify(manufacturer, cfg)
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

            // Heredocs cannot be used inside ShellDiag.buildScript steps: the wrapper
            // appends `; } 2>&1` to the same line as the step body, so a heredoc close
            // marker is never on a line by itself and the heredoc stays open, eating
            // the STEP_END marker. We transport script bodies via base64 single-line
            // decode instead — the base64 alphabet is quote-safe and newline-free.
            val innerB64 = Base64.encodeToString(innerScript.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            val outerB64 = Base64.encodeToString(outerScript.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

            // STAGE: write to .new paths (old processes still running untouched).
            // preflight_base64 round-trips a known token so a missing/broken `base64 -d`
            // surfaces as a clearly-named stage failure rather than silent truncation.
            val stageSteps = listOf(
                "preflight_base64" to "[ \"\$(printf '%s' 'Y2FzdGxh' | base64 -d)\" = 'castla' ]",
                "ensure_libdir" to "mkdir -p $libDir",
                "extract_librish" to
                    "unzip -o $shizukuApk lib/$abi/librish.so -d /data/local/tmp/shizuku_lib_tmp && " +
                    "cp /data/local/tmp/shizuku_lib_tmp/lib/$abi/librish.so $libDir/ && " +
                    "rm -rf /data/local/tmp/shizuku_lib_tmp",
                "write_inner" to "printf '%s' '$innerB64' | base64 -d > ${INNER_SCRIPT}.new",
                "write_outer" to "printf '%s' '$outerB64' | base64 -d > ${OUTER_SCRIPT}.new",
                "chmod_inner" to "chmod 755 ${INNER_SCRIPT}.new",
                "chmod_outer" to "chmod 755 ${OUTER_SCRIPT}.new"
            )
            val stageOut = service.execCommand(ShellDiag.buildScript(stageSteps)) ?: ""
            val stageResults = ShellDiag.parse(stageOut)
            val stageFailed = stageResults.any { it.rc != 0 } || stageResults.size != stageSteps.size
            if (stageFailed) {
                Log.e(TAG, "installWatchdog stage failed: ${summarizeStepFailure(stageResults)}")
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
                Log.e(TAG, "installWatchdog swap failed: ${summarizeStepFailure(swapResults)}")
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

    /**
     * Format a failed batch of ShellDiag steps for logcat: always list all name=rc
     * pairs, and append the first failing step's captured output (truncated to 200
     * chars, newlines collapsed) so device-specific failures are directly diagnosable.
     */
    private fun summarizeStepFailure(results: List<StepResult>): String {
        val summary = results.joinToString(",") { "${it.name}=${it.rc}" }
        val firstFailure = results.firstOrNull { it.rc != 0 } ?: return summary
        val detail = firstFailure.output
            .replace("\r\n", " ")
            .replace('\n', ' ')
            .replace('\r', ' ')
            .take(200)
        return "$summary step=${firstFailure.name} output=$detail"
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
        cancelPendingRestartNotification()
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
        releaseWifiLock()
    }
}
