package com.castla.mirror.policy

/**
 * Pure-function playback profile policy.
 * Determines the effective playback profile by capping the user's preferred
 * profile at the thermal-safe maximum.
 *
 * Mirrors the browser-side logic in main.js (resolveEffectiveProfile / THERMAL_MAX_PROFILE)
 * to enable server-side testing and potential future use.
 */
object PlaybackProfilePolicy {

    enum class Profile(val rank: Int) {
        LOW_LATENCY(0),
        BALANCED(1),
        SMOOTH(2)
    }

    enum class ThermalLevel {
        NONE, LIGHT, MODERATE, SEVERE
    }

    /** Maximum allowed profile for each thermal level. */
    private val THERMAL_CAP = mapOf(
        ThermalLevel.SEVERE to Profile.LOW_LATENCY,
        ThermalLevel.MODERATE to Profile.BALANCED,
        ThermalLevel.LIGHT to Profile.BALANCED,
        ThermalLevel.NONE to Profile.SMOOTH
    )

    /**
     * Resolve the effective profile: min(preferred, thermalCap).
     * A lower rank means more conservative — we always pick the more conservative
     * of the user's preference and the thermal cap.
     */
    fun resolveEffectiveProfile(preferred: Profile, thermal: ThermalLevel): Profile {
        val cap = THERMAL_CAP[thermal] ?: Profile.SMOOTH
        return if (preferred.rank <= cap.rank) preferred else cap
    }

    /**
     * Map Android PowerManager thermal status integer to ThermalLevel.
     * Uses integer values to avoid API level requirements:
     * 0=NONE, 1=LIGHT, 2=MODERATE, 3=SEVERE, 4=CRITICAL, 5=EMERGENCY
     */
    fun thermalLevelFromStatus(status: Int): ThermalLevel = when {
        status >= 3 -> ThermalLevel.SEVERE
        status == 2 -> ThermalLevel.MODERATE
        status == 1 -> ThermalLevel.LIGHT
        else -> ThermalLevel.NONE
    }
}
