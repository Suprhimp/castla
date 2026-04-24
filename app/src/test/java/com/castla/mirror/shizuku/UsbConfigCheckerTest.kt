package com.castla.mirror.shizuku

import org.junit.Assert.assertEquals
import org.junit.Test

class UsbConfigCheckerTest {

    @Test
    fun `non-samsung manufacturer returns NotApplicable`() {
        assertEquals(
            UsbConfigChecker.Advisory.NotApplicable,
            UsbConfigChecker.classify("Google", "mtp,adb")
        )
    }

    @Test
    fun `null manufacturer returns NotApplicable`() {
        assertEquals(
            UsbConfigChecker.Advisory.NotApplicable,
            UsbConfigChecker.classify(null, "mtp,adb")
        )
    }

    @Test
    fun `blank manufacturer returns NotApplicable`() {
        assertEquals(
            UsbConfigChecker.Advisory.NotApplicable,
            UsbConfigChecker.classify("  ", "mtp,adb")
        )
    }

    @Test
    fun `samsung with mtp returns RiskyUsbConfig`() {
        assertEquals(
            UsbConfigChecker.Advisory.RiskyUsbConfig,
            UsbConfigChecker.classify("samsung", "mtp,adb")
        )
    }

    @Test
    fun `samsung with ptp returns RiskyUsbConfig`() {
        assertEquals(
            UsbConfigChecker.Advisory.RiskyUsbConfig,
            UsbConfigChecker.classify("samsung", "ptp,adb")
        )
    }

    @Test
    fun `case-insensitive samsung match`() {
        assertEquals(
            UsbConfigChecker.Advisory.RiskyUsbConfig,
            UsbConfigChecker.classify("SAMSUNG", "MTP,adb")
        )
    }

    @Test
    fun `samsung with sec_charging returns Safe`() {
        assertEquals(
            UsbConfigChecker.Advisory.Safe,
            UsbConfigChecker.classify("samsung", "sec_charging,adb")
        )
    }

    @Test
    fun `samsung with none returns Safe`() {
        assertEquals(
            UsbConfigChecker.Advisory.Safe,
            UsbConfigChecker.classify("samsung", "none")
        )
    }

    @Test
    fun `samsung with adb-only returns Safe`() {
        assertEquals(
            UsbConfigChecker.Advisory.Safe,
            UsbConfigChecker.classify("samsung", "adb")
        )
    }

    @Test
    fun `samsung with null config returns Unknown`() {
        assertEquals(
            UsbConfigChecker.Advisory.Unknown,
            UsbConfigChecker.classify("samsung", null)
        )
    }

    @Test
    fun `samsung with blank config returns Unknown`() {
        assertEquals(
            UsbConfigChecker.Advisory.Unknown,
            UsbConfigChecker.classify("samsung", "   ")
        )
    }

    @Test
    fun `samsung with empty config returns Unknown`() {
        assertEquals(
            UsbConfigChecker.Advisory.Unknown,
            UsbConfigChecker.classify("samsung", "")
        )
    }

    @Test
    fun `substring mtpX does not trigger warning`() {
        // "midi" alone shouldn't be treated as mtp; only an exact token match.
        assertEquals(
            UsbConfigChecker.Advisory.Safe,
            UsbConfigChecker.classify("samsung", "midi,adb")
        )
    }

    @Test
    fun `whitespace around tokens is tolerated`() {
        assertEquals(
            UsbConfigChecker.Advisory.RiskyUsbConfig,
            UsbConfigChecker.classify("samsung", " mtp , adb ")
        )
    }
}
