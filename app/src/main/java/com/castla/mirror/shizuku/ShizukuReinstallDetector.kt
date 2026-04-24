package com.castla.mirror.shizuku

/**
 * Decides whether the app has been (re)installed since the last time we
 * recorded `PackageInfo.lastUpdateTime`. On reinstall, Shizuku's cached
 * user-service record points at a dead binder from the previous process —
 * we must call `unbindUserService(..., remove=true)` before binding again
 * or the next bind silently fails (token mismatch symptom).
 *
 * `lastUpdateTime` updates on every install/update, including `adb install -r`
 * with no version bump, which is the common development case.
 */
object ShizukuReinstallDetector {
    sealed class Action {
        /** No reinstall detected — proceed with a normal bind. */
        object None : Action()

        /** Reinstall or first-ever launch — unbind with remove=true before binding. */
        object ForceRebind : Action()
    }

    fun detect(savedLastUpdateTime: Long, currentLastUpdateTime: Long): Action {
        if (currentLastUpdateTime <= 0L) return Action.None
        return if (savedLastUpdateTime != currentLastUpdateTime) Action.ForceRebind else Action.None
    }
}
