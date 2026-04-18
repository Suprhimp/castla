package com.castla.mirror

import android.app.UiAutomation
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Environment
import android.os.LocaleList
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

/**
 * Play Store 스크린샷 자동 캡처 테스트
 *
 * 실행:
 *   ./gradlew connectedPlaystoreDebugAndroidTest \
 *       -Pandroid.testInstrumentationRunnerArguments.class=com.castla.mirror.PlayStoreScreenshotTest
 *
 * 또는 adb를 통해 직접:
 *   adb shell am instrument -w \
 *       -e class com.castla.mirror.PlayStoreScreenshotTest \
 *       com.castla.mirror.test/androidx.test.runner.AndroidJUnitRunner
 *
 * 스크린샷 저장 위치: /sdcard/Pictures/castla_screenshots/<locale>/<screen>.png
 * 가져오기: adb pull /sdcard/Pictures/castla_screenshots ./screenshots
 */
@RunWith(AndroidJUnit4::class)
class PlayStoreScreenshotTest {

    private lateinit var device: UiDevice
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    companion object {
        private val LOCALES = listOf(
            "en" to Locale("en", "US"),
            "ko" to Locale("ko", "KR"),
            "zh-CN" to Locale("zh", "CN"),
            "de" to Locale("de", "DE"),
            "no" to Locale("nb", "NO"),
            "fr" to Locale("fr", "FR"),
            "nl" to Locale("nl", "NL"),
            "ja" to Locale("ja", "JP"),
            "es" to Locale("es", "ES"),
        )

        private val SCREENS = listOf("main", "settings", "desktop")
        private const val SETTLE_MS = 3000L
    }

    @Before
    fun setUp() {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    }

    @Test
    fun captureAllScreensAllLocales() {
        for ((tag, locale) in LOCALES) {
            setLocale(locale)
            Thread.sleep(1000)

            for (screen in SCREENS) {
                launchScreen(screen)
                Thread.sleep(SETTLE_MS)
                takeScreenshot(tag, screen)
                closeApp()
                Thread.sleep(500)
            }
        }
    }

    // Individual locale tests — 특정 언어만 돌리고 싶을 때
    @Test fun screenshots_en() = captureLocale("en", Locale("en", "US"))
    @Test fun screenshots_ko() = captureLocale("ko", Locale("ko", "KR"))
    @Test fun screenshots_zhCN() = captureLocale("zh-CN", Locale("zh", "CN"))
    @Test fun screenshots_de() = captureLocale("de", Locale("de", "DE"))
    @Test fun screenshots_no() = captureLocale("no", Locale("nb", "NO"))
    @Test fun screenshots_fr() = captureLocale("fr", Locale("fr", "FR"))
    @Test fun screenshots_nl() = captureLocale("nl", Locale("nl", "NL"))
    @Test fun screenshots_ja() = captureLocale("ja", Locale("ja", "JP"))
    @Test fun screenshots_es() = captureLocale("es", Locale("es", "ES"))

    private fun captureLocale(tag: String, locale: Locale) {
        setLocale(locale)
        Thread.sleep(1000)
        for (screen in SCREENS) {
            launchScreen(screen)
            Thread.sleep(SETTLE_MS)
            takeScreenshot(tag, screen)
            closeApp()
            Thread.sleep(500)
        }
    }

    private fun setLocale(locale: Locale) {
        Locale.setDefault(locale)
        val config = Configuration(context.resources.configuration)
        config.setLocales(LocaleList(locale))
        @Suppress("DEPRECATION")
        context.resources.updateConfiguration(config, context.resources.displayMetrics)
    }

    private fun launchScreen(screen: String) {
        val pkg = "com.castla.mirror"
        when (screen) {
            "main" -> {
                val intent = context.packageManager.getLaunchIntentForPackage(pkg)
                    ?: Intent().setClassName(pkg, "com.castla.mirror.MainActivity")
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                context.startActivity(intent)
            }
            "settings" -> {
                val intent = Intent().setClassName(pkg, "com.castla.mirror.MainActivity")
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                intent.putExtra("open_settings", true)
                context.startActivity(intent)
            }
            // "desktop" screen removed — DesktopActivity has been deleted
        }
    }

    private fun takeScreenshot(locale: String, screen: String) {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            "castla_screenshots/$locale"
        )
        dir.mkdirs()

        val file = File(dir, "${screen}.png")
        device.takeScreenshot(file)

        if (file.exists()) {
            println("✓ Screenshot saved: ${file.absolutePath}")
        } else {
            println("✗ Failed to save: ${file.absolutePath}")
        }
    }

    private fun closeApp() {
        device.pressHome()
        Thread.sleep(500)
    }
}
