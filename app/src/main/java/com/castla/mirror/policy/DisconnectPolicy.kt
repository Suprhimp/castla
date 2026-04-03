package com.castla.mirror.policy

/**
 * Pure-logic policy for browser-disconnect handling.
 *
 * When the phone screen is off the Tesla browser socket may drop briefly;
 * tearing down the pipeline in that window kills wake-locks and makes
 * recovery impossible.  This policy extends the grace period and defers
 * teardown until the screen turns back on.
 */
object DisconnectPolicy {

    /** Normal grace period — survives a browser tab refresh. */
    const val DEFAULT_GRACE_MS = 3_000L

    /** Extended grace while the physical screen is off. */
    const val SCREEN_OFF_GRACE_MS = 15_000L

    /** Returns the appropriate grace period based on screen state. */
    fun graceMs(isScreenOff: Boolean): Long =
        if (isScreenOff) SCREEN_OFF_GRACE_MS else DEFAULT_GRACE_MS

    /**
     * Whether the pipeline should be torn down right now.
     *
     * Returns `false` when the screen is off — teardown is deferred
     * until the screen turns back on so that wake-locks stay held.
     */
    fun shouldTeardown(isScreenOff: Boolean, isBrowserConnected: Boolean): Boolean =
        !isBrowserConnected && !isScreenOff
}
