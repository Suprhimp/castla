package com.castla.mirror.ott

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log

/**
 * Resolves the best available browser on the device for external OTT launch.
 *
 * Priority:
 * 1. System default browser
 * 2. Chrome
 * 3. Samsung Internet
 * 4. Firefox
 * 5. Edge
 * 6. null (caller should fall back to internal WebBrowserActivity)
 */
object BrowserResolver {

    private const val TAG = "BrowserResolver"

    data class BrowserTarget(
        val packageName: String,
        val activityName: String
    ) {
        val componentFlat: String get() = "$packageName/$activityName"
    }

    private val PREFERRED_BROWSERS = listOf(
        "com.android.chrome",
        "com.sec.android.app.sbrowser",
        "org.mozilla.firefox",
        "com.microsoft.emmx"
    )

    /**
     * Resolve the best browser to handle [url].
     * Returns null if no suitable browser is found (caller should use WebBrowserActivity fallback).
     */
    fun resolve(context: Context, url: String = "https://example.com"): BrowserTarget? {
        val pm = context.packageManager
        val viewIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url))

        // 1. Try system default browser
        val defaultActivity = pm.resolveActivity(viewIntent, PackageManager.MATCH_DEFAULT_ONLY)
        if (defaultActivity != null) {
            val pkg = defaultActivity.activityInfo.packageName
            // Skip the Android resolver chooser — it's not a real browser
            if (pkg != "android" && pkg != "com.android.internal.app") {
                Log.i(TAG, "Using system default browser: $pkg/${defaultActivity.activityInfo.name}")
                return BrowserTarget(pkg, defaultActivity.activityInfo.name)
            }
        }

        // 2. Walk the preferred list
        for (browserPkg in PREFERRED_BROWSERS) {
            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                `package` = browserPkg
            }
            val ri = pm.resolveActivity(browserIntent, PackageManager.MATCH_DEFAULT_ONLY)
            if (ri != null) {
                Log.i(TAG, "Using preferred browser: $browserPkg/${ri.activityInfo.name}")
                return BrowserTarget(browserPkg, ri.activityInfo.name)
            }
        }

        // 3. Try any browser that can handle ACTION_VIEW for https
        val allBrowsers = pm.queryIntentActivities(viewIntent, PackageManager.MATCH_ALL)
        for (ri in allBrowsers) {
            val pkg = ri.activityInfo.packageName
            if (pkg != "android" && pkg != "com.android.internal.app") {
                Log.i(TAG, "Using fallback browser: $pkg/${ri.activityInfo.name}")
                return BrowserTarget(pkg, ri.activityInfo.name)
            }
        }

        Log.w(TAG, "No external browser found on device")
        return null
    }
}
