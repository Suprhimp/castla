package com.castla.mirror.shizuku

/**
 * Pure classifier for the persistent USB function configuration (aka Android
 * Developer Options → "Default USB Configuration").
 *
 * Background: on Samsung OneUI 6.1.1+ (incl. OneUI 8 / Android 16) the OS
 * calls `UsbDeviceManager.trySetEnabledFunctions(..., forceRestart=true)` on
 * every screen lock and unlock to swap `mScreenUnlockedFunctions` — a
 * forceRestart=true there tears adbd down. Every shell-UID process dies with
 * it (shizuku_server + our watchdog), so Shizuku dies on each lock cycle.
 *
 * The toggle only happens when the base function is something other than
 * "charging" or "none". "File Transfer" (mtp) and "PTP" (ptp) are the common
 * defaults and both trigger the regression. "Charging only" and "Debugging
 * only" ("none" / "sec_charging") are stable across screen-lock events.
 *
 * So the fix we surface to the user is: set Default USB Configuration to
 * "Charging only" or "No data transfer" in Developer Options. This classifier
 * lets us detect whether the user is in the risky state.
 */
object UsbConfigChecker {
    enum class Advisory {
        /** Non-Samsung device — the regression does not apply. */
        NotApplicable,
        /** Samsung device, config string unreadable or empty — caller should not warn. */
        Unknown,
        /** Samsung device, config safe (no mtp/ptp in the function list). */
        Safe,
        /** Samsung device, mtp or ptp present in the function list — warn user. */
        RiskyUsbConfig,
    }

    /**
     * @param manufacturer [android.os.Build.MANUFACTURER], e.g. "samsung".
     * @param persistedUsbConfig value of `persist.sys.usb.config` (or
     *   `sys.usb.config` as a fallback). Typical shapes: "mtp,adb",
     *   "sec_charging,adb", "ptp,adb", "none".
     */
    fun classify(manufacturer: String?, persistedUsbConfig: String?): Advisory {
        if (manufacturer.isNullOrBlank() || !manufacturer.equals("samsung", ignoreCase = true)) {
            return Advisory.NotApplicable
        }
        val cfg = persistedUsbConfig?.trim().orEmpty()
        if (cfg.isEmpty()) return Advisory.Unknown
        val functions = cfg.lowercase().split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (functions.isEmpty()) return Advisory.Unknown
        val risky = functions.any { it == "mtp" || it == "ptp" }
        return if (risky) Advisory.RiskyUsbConfig else Advisory.Safe
    }
}
