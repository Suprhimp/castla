package com.castla.mirror.ott

/**
 * Single source of truth for OTT app metadata.
 * Replaces duplicated URL maps in DesktopActivity, MirrorServer, and MirrorForegroundService.
 */
object OttCatalog {

    data class OttTarget(
        val packageName: String,
        val webUrl: String,
        val serviceName: String = "",
        val allowEmbeddedFallback: Boolean = true
    )

    private val targets = listOf(
        OttTarget("com.google.android.youtube", "https://m.youtube.com", "YouTube"),
        OttTarget("com.netflix.mediaclient", "https://www.netflix.com", "Netflix"),
        OttTarget("com.disney.disneyplus", "https://www.disneyplus.com", "Disney+"),
        OttTarget("com.disney.disneyplus.kr", "https://www.disneyplus.com", "Disney+ KR"),
        OttTarget("com.wavve.player", "https://m.wavve.com", "Wavve"),
        OttTarget("net.cj.cjhv.gs.tving", "https://www.tving.com", "Tving"),
        OttTarget("com.coupang.play", "https://www.coupangplay.com", "Coupang Play"),
        OttTarget("com.frograms.watcha", "https://watcha.com", "Watcha")
    )

    private val byPackage: Map<String, OttTarget> = targets.associateBy { it.packageName }

    /** Look up OTT target by package name. Returns null for non-OTT apps. */
    fun resolve(packageName: String): OttTarget? = byPackage[packageName]

    /** Get the web URL for a package, or null if not an OTT app. */
    fun webUrlFor(packageName: String): String? = byPackage[packageName]?.webUrl

    /** Check if a package is a known OTT app. */
    fun isOtt(packageName: String): Boolean = byPackage.containsKey(packageName)

    /** All known OTT package names. */
    val allPackages: Set<String> get() = byPackage.keys
}
