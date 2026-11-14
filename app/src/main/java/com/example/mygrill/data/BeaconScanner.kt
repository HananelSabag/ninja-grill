package com.example.mygrill.data

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.example.mygrill.core.Beacon
import com.example.mygrill.core.BeaconReading
import com.example.mygrill.core.ExternalReading
import com.example.mygrill.core.Slot
import com.example.mygrill.core.Tempwise
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** Why the Bluetooth side can't listen right now. */
enum class BleProblem { NO_PERMISSION, BLUETOOTH_OFF, NO_KEY, UNSUPPORTED }

/**
 * Passive scan for the grill's beacon. Never connects, so the official app (or milestone 2's
 * command link) keeps the grill's single BLE connection free, and the grill keeps advertising.
 */
class BeaconScanner(private val context: Context) {
    private val adapter get() = context.getSystemService(BluetoothManager::class.java)?.adapter

    fun problem(hasKey: Boolean): BleProblem? = when {
        adapter == null -> BleProblem.UNSUPPORTED
        !hasPermission() -> BleProblem.NO_PERMISSION
        adapter?.isEnabled != true -> BleProblem.BLUETOOTH_OFF
        !hasKey -> BleProblem.NO_KEY
        else -> null
    }

    fun hasPermission(): Boolean = permissions().all {
        context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }

    /** Readings from the grill at [address], or from the first grill heard when it's null. */
    @SuppressLint("MissingPermission") // checked by problem() before collecting
    fun readings(slot: Slot, address: String?): Flow<Pair<String, BeaconReading>> = callbackFlow {
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            close(IllegalStateException("Bluetooth unavailable"))
            return@callbackFlow
        }
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val from = result.device?.address ?: return
                if (address != null && !from.equals(address, ignoreCase = true)) return
                val mfg = result.scanRecord?.bytes?.let(Beacon::mfgFromScanRecord) ?: return
                runCatching { Beacon.read(mfg, slot, System.currentTimeMillis()) }
                    .onSuccess { trySend(from to it) }
            }

            override fun onScanFailed(errorCode: Int) {
                close(IllegalStateException("scan failed ($errorCode)"))
            }
        }
        val filter = ScanFilter.Builder()
            .setManufacturerData(Beacon.COMPANY_ID, ByteArray(0))
            .apply { if (address != null) setDeviceAddress(address.uppercase()) }
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner.startScan(listOf(filter), settings, callback)
        awaitClose { runCatching { scanner.stopScan(callback) } }
    }

    /**
     * The owner's Tempwise base, heard passively: it puts both probes' readings in every
     * advertisement, so nothing connects and the vendor's app can keep its own link.
     */
    @SuppressLint("MissingPermission") // checked by problem() before collecting
    fun tempwise(address: String): Flow<ExternalReading> = callbackFlow {
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            close(IllegalStateException("Bluetooth unavailable"))
            return@callbackFlow
        }
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val data = result.scanRecord?.manufacturerSpecificData ?: return
                for (i in 0 until data.size()) {
                    Tempwise.parseAdvert(data.keyAt(i), data.valueAt(i), address, System.currentTimeMillis())?.let { trySend(it) }
                }
            }

            override fun onScanFailed(errorCode: Int) {
                close(IllegalStateException("scan failed ($errorCode)"))
            }
        }
        val filter = ScanFilter.Builder().setDeviceAddress(address.uppercase()).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(listOf(filter), settings, callback)
        awaitClose { runCatching { scanner.stopScan(callback) } }
    }

    companion object {
        fun permissions(): Array<String> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
            }
    }
}
