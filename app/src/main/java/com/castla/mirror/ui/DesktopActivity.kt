package com.castla.mirror.ui

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*

/**
 * Launcher activity displayed on the Shizuku Virtual Display.
 * Shows categorized app grid: Navigation, Video/OTT, Music, Other.
 */
class DesktopActivity : Activity() {

    companion object {
        private const val TAG = "DesktopActivity"

        // --- Navigation ---
        private val NAV_PACKAGES = setOf(
            "com.skt.tmap",              // T Map
            "com.skt.skaf.l001mtm091",   // T Map (legacy)
            "com.locnall.KimGiSa",       // 김기사 (now Kakao Navi)
            "com.kakao.taxi",            // Kakao T
            "com.kakaonavi",             // Kakao Navi
            "com.nhn.android.nmap",      // Naver Map
            "com.nhn.android.navermap",  // Naver Map (alt)
            "com.google.android.apps.maps", // Google Maps
            "com.waze",                  // Waze
            "net.daum.android.map",      // Kakao Map
            "com.thinkware.iNaviC",      // iNavi
            "com.mnav.atlan",            // Atlan Navi
            "com.mappy.app",             // Mappy
            "com.here.app.maps",         // HERE Maps
            "com.mapbox.mapboxandroiddemo", // Mapbox
        )
        private val NAV_KEYWORDS = listOf("map", "navi", "navigation", "tmap", "waze")

        // --- Video / OTT ---
        private val VIDEO_PACKAGES = setOf(
            "com.google.android.youtube",           // YouTube
            "com.netflix.mediaclient",              // Netflix
            "com.disney.disneyplus",                // Disney+
            "com.disney.disneyplus.kr",             // Disney+ KR
            "com.wavve.player",                     // Wavve
            "net.cj.cjhv.gs.tving",                // Tving
            "com.coupang.play",                     // Coupang Play
            "com.amazon.avod.thirdpartyclient",     // Prime Video
            "com.amazon.avod",                      // Prime Video (alt)
            "tv.twitch.android.app",                // Twitch
            "com.frograms.watcha",                  // Watcha
            "kr.co.captv.pooq",                     // Pooq (now Wavve)
            "com.hbo.hbomax",                       // HBO Max
            "com.apple.atve.androidtv.appletv",     // Apple TV+
            "com.bbc.iplayer.android",              // BBC iPlayer
            "com.sbs.vod.sbsnow",                   // SBS
            "com.kbs.kbsn",                         // KBS
            "com.imbc.mbcvod",                      // MBC
            "com.vikinc.vikinchannel",              // JTBC
            "kr.co.nowcom.mobile.aladdin",          // Aladdin (영화)
            "com.dmp.hoyatv",                       // Hoya TV
        )
        private val VIDEO_KEYWORDS = listOf(
            "youtube", "netflix", "video", "movie", "ott", "tv", "drama",
            "stream", "vod", "player", "hulu", "disney", "tving", "wavve"
        )

        // Web URLs for DRM-restricted OTT apps
        private val OTT_WEB_URLS = mapOf(
            "com.google.android.youtube" to "https://m.youtube.com", // 모바일 유튜브 웹버전
            "com.netflix.mediaclient" to "https://www.netflix.com",
            "com.disney.disneyplus" to "https://www.disneyplus.com",
            "com.disney.disneyplus.kr" to "https://www.disneyplus.com",
            "com.wavve.player" to "https://m.wavve.com",
            "net.cj.cjhv.gs.tving" to "https://www.tving.com",
            "com.coupang.play" to "https://www.coupangplay.com",
            "com.frograms.watcha" to "https://watcha.com"
        )

        // --- Music ---
        private val MUSIC_PACKAGES = setOf(
            "com.spotify.music",                    // Spotify
            "com.google.android.apps.youtube.music",// YouTube Music
            "com.apple.android.music",              // Apple Music
            "com.iloen.melon",                      // Melon
            "com.kt.android.genie",                 // Genie
            "com.sktelecom.flomusic",               // FLO
            "com.naver.vibe",                       // Vibe (Naver)
            "com.soribada.android",                 // Soribada
            "com.soundcloud.android",               // SoundCloud
            "com.pandora.android",                  // Pandora
            "com.amazon.mp3",                       // Amazon Music
            "com.shazam.android",                   // Shazam
            "fm.castbox.audiobook.radio.podcast",   // Castbox
            "com.samsung.android.app.podcast",      // Samsung Podcasts
            "com.google.android.apps.podcasts",     // Google Podcasts
        )
        private val MUSIC_KEYWORDS = listOf(
            "music", "spotify", "audio", "radio", "podcast", "fm", "melon"
        )
    }

    private enum class AppCategory { NAVIGATION, VIDEO, MUSIC, OTHER }

    private var displayId = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        @Suppress("DEPRECATION")
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )

        displayId = display?.displayId ?: 0
        val allApps = getLaunchableApps()

        // Classify once, reuse everywhere
        val grouped = allApps.groupBy { classifyApp(it) }
        val navApps = grouped[AppCategory.NAVIGATION].orEmpty()
        val videoApps = grouped[AppCategory.VIDEO].orEmpty()
        val musicApps = grouped[AppCategory.MUSIC].orEmpty()
        val otherApps = grouped[AppCategory.OTHER].orEmpty()

        Log.i(TAG, "Display $displayId: ${navApps.size} nav, ${videoApps.size} video, ${musicApps.size} music, ${otherApps.size} other")

        val root = ScrollView(this).apply {
            setBackgroundColor(0xFF0F0F1A.toInt())
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(24, 24, 24, 24)
        }

        // Navigation (green)
        if (navApps.isNotEmpty()) {
            content.addView(createSectionHeader("Navigation", 0xFF4CAF50.toInt()))
            content.addView(createAppGrid(navApps))
        }

        // Video / OTT (deep orange)
        if (videoApps.isNotEmpty()) {
            content.addView(createSectionHeader("Video", 0xFFFF5722.toInt()))
            content.addView(createAppGrid(videoApps))
        }

        // Music (purple)
        if (musicApps.isNotEmpty()) {
            content.addView(createSectionHeader("Music", 0xFF9C27B0.toInt()))
            content.addView(createAppGrid(musicApps))
        }

        // Other apps (gray)
        if (otherApps.isNotEmpty()) {
            content.addView(createSectionHeader("Apps", 0xFF9E9E9E.toInt()))
            content.addView(createAppGrid(otherApps))
        }

        root.addView(content)
        setContentView(root)
    }

    private fun createSectionHeader(title: String, accentColor: Int): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16, 20, 16, 12)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )

            // Accent bar
            addView(View(context).apply {
                val bar = GradientDrawable().apply {
                    setColor(accentColor)
                    cornerRadius = 4f
                }
                background = bar
                layoutParams = LinearLayout.LayoutParams(6, 36).apply {
                    rightMargin = 16
                }
            })

            // Title
            addView(TextView(context).apply {
                text = title
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 18f
                typeface = Typeface.DEFAULT_BOLD
            })
        }
    }

    private fun createAppGrid(apps: List<AppInfo>): GridLayout {
        return GridLayout(this).apply {
            columnCount = 8
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 16
            }
            setPadding(8, 8, 8, 8)

            for (app in apps) {
                addView(createAppCell(app))
            }
        }
    }

    private fun createAppCell(app: AppInfo): View {
        val cell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(8, 12, 8, 12)
            layoutParams = GridLayout.LayoutParams().apply {
                width = 0
                height = ViewGroup.LayoutParams.WRAP_CONTENT
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1, 1f)
            }
            isClickable = true
            isFocusable = true
            val attrs = intArrayOf(android.R.attr.selectableItemBackground)
            val ta = context.obtainStyledAttributes(attrs)
            foreground = ta.getDrawable(0)
            ta.recycle()
        }

        // Icon container (with optional lock overlay)
        val iconContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(80, 80).apply {
                gravity = Gravity.CENTER
            }
        }

        val icon = ImageView(this).apply {
            setImageDrawable(app.icon)
            layoutParams = FrameLayout.LayoutParams(80, 80)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        iconContainer.addView(icon)

        // Web badge for DRM-restricted OTTs
        if (OTT_WEB_URLS.containsKey(app.packageName)) {
            val webBadge = TextView(this).apply {
                text = "WEB"
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 8f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                val bg = GradientDrawable().apply {
                    setColor(0xFF2196F3.toInt()) // Blue badge for Web
                    cornerRadius = 6f
                }
                background = bg
                setPadding(6, 2, 6, 2)
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.END
                ).apply {
                    topMargin = -4
                    rightMargin = -4
                }
            }
            iconContainer.addView(webBadge)
        }

        val label = TextView(this).apply {
            text = app.label
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 10f
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 4
            }
        }

        cell.addView(iconContainer)
        cell.addView(label)

        cell.setOnClickListener {
            launchApp(app.packageName, app.className, app.category)
        }

        return cell
    }

    private fun launchApp(packageName: String, className: String, category: AppCategory = AppCategory.OTHER) {
        val isVideo = category == AppCategory.VIDEO
        
        // Check if this is a DRM-restricted OTT app that should be launched in our WebBrowserActivity
        val webUrl = OTT_WEB_URLS[packageName]
        if (webUrl != null) {
            Log.i(TAG, "Launching DRM-restricted OTT app via WebBrowserActivity locally: $packageName -> $webUrl")
            // System 앱이 아닌 일반 앱은 권한 문제(SecurityException)로 인해
            // 가상 디스플레이에 직접 ActivityOptions.launchDisplayId 를 사용할 수 없습니다.
            // 따라서 1차적으로 시도했던 Shizuku(시스템 권한)를 통한 launchAppWithExtraOnDisplay 로 롤백하여 안전하게 띄웁니다.
            val componentName = "com.castla.mirror/com.castla.mirror.ui.WebBrowserActivity"
            com.castla.mirror.utils.AppLaunchBus.requestLaunch(componentName, null, isVideoApp = true, intentExtra = webUrl)
        } else {
            // Standard app launch via Shizuku
            com.castla.mirror.utils.AppLaunchBus.requestLaunch(packageName, className, isVideoApp = isVideo)
            Log.i(TAG, "Requested launch of $packageName (video=$isVideo) on display $displayId")
        }
    }

    /**
     * Cascading classification: Navigation > Video > Music > Other.
     * Each app falls into exactly one category (first match wins).
     */
    private fun classifyApp(app: AppInfo): AppCategory {
        val pkg = app.packageName.lowercase()
        val label = app.label.lowercase()

        // Navigation
        if (NAV_PACKAGES.any { pkg.startsWith(it) }) return AppCategory.NAVIGATION
        if (NAV_KEYWORDS.any { pkg.contains(it) }) return AppCategory.NAVIGATION
        if (label.contains("지도") || label.contains("내비") ||
            label.contains("navi") || label.contains("map")) return AppCategory.NAVIGATION

        // Video / OTT
        if (VIDEO_PACKAGES.any { pkg.startsWith(it) }) return AppCategory.VIDEO
        if (VIDEO_KEYWORDS.any { pkg.contains(it) }) return AppCategory.VIDEO
        if (label.contains("동영상") || label.contains("영화") ||
            label.contains("드라마")) return AppCategory.VIDEO

        // Music
        if (MUSIC_PACKAGES.any { pkg.startsWith(it) }) return AppCategory.MUSIC
        if (MUSIC_KEYWORDS.any { pkg.contains(it) }) return AppCategory.MUSIC
        if (label.contains("음악") || label.contains("뮤직") ||
            label.contains("라디오")) return AppCategory.MUSIC

        return AppCategory.OTHER
    }

    private fun getLaunchableApps(): List<AppInfo> {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolveInfos = pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)

        val realApps = resolveInfos
            .filter { it.activityInfo.packageName != packageName }
            .sortedBy { it.loadLabel(pm).toString().lowercase() }
            .map { ri ->
                val info = AppInfo(
                    packageName = ri.activityInfo.packageName,
                    className = ri.activityInfo.name,
                    label = ri.loadLabel(pm).toString(),
                    icon = ri.loadIcon(pm)
                )
                info.copy(category = classifyApp(info))
            }

        // Add demo apps on emulator for richer screenshots
        if (isEmulator()) {
            return (realApps + getDemoApps()).distinctBy { it.packageName }
        }
        return realApps
    }

    private fun isEmulator(): Boolean {
        return Build.FINGERPRINT.contains("generic") ||
                Build.FINGERPRINT.contains("emulator") ||
                Build.MODEL.contains("Emulator") ||
                Build.MODEL.contains("Android SDK") ||
                Build.PRODUCT.contains("sdk") ||
                Build.PRODUCT.contains("emulator")
    }

    private fun getDemoApps(): List<AppInfo> {
        // Read locale from system property (more reliable on emulator than Configuration)
        val sysProp = try {
            @Suppress("DEPRECATION")
            Class.forName("android.os.SystemProperties")
                .getMethod("get", String::class.java, String::class.java)
                .invoke(null, "persist.sys.locale", "") as String
        } catch (_: Exception) { "" }
        val locale = if (sysProp.isNotEmpty()) {
            sysProp.split("-", "_").firstOrNull() ?: "en"
        } else {
            resources.configuration.locales[0]?.language ?: "en"
        }

        // Navigation apps vary by locale
        val navApps = when (locale) {
            "ko" -> listOf(
                Triple("com.skt.tmap.ku", "T Map", "tmap.png"),
                Triple("com.locnall.KimGiSa", "Kakao Navi", "kakao_navi.png"),
                Triple("com.nhn.android.nmap", "Naver Map", "naver_map.png"),
            )
            "ja" -> listOf(
                Triple("com.waze", "Waze", "waze.png"),
                Triple("com.sygic.aura", "Sygic", "sygic.png"),
            )
            "zh" -> listOf(
                Triple("com.baidu.BaiduMap", "百度地图", "baidu_maps.png"),
                Triple("com.autonavi.minimap", "高德地图", "amap.png"),
            )
            else -> listOf(
                Triple("com.waze", "Waze", "waze.png"),
                Triple("com.sygic.aura", "Sygic", "sygic.png"),
            )
        }

        // Video & Music are universal
        val videoApps = listOf(
            Triple("com.netflix.mediaclient", "Netflix", "netflix.png"),
            Triple("com.disney.disneyplus", "Disney+", "disney_plus.png"),
            Triple("com.amazon.avod", "Prime Video", "prime_video.png"),
        )
        val musicApps = listOf(
            Triple("com.spotify.music", "Spotify", "spotify.png"),
            Triple("com.apple.android.music", "Apple Music", "apple_music.png"),
            Triple("com.iloen.melon", "Melon", "melon.png"),
        )

        val result = mutableListOf<AppInfo>()
        for ((pkg, label, iconFile) in navApps) {
            loadDemoIcon(iconFile)?.let { icon ->
                result.add(AppInfo(pkg, "$pkg.MainActivity", label, icon, AppCategory.NAVIGATION))
            }
        }
        for ((pkg, label, iconFile) in videoApps) {
            loadDemoIcon(iconFile)?.let { icon ->
                result.add(AppInfo(pkg, "$pkg.MainActivity", label, icon, AppCategory.VIDEO))
            }
        }
        for ((pkg, label, iconFile) in musicApps) {
            loadDemoIcon(iconFile)?.let { icon ->
                result.add(AppInfo(pkg, "$pkg.MainActivity", label, icon, AppCategory.MUSIC))
            }
        }
        return result
    }

    private fun loadDemoIcon(filename: String): Drawable? {
        return try {
            val stream = assets.open("demo_icons/$filename")
            val bitmap = BitmapFactory.decodeStream(stream)
            stream.close()
            if (bitmap != null) BitmapDrawable(resources, bitmap) else null
        } catch (e: Exception) {
            Log.w("DesktopActivity", "Failed to load demo icon: $filename", e)
            null
        }
    }

    private data class AppInfo(
        val packageName: String,
        val className: String,
        val label: String,
        val icon: Drawable,
        val category: AppCategory = AppCategory.OTHER
    )
}
