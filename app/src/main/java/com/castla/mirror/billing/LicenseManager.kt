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
    val isPremiumNow: Boolean get() = true

    fun init(context: Context) {
        // Force premium state to true
        _isPremium.value = true
    }

    fun setPremium(value: Boolean, context: Context) {
        // Ignore the input value and force true
        _isPremium.value = true
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_IS_PREMIUM, true)
            .apply()
    }
}
