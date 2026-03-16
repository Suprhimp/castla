package com.castla.mirror.ui

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class QrCodeGeneratorTest {

    @Test
    fun `generates bitmap with correct dimensions`() {
        val bitmap = QrCodeGenerator.generate("https://example.com", 256)
        assertEquals(256, bitmap.width)
        assertEquals(256, bitmap.height)
    }

    @Test
    fun `generates bitmap with default size`() {
        val bitmap = QrCodeGenerator.generate("test")
        assertEquals(512, bitmap.width)
        assertEquals(512, bitmap.height)
    }

    @Test
    fun `generates different bitmaps for different URLs`() {
        val bmp1 = QrCodeGenerator.generate("http://192.168.1.1:9090/?token=abc", 64)
        val bmp2 = QrCodeGenerator.generate("http://192.168.1.1:9090/?token=xyz", 64)
        // At least one pixel should differ
        var differ = false
        outer@ for (x in 0 until 64) {
            for (y in 0 until 64) {
                if (bmp1.getPixel(x, y) != bmp2.getPixel(x, y)) {
                    differ = true
                    break@outer
                }
            }
        }
        assertTrue("QR codes for different tokens should differ", differ)
    }

    @Test
    fun `handles long URL content`() {
        val longUrl = "http://192.168.1.100:9090/?token=" + "a".repeat(100)
        val bitmap = QrCodeGenerator.generate(longUrl, 300)
        assertEquals(300, bitmap.width)
    }
}
