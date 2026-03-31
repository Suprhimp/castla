package com.castla.mirror.ads

import android.app.Activity
import android.content.Context
import android.util.Log
import com.castla.mirror.billing.LicenseManager
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.RequestConfiguration
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages AdMob ads with a credit system:
 * - Rewarded ad: user proactively watches → earns 10 free mirroring sessions
 * - Interstitial ad: shown when user has 0 credits and starts mirroring
 * - Banner ad: shown at bottom of main screen
 * - PRO users bypass everything
 */
object AdManager {

    private const val TAG = "AdManager"
    private const val PREFS_NAME = "castla_ads"
    private const val KEY_FREE_CREDITS = "free_credits"
    private const val KEY_INITIAL_GRANTED = "initial_credits_granted"
    private const val CREDITS_PER_AD = 10
    private const val INITIAL_CREDITS = 10

    const val BANNER_AD_UNIT_ID = "ca-app-pub-8785907922784819/5841169630"
    private const val INTERSTITIAL_AD_UNIT_ID = "ca-app-pub-8785907922784819/5174324337"
    private const val REWARDED_AD_UNIT_ID = "ca-app-pub-8785907922784819/1290919643"

    private var interstitialAd: InterstitialAd? = null
    private var rewardedAd: RewardedAd? = null
    private var isInitialized = false
    private var isLoadingRewarded = false
    private var isLoadingInterstitial = false
    private lateinit var appContext: Context

    private val _freeCredits = MutableStateFlow(0)
    val freeCredits: StateFlow<Int> = _freeCredits.asStateFlow()

    /** Whether ads should be shown (false for PRO users) */
    val shouldShowAds: Boolean get() = !LicenseManager.isPremiumNow

    fun init(context: Context) {
        if (isInitialized) return
        appContext = context.applicationContext
        val prefs = getPrefs()
        if (!prefs.getBoolean(KEY_INITIAL_GRANTED, false)) {
            prefs.edit()
                .putInt(KEY_FREE_CREDITS, INITIAL_CREDITS)
                .putBoolean(KEY_INITIAL_GRANTED, true)
                .apply()
        }
        _freeCredits.value = prefs.getInt(KEY_FREE_CREDITS, 0)
        val testDevices = listOf("6FD954F2AF7C5480ADDABB61278A38A9")
        val config = RequestConfiguration.Builder()
            .setTestDeviceIds(testDevices)
            .build()
        MobileAds.setRequestConfiguration(config)
        MobileAds.initialize(context) {
            Log.i(TAG, "AdMob initialized")
            isInitialized = true
            loadInterstitial(context)
            loadRewarded(context)
        }
    }

    private fun getPrefs() =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun saveCredits(credits: Int) {
        _freeCredits.value = credits
        getPrefs().edit().putInt(KEY_FREE_CREDITS, credits).apply()
    }

    private fun addCredits() {
        saveCredits(minOf(_freeCredits.value + CREDITS_PER_AD, CREDITS_PER_AD))
    }

    private fun useCredit() {
        val current = _freeCredits.value
        if (current > 0) saveCredits(current - 1)
    }

    // ── Interstitial (forced, when 0 credits) ──────────────────────────

    fun loadInterstitial(context: Context) {
        if (!shouldShowAds || isLoadingInterstitial) return
        isLoadingInterstitial = true
        InterstitialAd.load(context, INTERSTITIAL_AD_UNIT_ID, AdRequest.Builder().build(),
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    Log.i(TAG, "Interstitial loaded")
                    interstitialAd = ad
                    isLoadingInterstitial = false
                }
                override fun onAdFailedToLoad(error: LoadAdError) {
                    Log.w(TAG, "Interstitial failed to load: ${error.message}")
                    interstitialAd = null
                    isLoadingInterstitial = false
                }
            })
    }

    /**
     * Called when user starts mirroring.
     * Credits > 0 → deduct one, proceed.
     * Credits = 0 → show interstitial, grant credits, proceed.
     */
    fun onMirroringStart(activity: Activity, onComplete: () -> Unit) {
        if (!shouldShowAds) {
            onComplete()
            return
        }

        if (_freeCredits.value > 0) {
            useCredit()
            Log.i(TAG, "Used 1 credit, remaining: ${_freeCredits.value}")
            onComplete()
            return
        }

        // No credits — show interstitial
        if (interstitialAd == null) {
            // Ad not loaded — don't block the user
            addCredits()
            useCredit()
            onComplete()
            return
        }

        interstitialAd?.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                Log.i(TAG, "Interstitial dismissed")
                interstitialAd = null
                loadInterstitial(activity)
                addCredits()
                useCredit()
                onComplete()
            }
            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                Log.w(TAG, "Interstitial failed to show: ${error.message}")
                interstitialAd = null
                loadInterstitial(activity)
                addCredits()
                useCredit()
                onComplete()
            }
        }
        interstitialAd?.show(activity)
    }

    // ── Rewarded (voluntary, "Watch Ad" button) ────────────────────────

    fun loadRewarded(context: Context) {
        if (!shouldShowAds || isLoadingRewarded) return
        isLoadingRewarded = true
        RewardedAd.load(context, REWARDED_AD_UNIT_ID, AdRequest.Builder().build(),
            object : RewardedAdLoadCallback() {
                override fun onAdLoaded(ad: RewardedAd) {
                    Log.i(TAG, "Rewarded ad loaded")
                    rewardedAd = ad
                    isLoadingRewarded = false
                }
                override fun onAdFailedToLoad(error: LoadAdError) {
                    Log.w(TAG, "Rewarded ad failed to load: ${error.message}")
                    rewardedAd = null
                    isLoadingRewarded = false
                }
            })
    }

    /** Whether a rewarded ad is ready to show */
    val isRewardedReady: Boolean get() = rewardedAd != null

    /**
     * User proactively watches a rewarded ad to earn 10 credits.
     * Credits are only granted when the reward callback fires (user watched to completion).
     */
    fun watchAdForCredits(activity: Activity, onComplete: () -> Unit) {
        if (rewardedAd == null) {
            Log.w(TAG, "Rewarded ad not ready")
            loadRewarded(activity)
            onComplete()
            return
        }

        rewardedAd?.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                Log.i(TAG, "Rewarded ad dismissed")
                rewardedAd = null
                loadRewarded(activity)
            }
            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                Log.w(TAG, "Rewarded ad failed to show: ${error.message}")
                rewardedAd = null
                loadRewarded(activity)
                onComplete()
            }
        }

        rewardedAd?.show(activity) { reward ->
            // Only grant credits when user earns the reward (watched to completion)
            Log.i(TAG, "Reward earned: ${reward.amount} ${reward.type}")
            addCredits()
            onComplete()
        }
    }
}
