package com.castla.mirror.ui

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import com.castla.mirror.billing.LicenseManager

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

        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
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
            content.addView(createAppGrid(navApps, isPremium = false))
        }

        // Video / OTT (deep orange) — locked for free users
        val isLocked = !LicenseManager.isPremiumNow
        if (videoApps.isNotEmpty()) {
            content.addView(createSectionHeader("Video", 0xFFFF5722.toInt()))
            content.addView(createAppGrid(videoApps, isPremium = isLocked))
        }

        // Music (purple) — locked for free users
        if (musicApps.isNotEmpty()) {
            content.addView(createSectionHeader("Music", 0xFF9C27B0.toInt()))
            content.addView(createAppGrid(musicApps, isPremium = isLocked))
        }

        // Other apps (gray) — locked for free users
        if (otherApps.isNotEmpty()) {
            content.addView(createSectionHeader("Apps", 0xFF9E9E9E.toInt()))
            content.addView(createAppGrid(otherApps, isPremium = isLocked))
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

    private fun createAppGrid(apps: List<AppInfo>, isPremium: Boolean): GridLayout {
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
                addView(createAppCell(app, isPremium))
            }
        }
    }

    private fun createAppCell(app: AppInfo, isPremium: Boolean): View {
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
            if (isPremium) alpha = 0.4f
        }
        iconContainer.addView(icon)

        if (isPremium) {
            // Lock overlay
            val lock = TextView(this).apply {
                text = "PRO"
                setTextColor(0xFFFFD700.toInt())
                textSize = 9f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                val bg = GradientDrawable().apply {
                    setColor(0xCC000000.toInt())
                    cornerRadius = 8f
                }
                background = bg
                setPadding(8, 2, 8, 2)
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM or Gravity.END
                )
            }
            iconContainer.addView(lock)
        }

        val label = TextView(this).apply {
            text = app.label
            setTextColor(if (isPremium) 0xFF888888.toInt() else 0xFFFFFFFF.toInt())
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
            if (isPremium) {
                Toast.makeText(this, "Premium feature — upgrade to unlock", Toast.LENGTH_SHORT).show()
            } else {
                launchApp(app.packageName, app.className, app.category)
            }
        }

        return cell
    }

    private fun launchApp(packageName: String, className: String, category: AppCategory = AppCategory.OTHER) {
        val isVideo = category == AppCategory.VIDEO
        com.castla.mirror.utils.AppLaunchBus.requestLaunch(packageName, className, isVideoApp = isVideo)
        Log.i(TAG, "Requested launch of $packageName (video=$isVideo) on display $displayId")
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

        return resolveInfos
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
    }

    private data class AppInfo(
        val packageName: String,
        val className: String,
        val label: String,
        val icon: Drawable,
        val category: AppCategory = AppCategory.OTHER
    )
}
