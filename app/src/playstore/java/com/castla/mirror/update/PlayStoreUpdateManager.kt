package com.castla.mirror.update

import android.util.Log
import androidx.activity.ComponentActivity
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.UpdateAvailability

/**
 * Play Store flavor: uses Google Play In-App Updates API with IMMEDIATE mode.
 * IMMEDIATE mode blocks the app entirely until the update is installed.
 */
class PlayStoreUpdateManager : UpdateManager {

    companion object {
        private const val TAG = "PlayStoreUpdate"
        private const val UPDATE_REQUEST_CODE = 9001
    }

    private val appUpdateManager by lazy { AppUpdateManagerFactory.create(activity!!) }
    private var activity: ComponentActivity? = null

    override fun checkForUpdate(activity: ComponentActivity) {
        this.activity = activity
        val appUpdateInfoTask = appUpdateManager.appUpdateInfo

        appUpdateInfoTask.addOnSuccessListener { appUpdateInfo ->
            Log.i(TAG, "Update availability: ${appUpdateInfo.updateAvailability()}")

            if (appUpdateInfo.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE
                && appUpdateInfo.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)
            ) {
                Log.i(TAG, "Immediate update available, launching update flow")
                appUpdateManager.startUpdateFlowForResult(
                    appUpdateInfo,
                    activity,
                    AppUpdateOptions.newBuilder(AppUpdateType.IMMEDIATE).build(),
                    UPDATE_REQUEST_CODE
                )
            }
        }

        appUpdateInfoTask.addOnFailureListener { e ->
            Log.w(TAG, "Update check failed", e)
        }
    }

    override fun onResume(activity: ComponentActivity) {
        this.activity = activity
        // If an IMMEDIATE update was interrupted, re-launch it
        appUpdateManager.appUpdateInfo.addOnSuccessListener { appUpdateInfo ->
            if (appUpdateInfo.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS) {
                Log.i(TAG, "Resuming stalled immediate update")
                appUpdateManager.startUpdateFlowForResult(
                    appUpdateInfo,
                    activity,
                    AppUpdateOptions.newBuilder(AppUpdateType.IMMEDIATE).build(),
                    UPDATE_REQUEST_CODE
                )
            }
        }
    }

    override fun destroy() {
        activity = null
    }
}
