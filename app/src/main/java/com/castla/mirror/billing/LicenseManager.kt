package com.castla.mirror.billing

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Singleton managing premium license state.
 * Uses StateFlow for Compose reactivity + SharedPreferences as offline cache.
 * Same-process singleton — accessible from DesktopActivity (VD), MirrorServer, etc.
 */
object LicenseManager {

    private const val PREFS_NAME = "castla_license"
    private const val KEY_IS_PREMIUM = "is_premium"

    // Temporarily default to true
    private val _isPremium = MutableStateFlow(true)
    val isPremium: StateFlow<Boolean> = _isPremium.asStateFlow()

    /** Non-reactive read for non-Compose code (DesktopActivity, MirrorServer, etc.) */
    val isPremiumNow: Boolean get() = _isPremium.value

    fun init(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        // Temporarily default to true
        _isPremium.value = prefs.getBoolean(KEY_IS_PREMIUM, true)
    }

    fun setPremium(value: Boolean, context: Context) {
        _isPremium.value = value
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_IS_PREMIUM, value)
            .apply()
    }
}
