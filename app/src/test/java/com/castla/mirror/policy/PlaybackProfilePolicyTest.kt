package com.castla.mirror.policy

import com.castla.mirror.policy.PlaybackProfilePolicy.Profile
import com.castla.mirror.policy.PlaybackProfilePolicy.ThermalLevel
import org.junit.Assert.*
import org.junit.Test

class PlaybackProfilePolicyTest {

    // ── resolveEffectiveProfile ──

    @Test
    fun `no thermal cap - user gets preferred profile`() {
        assertEquals(
            Profile.SMOOTH,
            PlaybackProfilePolicy.resolveEffectiveProfile(Profile.SMOOTH, ThermalLevel.NONE)
        )
        assertEquals(
            Profile.BALANCED,
            PlaybackProfilePolicy.resolveEffectiveProfile(Profile.BALANCED, ThermalLevel.NONE)
        )
        assertEquals(
            Profile.LOW_LATENCY,
            PlaybackProfilePolicy.resolveEffectiveProfile(Profile.LOW_LATENCY, ThermalLevel.NONE)
        )
    }

    @Test
    fun `moderate thermal caps smooth to balanced`() {
        assertEquals(
            Profile.BALANCED,
            PlaybackProfilePolicy.resolveEffectiveProfile(Profile.SMOOTH, ThermalLevel.MODERATE)
        )
    }

    @Test
    fun `moderate thermal keeps balanced as balanced`() {
        assertEquals(
            Profile.BALANCED,
            PlaybackProfilePolicy.resolveEffectiveProfile(Profile.BALANCED, ThermalLevel.MODERATE)
        )
    }

    @Test
    fun `moderate thermal keeps low_latency as low_latency`() {
        assertEquals(
            Profile.LOW_LATENCY,
            PlaybackProfilePolicy.resolveEffectiveProfile(Profile.LOW_LATENCY, ThermalLevel.MODERATE)
        )
    }

    @Test
    fun `severe thermal forces everything to low_latency`() {
        assertEquals(
            Profile.LOW_LATENCY,
            PlaybackProfilePolicy.resolveEffectiveProfile(Profile.SMOOTH, ThermalLevel.SEVERE)
        )
        assertEquals(
            Profile.LOW_LATENCY,
            PlaybackProfilePolicy.resolveEffectiveProfile(Profile.BALANCED, ThermalLevel.SEVERE)
        )
        assertEquals(
            Profile.LOW_LATENCY,
            PlaybackProfilePolicy.resolveEffectiveProfile(Profile.LOW_LATENCY, ThermalLevel.SEVERE)
        )
    }

    @Test
    fun `light thermal caps smooth to balanced`() {
        assertEquals(
            Profile.BALANCED,
            PlaybackProfilePolicy.resolveEffectiveProfile(Profile.SMOOTH, ThermalLevel.LIGHT)
        )
    }

    @Test
    fun `light thermal keeps balanced`() {
        assertEquals(
            Profile.BALANCED,
            PlaybackProfilePolicy.resolveEffectiveProfile(Profile.BALANCED, ThermalLevel.LIGHT)
        )
    }

    @Test
    fun `light thermal keeps low_latency`() {
        assertEquals(
            Profile.LOW_LATENCY,
            PlaybackProfilePolicy.resolveEffectiveProfile(Profile.LOW_LATENCY, ThermalLevel.LIGHT)
        )
    }

    @Test
    fun `thermal never upcaps - user conservative preference preserved`() {
        // Even with no thermal pressure, if user prefers LOW_LATENCY, it stays
        assertEquals(
            Profile.LOW_LATENCY,
            PlaybackProfilePolicy.resolveEffectiveProfile(Profile.LOW_LATENCY, ThermalLevel.NONE)
        )
    }

    // ── thermalLevelFromStatus ──

    @Test
    fun `status 0 maps to NONE`() {
        assertEquals(ThermalLevel.NONE, PlaybackProfilePolicy.thermalLevelFromStatus(0))
    }

    @Test
    fun `status 1 maps to LIGHT`() {
        assertEquals(ThermalLevel.LIGHT, PlaybackProfilePolicy.thermalLevelFromStatus(1))
    }

    @Test
    fun `status 2 maps to MODERATE`() {
        assertEquals(ThermalLevel.MODERATE, PlaybackProfilePolicy.thermalLevelFromStatus(2))
    }

    @Test
    fun `status 3 maps to SEVERE`() {
        assertEquals(ThermalLevel.SEVERE, PlaybackProfilePolicy.thermalLevelFromStatus(3))
    }

    @Test
    fun `status 4 CRITICAL maps to SEVERE`() {
        assertEquals(ThermalLevel.SEVERE, PlaybackProfilePolicy.thermalLevelFromStatus(4))
    }

    @Test
    fun `status 5 EMERGENCY maps to SEVERE`() {
        assertEquals(ThermalLevel.SEVERE, PlaybackProfilePolicy.thermalLevelFromStatus(5))
    }

    @Test
    fun `negative status maps to NONE`() {
        assertEquals(ThermalLevel.NONE, PlaybackProfilePolicy.thermalLevelFromStatus(-1))
    }

    // ── Full matrix: all profile x thermal combinations ──

    @Test
    fun `full profile x thermal matrix`() {
        data class Case(val preferred: Profile, val thermal: ThermalLevel, val expected: Profile)

        val cases = listOf(
            // NONE — no cap
            Case(Profile.LOW_LATENCY, ThermalLevel.NONE, Profile.LOW_LATENCY),
            Case(Profile.BALANCED, ThermalLevel.NONE, Profile.BALANCED),
            Case(Profile.SMOOTH, ThermalLevel.NONE, Profile.SMOOTH),
            // LIGHT — cap at BALANCED
            Case(Profile.LOW_LATENCY, ThermalLevel.LIGHT, Profile.LOW_LATENCY),
            Case(Profile.BALANCED, ThermalLevel.LIGHT, Profile.BALANCED),
            Case(Profile.SMOOTH, ThermalLevel.LIGHT, Profile.BALANCED),
            // MODERATE — cap at BALANCED
            Case(Profile.LOW_LATENCY, ThermalLevel.MODERATE, Profile.LOW_LATENCY),
            Case(Profile.BALANCED, ThermalLevel.MODERATE, Profile.BALANCED),
            Case(Profile.SMOOTH, ThermalLevel.MODERATE, Profile.BALANCED),
            // SEVERE — cap at LOW_LATENCY
            Case(Profile.LOW_LATENCY, ThermalLevel.SEVERE, Profile.LOW_LATENCY),
            Case(Profile.BALANCED, ThermalLevel.SEVERE, Profile.LOW_LATENCY),
            Case(Profile.SMOOTH, ThermalLevel.SEVERE, Profile.LOW_LATENCY)
        )

        for ((preferred, thermal, expected) in cases) {
            assertEquals(
                "preferred=$preferred thermal=$thermal",
                expected,
                PlaybackProfilePolicy.resolveEffectiveProfile(preferred, thermal)
            )
        }
    }
}
