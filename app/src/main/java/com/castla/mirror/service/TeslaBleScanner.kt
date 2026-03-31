package com.castla.mirror.service

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log

/**
 * BLE scanner that detects Tesla vehicles by their known BLE advertisement UUIDs.
 * Acts as a fallback/complement to CompanionDeviceManager detection.
 *
 * Tesla vehicles broadcast two identifiable BLE signals:
 * - VCSEC Service UUID (Vehicle Controller Secondary — phone key communication)
 * - iBeacon with Tesla's UUID (via Apple manufacturer data, company ID 0x004C)
 */
class TeslaBleScanner(private val context: Context) {

    companion object {
        private const val TAG = "TeslaBleScanner"

        /** Tesla VCSEC (Vehicle Controller Secondary) BLE service UUID */
        val TESLA_VCSEC_UUID: ParcelUuid =
            ParcelUuid.fromString("00000211-b2d1-43f0-9b88-960cebf8b91e")

        /** Tesla iBeacon UUID */
        private const val TESLA_IBEACON_UUID = "74278BDA-B644-4520-8F0C-720EAF059935"

        /** Apple company ID for iBeacon manufacturer data */
        private const val APPLE_COMPANY_ID = 0x004C

        /** Restart scan every 25 min to avoid Android's ~30 min BLE scan timeout */
        private const val SCAN_RESTART_INTERVAL_MS = 25 * 60 * 1000L

        /** Don't re-notify for 60 seconds after a detection */
        private const val COOLDOWN_AFTER_DETECTION_MS = 60 * 1000L
    }

    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var isScanning = false
    private var lastDetectionTimeMs = 0L
    private val handler = Handler(Looper.getMainLooper())
    private var onTeslaDetected: (() -> Unit)? = null

    fun start(onDetected: () -> Unit) {
        onTeslaDetected = onDetected

        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = bluetoothManager?.adapter
        if (adapter == null || !adapter.isEnabled) {
            Log.w(TAG, "Bluetooth not available or disabled")
            return
        }

        bluetoothLeScanner = adapter.bluetoothLeScanner
        if (bluetoothLeScanner == null) {
            Log.w(TAG, "BluetoothLeScanner not available")
            return
        }

        startScanInternal()
    }

    fun stop() {
        handler.removeCallbacks(restartRunnable)
        stopScanInternal()
        onTeslaDetected = null
        Log.i(TAG, "BLE scanner stopped")
    }

    private fun startScanInternal() {
        if (isScanning) return

        try {
            bluetoothLeScanner?.startScan(buildScanFilters(), buildScanSettings(), scanCallback)
            isScanning = true
            handler.postDelayed(restartRunnable, SCAN_RESTART_INTERVAL_MS)
            Log.i(TAG, "BLE scan started")
        } catch (e: SecurityException) {
            Log.e(TAG, "BLE scan permission denied", e)
        } catch (e: Exception) {
            Log.e(TAG, "BLE scan failed to start", e)
        }
    }

    private fun stopScanInternal() {
        if (!isScanning) return

        try {
            bluetoothLeScanner?.stopScan(scanCallback)
        } catch (e: SecurityException) {
            Log.w(TAG, "SecurityException stopping BLE scan", e)
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping BLE scan", e)
        }
        isScanning = false
    }

    private fun buildScanFilters(): List<ScanFilter> {
        val filters = mutableListOf<ScanFilter>()

        // Filter 1: Tesla VCSEC service UUID
        filters.add(
            ScanFilter.Builder()
                .setServiceUuid(TESLA_VCSEC_UUID)
                .build()
        )

        // Filter 2: Tesla iBeacon via Apple manufacturer data
        // iBeacon format: [0x02, 0x15, <16-byte UUID>]
        // Total: 18 bytes of manufacturer data after company ID
        val iBeaconData = buildTeslaIBeaconData()
        val iBeaconMask = buildIBeaconMask()
        filters.add(
            ScanFilter.Builder()
                .setManufacturerData(APPLE_COMPANY_ID, iBeaconData, iBeaconMask)
                .build()
        )

        return filters
    }

    private fun buildTeslaIBeaconData(): ByteArray {
        // iBeacon prefix: type=0x02, length=0x15 (21 bytes follow)
        // Then 16-byte UUID
        val data = ByteArray(18)
        data[0] = 0x02  // iBeacon type
        data[1] = 0x15  // length (21 bytes: 16 UUID + 2 major + 2 minor + 1 TX)

        // Parse Tesla iBeacon UUID into bytes
        val uuidHex = TESLA_IBEACON_UUID.replace("-", "")
        for (i in 0 until 16) {
            data[2 + i] = uuidHex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return data
    }

    private fun buildIBeaconMask(): ByteArray {
        // Match type, length, and all 16 UUID bytes; ignore major/minor/TX
        val mask = ByteArray(18)
        mask[0] = 0xFF.toByte()  // match type
        mask[1] = 0xFF.toByte()  // match length
        for (i in 2 until 18) {
            mask[i] = 0xFF.toByte()  // match UUID bytes
        }
        return mask
    }

    private fun buildScanSettings(): ScanSettings {
        return ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            .setMatchMode(ScanSettings.MATCH_MODE_STICKY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_FIRST_MATCH)
            .build()
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            if (result == null) return

            val now = System.currentTimeMillis()
            if (now - lastDetectionTimeMs < COOLDOWN_AFTER_DETECTION_MS) {
                return // cooldown active
            }

            lastDetectionTimeMs = now
            Log.i(TAG, "Tesla BLE detected! device=${result.device?.address}, rssi=${result.rssi}")
            onTeslaDetected?.invoke()
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "BLE scan failed with error code: $errorCode")
            isScanning = false
        }
    }

    /** Periodic restart to prevent Android from killing long-running scans */
    private val restartRunnable = Runnable {
        Log.i(TAG, "Restarting BLE scan (periodic refresh)")
        stopScanInternal()
        startScanInternal()
    }
}
