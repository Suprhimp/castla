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

/**
 * Launcher activity displayed on the Shizuku Virtual Display.
 * Shows categorized app grid: Navigation apps (free) at top,
 * other apps (premium) below.
 */
class DesktopActivity : Activity() {

    companion object {
        private const val TAG = "DesktopActivity"

        // Known navigation/map app package prefixes and names
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

        // Keywords in package name that indicate navigation
        private val NAV_KEYWORDS = listOf("map", "navi", "navigation", "tmap", "waze")
    }

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

        val navApps = allApps.filter { isNavigationApp(it) }
        val otherApps = allApps.filter { !isNavigationApp(it) }

        Log.i(TAG, "Display $displayId: ${navApps.size} nav apps, ${otherApps.size} other apps")

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

        // Navigation section
        if (navApps.isNotEmpty()) {
            content.addView(createSectionHeader("Navigation", 0xFF4CAF50.toInt()))
            content.addView(createAppGrid(navApps, isPremium = false))
        }

        // Other apps section
        if (otherApps.isNotEmpty()) {
            content.addView(createSectionHeader("Apps", 0xFF9E9E9E.toInt()))
            content.addView(createAppGrid(otherApps, isPremium = false)) // TODO: set isPremium=true for paid gating
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
                launchApp(app.packageName, app.className)
            }
        }

        return cell
    }

    private fun launchApp(packageName: String, className: String) {
        // Send broadcast to MirrorForegroundService which has Shizuku access (uid 2000)
        val intent = Intent("com.castla.mirror.LAUNCH_ON_VD").apply {
            setPackage("com.castla.mirror")
            putExtra("package", packageName)
            putExtra("class", className)
            putExtra("displayId", displayId)
        }
        sendBroadcast(intent)
        Log.i(TAG, "Requested launch of $packageName/$className on display $displayId")
    }

    private fun isNavigationApp(app: AppInfo): Boolean {
        val pkg = app.packageName.lowercase()
        if (NAV_PACKAGES.any { pkg.startsWith(it) }) return true
        if (NAV_KEYWORDS.any { pkg.contains(it) }) return true
        val labelLower = app.label.lowercase()
        if (labelLower.contains("지도") || labelLower.contains("내비") ||
            labelLower.contains("navi") || labelLower.contains("map")) return true
        return false
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
                AppInfo(
                    packageName = ri.activityInfo.packageName,
                    className = ri.activityInfo.name,
                    label = ri.loadLabel(pm).toString(),
                    icon = ri.loadIcon(pm)
                )
            }
    }

    private data class AppInfo(
        val packageName: String,
        val className: String,
        val label: String,
        val icon: Drawable
    )
}
