package com.castla.mirror.service

import android.companion.CompanionDeviceService
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi

/**
 * Receives callbacks from the OS when a CDM-associated Tesla Bluetooth device
 * appears or disappears. This allows the app to be woken from the background
 * without requiring background location or NotificationListenerService.
 *
 * The API 33+ overloads (AssociationInfo parameter) are isolated in a nested
 * helper so that the AssociationInfo class reference does not cause a
 * ClassNotFoundException on API <33 when the service is instantiated.
 */
class TeslaCompanionService : CompanionDeviceService() {

    companion object {
        private const val TAG = "TeslaCompanion"

        /** Action used by the notification's "Start" button to launch mirroring */
        const val ACTION_START_MIRRORING = "com.castla.mirror.ACTION_START_MIRRORING_FROM_CDM"
    }

    override fun onCreate() {
        super.onCreate()
        TeslaDetectNotifier.ensureChannel(this)
    }

    // --- API 31 (S) callbacks: String MAC address ---

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onDeviceAppeared(address: String) {
        Log.i(TAG, "Tesla device appeared: $address")
        handleDeviceAppeared()
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onDeviceDisappeared(address: String) {
        Log.i(TAG, "Tesla device disappeared: $address")
        TeslaDetectNotifier.dismiss(this)
    }

    // --- API 33+ (TIRAMISU) callbacks: AssociationInfo ---
    // Isolated behind @RequiresApi so the classloader does not try to resolve
    // android.companion.AssociationInfo on older devices.

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onDeviceAppeared(associationInfo: android.companion.AssociationInfo) {
        Log.i(TAG, "Tesla device appeared: id=${associationInfo.id}")
        handleDeviceAppeared()
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onDeviceDisappeared(associationInfo: android.companion.AssociationInfo) {
        Log.i(TAG, "Tesla device disappeared: id=${associationInfo.id}")
        TeslaDetectNotifier.dismiss(this)
    }

    // --- Shared logic ---

    private fun handleDeviceAppeared() {
        TeslaDetectNotifier.showTeslaDetectedNotification(this)
    }
}
