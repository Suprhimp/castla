package com.castla.mirror.policy

/**
 * States for the screen-off mirroring state machine.
 */
enum class ScreenOffState {
    /** Normal operation — physical screen is on, VD rendering normally. */
    ACTIVE,
    /** Panel-off requested but not yet confirmed. */
    PANEL_OFF_PENDING,
    /** Physical panel is off via SurfaceControl; device stays awake, VD renders. */
    PANEL_OFF_ACTIVE,
    /** Panel-off not supported; using periodic VD wake-up instead. */
    KEEP_ALIVE_ACTIVE,
}

/**
 * Actions the service should take in response to state transitions.
 */
enum class ScreenOffAction {
    NONE,
    TURN_PANEL_OFF,
    START_KEEP_ALIVE,
    RESTORE_PANEL,
    STOP_KEEP_ALIVE,
}

/**
 * Pure-logic state machine for screen-off mirroring.
 *
 * Strategy priority:
 * 1. Physical panel OFF (scrcpy approach) — Samsung preferred path.
 *    Device stays awake, VD keeps rendering, physical screen goes dark.
 * 2. Keep-alive (periodic wakeUpDisplay) — fallback when panel-off fails.
 * 3. Normal lock — not actively supported (may cause black screen on VD).
 *
 * Thread safety: call from a single thread (main/service scope).
 */
class ScreenOffPolicy {

    var state: ScreenOffState = ScreenOffState.ACTIVE
        private set

    /** Whether this device supports panel-off. Set to false on first failure. */
    var isPanelOffSupported: Boolean = true
        private set

    /** True when the physical screen is off (any non-ACTIVE state). */
    val isScreenOff: Boolean
        get() = state != ScreenOffState.ACTIVE

    /**
     * Called when ACTION_SCREEN_OFF is received.
     * @param panelOffSupported whether to attempt panel-off (check [isPanelOffSupported])
     * @return action the service should execute
     */
    fun onScreenOff(panelOffSupported: Boolean): ScreenOffAction {
        if (state != ScreenOffState.ACTIVE) return ScreenOffAction.NONE

        return if (panelOffSupported) {
            state = ScreenOffState.PANEL_OFF_PENDING
            ScreenOffAction.TURN_PANEL_OFF
        } else {
            state = ScreenOffState.KEEP_ALIVE_ACTIVE
            ScreenOffAction.START_KEEP_ALIVE
        }
    }

    /**
     * Called after setPhysicalDisplayPower(false) completes.
     * @param success whether the panel-off call succeeded
     * @return fallback action if panel-off failed, NONE otherwise
     */
    fun onPanelOffResult(success: Boolean): ScreenOffAction {
        if (state != ScreenOffState.PANEL_OFF_PENDING) return ScreenOffAction.NONE

        return if (success) {
            state = ScreenOffState.PANEL_OFF_ACTIVE
            ScreenOffAction.NONE
        } else {
            isPanelOffSupported = false
            state = ScreenOffState.KEEP_ALIVE_ACTIVE
            ScreenOffAction.START_KEEP_ALIVE
        }
    }

    /**
     * Called when ACTION_SCREEN_ON is received.
     * @return action the service should execute to restore normal state
     */
    fun onScreenOn(): ScreenOffAction {
        val action = when (state) {
            ScreenOffState.PANEL_OFF_ACTIVE, ScreenOffState.PANEL_OFF_PENDING -> ScreenOffAction.RESTORE_PANEL
            ScreenOffState.KEEP_ALIVE_ACTIVE -> ScreenOffAction.STOP_KEEP_ALIVE
            ScreenOffState.ACTIVE -> ScreenOffAction.NONE
        }
        state = ScreenOffState.ACTIVE
        return action
    }

    /** Reset to initial state (e.g., on service restart). */
    fun reset() {
        state = ScreenOffState.ACTIVE
        isPanelOffSupported = true
    }
}
