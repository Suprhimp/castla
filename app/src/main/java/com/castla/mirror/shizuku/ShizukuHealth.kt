package com.castla.mirror.shizuku

/**
 * Pure classifier for the Shizuku watchdog heartbeat age.
 *
 * The watchdog writes a unix timestamp to a heartbeat file every ~5s while
 * healthy. Callers compute `age = now - heartbeatTimestamp` and pass it here.
 *
 * Negative age (future timestamp, e.g. from clock skew) or missing heartbeat
 * (age < 0) is surfaced as [State.Unknown] — callers should not assume health.
 */
object ShizukuHealth {
    sealed class State {
        object Healthy : State()
        object Warning : State()
        object Critical : State()
        object Unknown : State()
    }

    fun classify(heartbeatAgeSeconds: Long): State = when {
        heartbeatAgeSeconds < 0 -> State.Unknown
        heartbeatAgeSeconds < 30 -> State.Healthy
        heartbeatAgeSeconds < 120 -> State.Warning
        else -> State.Critical
    }
}
