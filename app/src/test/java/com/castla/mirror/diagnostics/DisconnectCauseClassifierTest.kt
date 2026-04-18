package com.castla.mirror.diagnostics

import org.junit.Assert.*
import org.junit.Test

class DisconnectCauseClassifierTest {

    // ── Single-event classification ──

    @Test
    fun `shizuku binder dead classifies as SHIZUKU`() {
        val events = listOf(DiagnosticEvent.SHIZUKU_BINDER_DEAD)
        assertEquals(DisconnectCause.SHIZUKU, DisconnectCauseClassifier.classify(events))
    }

    @Test
    fun `vd stopped classifies as VIRTUAL_DISPLAY`() {
        val events = listOf(DiagnosticEvent.VD_STOPPED)
        assertEquals(DisconnectCause.VIRTUAL_DISPLAY, DisconnectCauseClassifier.classify(events))
    }

    @Test
    fun `socket disconnected classifies as NETWORK`() {
        val events = listOf(DiagnosticEvent.SOCKET_DISCONNECTED)
        assertEquals(DisconnectCause.NETWORK, DisconnectCauseClassifier.classify(events))
    }

    @Test
    fun `socket timeout classifies as NETWORK`() {
        val events = listOf(DiagnosticEvent.SOCKET_TIMEOUT)
        assertEquals(DisconnectCause.NETWORK, DisconnectCauseClassifier.classify(events))
    }

    @Test
    fun `screen off alone classifies as PROCESS_OR_POWER`() {
        val events = listOf(DiagnosticEvent.SCREEN_OFF)
        assertEquals(DisconnectCause.PROCESS_OR_POWER, DisconnectCauseClassifier.classify(events))
    }

    @Test
    fun `empty events classifies as UNKNOWN`() {
        assertEquals(DisconnectCause.UNKNOWN, DisconnectCauseClassifier.classify(emptyList()))
    }

    // ── Most recent unrecovered strong signal wins ──

    @Test
    fun `shizuku dead after socket disconnect classifies as SHIZUKU`() {
        val events = listOf(
            DiagnosticEvent.SOCKET_DISCONNECTED,
            DiagnosticEvent.SHIZUKU_BINDER_DEAD
        )
        assertEquals(DisconnectCause.SHIZUKU, DisconnectCauseClassifier.classify(events))
    }

    @Test
    fun `socket disconnect after shizuku dead classifies as NETWORK`() {
        val events = listOf(
            DiagnosticEvent.SHIZUKU_BINDER_DEAD,
            DiagnosticEvent.SOCKET_DISCONNECTED
        )
        assertEquals(DisconnectCause.NETWORK, DisconnectCauseClassifier.classify(events))
    }

    @Test
    fun `vd stopped after socket timeout classifies as VIRTUAL_DISPLAY`() {
        val events = listOf(
            DiagnosticEvent.SOCKET_TIMEOUT,
            DiagnosticEvent.VD_STOPPED
        )
        assertEquals(DisconnectCause.VIRTUAL_DISPLAY, DisconnectCauseClassifier.classify(events))
    }

    // ── Screen-off as weak signal ──

    @Test
    fun `screen off with subsequent shizuku dead classifies as SHIZUKU not PROCESS_OR_POWER`() {
        val events = listOf(
            DiagnosticEvent.SCREEN_OFF,
            DiagnosticEvent.KEYGUARD_LOCKED,
            DiagnosticEvent.SHIZUKU_BINDER_DEAD
        )
        assertEquals(DisconnectCause.SHIZUKU, DisconnectCauseClassifier.classify(events))
    }

    @Test
    fun `screen off with keyguard but no strong signal classifies as PROCESS_OR_POWER`() {
        val events = listOf(
            DiagnosticEvent.SCREEN_OFF,
            DiagnosticEvent.KEYGUARD_LOCKED
        )
        assertEquals(DisconnectCause.PROCESS_OR_POWER, DisconnectCauseClassifier.classify(events))
    }

    // ── Recovery cancellation ──

    @Test
    fun `shizuku recovery cancels prior shizuku death`() {
        val events = listOf(
            DiagnosticEvent.SHIZUKU_BINDER_DEAD,
            DiagnosticEvent.SHIZUKU_BINDER_READY
        )
        assertEquals(DisconnectCause.UNKNOWN, DisconnectCauseClassifier.classify(events))
    }

    @Test
    fun `vd rebuild cancels prior vd stop`() {
        val events = listOf(
            DiagnosticEvent.VD_STOPPED,
            DiagnosticEvent.VD_CREATED
        )
        assertEquals(DisconnectCause.UNKNOWN, DisconnectCauseClassifier.classify(events))
    }

    @Test
    fun `recovered shizuku then later socket disconnect classifies as NETWORK not SHIZUKU`() {
        val events = listOf(
            DiagnosticEvent.SHIZUKU_BINDER_DEAD,
            DiagnosticEvent.SHIZUKU_BINDER_READY,
            DiagnosticEvent.SOCKET_DISCONNECTED
        )
        assertEquals(DisconnectCause.NETWORK, DisconnectCauseClassifier.classify(events))
    }

    @Test
    fun `vd rebuild during pipeline resize does not cause VIRTUAL_DISPLAY classification`() {
        // Typical pipeline rebuild: stop old VD, create new VD, then session ends for unrelated reason
        val events = listOf(
            DiagnosticEvent.VD_CREATED,       // initial
            DiagnosticEvent.VD_STOPPED,       // resize teardown
            DiagnosticEvent.VD_CREATED,       // resize rebuild
            DiagnosticEvent.SCREEN_OFF        // session ends due to power
        )
        assertEquals(DisconnectCause.PROCESS_OR_POWER, DisconnectCauseClassifier.classify(events))
    }

    @Test
    fun `double shizuku death with single recovery leaves one unrecovered`() {
        val events = listOf(
            DiagnosticEvent.SHIZUKU_BINDER_DEAD,
            DiagnosticEvent.SHIZUKU_BINDER_READY,
            DiagnosticEvent.SHIZUKU_BINDER_DEAD
        )
        assertEquals(DisconnectCause.SHIZUKU, DisconnectCauseClassifier.classify(events))
    }

    @Test
    fun `multiple vd rebuilds followed by unrecovered stop classifies as VIRTUAL_DISPLAY`() {
        val events = listOf(
            DiagnosticEvent.VD_CREATED,
            DiagnosticEvent.VD_STOPPED,       // rebuild 1
            DiagnosticEvent.VD_CREATED,
            DiagnosticEvent.VD_STOPPED,       // rebuild 2
            DiagnosticEvent.VD_CREATED,
            DiagnosticEvent.VD_STOPPED        // final death — no recovery
        )
        assertEquals(DisconnectCause.VIRTUAL_DISPLAY, DisconnectCauseClassifier.classify(events))
    }

    // ── Noise events are ignored ──

    @Test
    fun `screen on and vd created alone are not strong signals`() {
        val events = listOf(
            DiagnosticEvent.VD_CREATED,
            DiagnosticEvent.SCREEN_ON,
            DiagnosticEvent.SHIZUKU_BINDER_READY
        )
        assertEquals(DisconnectCause.UNKNOWN, DisconnectCauseClassifier.classify(events))
    }

    // ── Realistic multi-event sequences ──

    @Test
    fun `typical lock-screen kill sequence classifies as PROCESS_OR_POWER`() {
        val events = listOf(
            DiagnosticEvent.VD_CREATED,
            DiagnosticEvent.SHIZUKU_BINDER_READY,
            DiagnosticEvent.SCREEN_OFF,
            DiagnosticEvent.KEYGUARD_LOCKED
            // no socket or binder events — process was killed
        )
        assertEquals(DisconnectCause.PROCESS_OR_POWER, DisconnectCauseClassifier.classify(events))
    }

    @Test
    fun `shizuku death cascade classifies as NETWORK`() {
        val events = listOf(
            DiagnosticEvent.VD_CREATED,
            DiagnosticEvent.SCREEN_OFF,
            DiagnosticEvent.KEYGUARD_LOCKED,
            DiagnosticEvent.SHIZUKU_BINDER_DEAD,
            DiagnosticEvent.VD_STOPPED,
            DiagnosticEvent.SOCKET_DISCONNECTED
        )
        // Most recent strong signal is SOCKET_DISCONNECTED
        assertEquals(DisconnectCause.NETWORK, DisconnectCauseClassifier.classify(events))
    }

    @Test
    fun `full reconnect then clean disconnect classifies as NETWORK`() {
        val events = listOf(
            DiagnosticEvent.VD_CREATED,
            DiagnosticEvent.SCREEN_OFF,
            DiagnosticEvent.SHIZUKU_BINDER_DEAD,    // dies
            DiagnosticEvent.SHIZUKU_BINDER_READY,   // recovers
            DiagnosticEvent.VD_STOPPED,              // rebuild old VD
            DiagnosticEvent.VD_CREATED,              // rebuild new VD
            DiagnosticEvent.SCREEN_ON,
            DiagnosticEvent.SOCKET_DISCONNECTED      // browser navigated away
        )
        assertEquals(DisconnectCause.NETWORK, DisconnectCauseClassifier.classify(events))
    }

    // ── Informational events are ignored ──

    @Test
    fun `shizuku fortified alone classifies as UNKNOWN`() {
        assertEquals(
            DisconnectCause.UNKNOWN,
            DisconnectCauseClassifier.classify(listOf(DiagnosticEvent.SHIZUKU_FORTIFIED))
        )
    }

    @Test
    fun `shizuku fortified mixed with binder dead still classifies as SHIZUKU`() {
        val events = listOf(
            DiagnosticEvent.SHIZUKU_FORTIFIED,
            DiagnosticEvent.SHIZUKU_BINDER_DEAD,
            DiagnosticEvent.SHIZUKU_FORTIFIED
        )
        assertEquals(DisconnectCause.SHIZUKU, DisconnectCauseClassifier.classify(events))
    }

    @Test
    fun `shizuku fortified does not cancel binder death nor substitute for recovery`() {
        val events = listOf(
            DiagnosticEvent.SHIZUKU_BINDER_DEAD,
            DiagnosticEvent.SHIZUKU_FORTIFIED,
            DiagnosticEvent.SHIZUKU_BINDER_READY
        )
        // The real recovery (SHIZUKU_BINDER_READY) still cancels the death;
        // SHIZUKU_FORTIFIED is informational and irrelevant to classification.
        assertEquals(DisconnectCause.UNKNOWN, DisconnectCauseClassifier.classify(events))
    }
}
