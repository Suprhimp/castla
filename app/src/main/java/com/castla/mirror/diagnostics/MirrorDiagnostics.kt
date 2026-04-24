package com.castla.mirror.diagnostics

import android.os.SystemClock
import android.util.Log

/**
 * Structured diagnostic events emitted during a mirroring session.
 * Each event is logged with a consistent tag and prefix for easy filtering.
 */
enum class DiagnosticEvent {
    SCREEN_OFF,
    SCREEN_ON,
    KEYGUARD_LOCKED,
    KEYGUARD_UNLOCKED,
    SHIZUKU_BINDER_DEAD,
    SHIZUKU_BINDER_READY,
    /** Informational: process fortification applied (doze whitelist, appops, oom_adj). */
    SHIZUKU_FORTIFIED,
    VD_CREATED,
    VD_STOPPED,
    SOCKET_DISCONNECTED,
    SOCKET_TIMEOUT,
    SESSION_END
}

/**
 * High-level cause categories for session disconnects.
 * Used in the final SESSION_END event to classify *why* the session ended.
 */
enum class DisconnectCause {
    /** Shizuku binder died or PrivilegedService became unreachable. */
    SHIZUKU,
    /** Virtual display was released or became invalid. */
    VIRTUAL_DISPLAY,
    /** All browser sockets disconnected (network issue or browser closed). */
    NETWORK,
    /** App process killed or device entered deep sleep despite wake locks. */
    PROCESS_OR_POWER,
    /** Could not determine cause from available signals. */
    UNKNOWN
}

/**
 * Pure classifier — given the set of recent diagnostic events that preceded a
 * session end, returns the most likely [DisconnectCause].
 *
 * Walks the event list from most recent to oldest. Recovery events
 * (`SHIZUKU_BINDER_READY`, `VD_CREATED`) cancel out their corresponding
 * failure events, so a successful reconnect/rebuild does not contaminate
 * classification. The first *unrecovered* strong signal wins:
 *
 *  1. Unrecovered `SHIZUKU_BINDER_DEAD`              → SHIZUKU
 *  2. Unrecovered `VD_STOPPED`                        → VIRTUAL_DISPLAY
 *  3. `SOCKET_DISCONNECTED` or `SOCKET_TIMEOUT`       → NETWORK
 *  4. `SCREEN_OFF` present (with no strong signal)     → PROCESS_OR_POWER
 *  5. fallback                                         → UNKNOWN
 *
 * Informational events like `SHIZUKU_FORTIFIED` are ignored by the classifier
 * and must not affect the outcome.
 */
object DisconnectCauseClassifier {

    fun classify(recentEvents: List<DiagnosticEvent>): DisconnectCause {
        // Track how many recovery events we've seen while walking backwards.
        // Each recovery "absorbs" one preceding failure of the same kind.
        var shizukuRecoveries = 0
        var vdRecoveries = 0

        for (event in recentEvents.asReversed()) {
            when (event) {
                // Recovery events: accumulate a credit that cancels one failure
                DiagnosticEvent.SHIZUKU_BINDER_READY -> shizukuRecoveries++
                DiagnosticEvent.VD_CREATED -> vdRecoveries++

                // Failure events: only count if not cancelled by a later recovery
                DiagnosticEvent.SHIZUKU_BINDER_DEAD -> {
                    if (shizukuRecoveries > 0) {
                        shizukuRecoveries--
                    } else {
                        return DisconnectCause.SHIZUKU
                    }
                }
                DiagnosticEvent.VD_STOPPED -> {
                    if (vdRecoveries > 0) {
                        vdRecoveries--
                    } else {
                        return DisconnectCause.VIRTUAL_DISPLAY
                    }
                }
                DiagnosticEvent.SOCKET_DISCONNECTED,
                DiagnosticEvent.SOCKET_TIMEOUT -> return DisconnectCause.NETWORK

                else -> { /* continue scanning */ }
            }
        }
        // No strong signal — check if screen was off (power/OEM kill suspected)
        if (recentEvents.any { it == DiagnosticEvent.SCREEN_OFF }) {
            return DisconnectCause.PROCESS_OR_POWER
        }
        return DisconnectCause.UNKNOWN
    }
}

/**
 * Central diagnostic logger for mirroring sessions.
 * Logs structured events with a consistent format and maintains a small ring
 * buffer of recent events for disconnect cause classification.
 *
 * Thread-safe: all public methods synchronize on [recentEvents].
 */
object MirrorDiagnostics {

    private const val TAG = "MirrorDiag"
    private const val MAX_RECENT_EVENTS = 32

    private val recentEvents = mutableListOf<DiagnosticEvent>()
    private var sessionStartUptimeMs = 0L
    @Volatile private var sessionActive = false

    /** Call when a new mirroring session starts to reset state. */
    fun onSessionStart() {
        synchronized(recentEvents) {
            recentEvents.clear()
            sessionStartUptimeMs = SystemClock.elapsedRealtime()
            sessionActive = true
        }
        Log.i(TAG, "[SESSION_START]")
    }

    /** Record a diagnostic event with optional detail string. */
    fun log(event: DiagnosticEvent, detail: String? = null) {
        val elapsed = if (sessionActive) SystemClock.elapsedRealtime() - sessionStartUptimeMs else 0L
        synchronized(recentEvents) {
            recentEvents.add(event)
            if (recentEvents.size > MAX_RECENT_EVENTS) {
                recentEvents.removeAt(0)
            }
        }
        val msg = buildString {
            append("[${event.name}]")
            append(" +${elapsed}ms")
            if (detail != null) append(" $detail")
        }
        Log.i(TAG, msg)
    }

    /**
     * Classify and log the disconnect cause at session end.
     * No-ops if no session was started (guards against cleanup-before-start).
     * Returns the classified [DisconnectCause], or null if no session was active.
     */
    fun endSession(cleanupReason: String): DisconnectCause? {
        if (!sessionActive) {
            Log.d(TAG, "[SESSION_END_SKIPPED] reason=$cleanupReason (no active session)")
            return null
        }
        val events: List<DiagnosticEvent>
        synchronized(recentEvents) {
            events = recentEvents.toList()
            sessionActive = false
        }
        val cause = DisconnectCauseClassifier.classify(events)
        log(DiagnosticEvent.SESSION_END, "reason=$cleanupReason cause=${cause.name}")
        Log.i(TAG, "[SESSION_SUMMARY] duration=${SystemClock.elapsedRealtime() - sessionStartUptimeMs}ms " +
                "events=${events.size} cause=${cause.name} recent=${events.takeLast(5).map { it.name }}")
        return cause
    }
}
