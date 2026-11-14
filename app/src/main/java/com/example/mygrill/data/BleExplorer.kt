package com.example.mygrill.data

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.ArrayDeque
import java.util.UUID

data class FoundDevice(val address: String, val name: String?, val rssi: Int, val serviceUuids: List<String>, val companies: List<Int>)

/** What a connected device does, for the thermometer finder and the external-probe link. */
sealed interface GattEvent {
    data object Connected : GattEvent
    data class Services(val lines: List<String>) : GattEvent
    data class Notification(val uuid: String, val value: ByteArray) : GattEvent
    data class Closed(val reason: String) : GattEvent
}

/**
 * Generic BLE helpers for third-party devices (the Tempwise probes): an unfiltered scan, and a
 * connection that subscribes to every notify/indicate characteristic and streams what arrives.
 * Thermometers like these speak plainly, so nothing here needs keys.
 */
@SuppressLint("MissingPermission") // callers check BeaconScanner.hasPermission() first
class BleExplorer(private val context: Context) {
    private val adapter get() = context.getSystemService(BluetoothManager::class.java)?.adapter

    fun scanAll(): Flow<FoundDevice> = callbackFlow {
        val scanner = adapter?.bluetoothLeScanner ?: run { close(); return@callbackFlow }
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val record = result.scanRecord
                val companies = record?.manufacturerSpecificData?.let { m -> (0 until m.size()).map(m::keyAt) }.orEmpty()
                trySend(
                    FoundDevice(
                        address = result.device.address,
                        name = record?.deviceName ?: runCatching { result.device.name }.getOrNull(),
                        rssi = result.rssi,
                        serviceUuids = record?.serviceUuids?.map { it.uuid.toString() }.orEmpty(),
                        companies = companies,
                    ),
                )
            }

            override fun onScanFailed(errorCode: Int) { close(IllegalStateException("scan failed ($errorCode)")) }
        }
        scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
        awaitClose { runCatching { scanner.stopScan(callback) } }
    }

    /** Connect and stream every notification until the flow is cancelled or the link drops. */
    fun connect(address: String): Flow<GattEvent> = callbackFlow {
        val device = adapter?.getRemoteDevice(address.uppercase()) ?: run { close(); return@callbackFlow }
        val pending = ArrayDeque<BluetoothGattDescriptor>()

        fun writeNextDescriptor(gatt: BluetoothGatt) {
            val d = pending.pollFirst() ?: return
            val ch = d.characteristic
            val value = if (ch.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            } else {
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            }
            gatt.setCharacteristicNotification(ch, true)
            val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(d, value) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run { d.value = value; gatt.writeDescriptor(d) }
            }
            if (!ok) writeNextDescriptor(gatt)
        }

        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    trySend(GattEvent.Connected)
                    gatt.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    trySend(GattEvent.Closed("disconnected ($status)"))
                    close()
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                val lines = gatt.services.flatMap { s ->
                    listOf("SERVICE ${s.uuid}") + s.characteristics.map { c -> "   ${c.uuid}  ${props(c.properties)}" }
                }
                trySend(GattEvent.Services(lines))
                gatt.services.flatMap { it.characteristics }
                    .filter { it.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0 }
                    .mapNotNull { it.getDescriptor(CCCD) }
                    .forEach(pending::addLast)
                writeNextDescriptor(gatt)
            }

            override fun onDescriptorWrite(gatt: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) = writeNextDescriptor(gatt)

            override fun onCharacteristicChanged(gatt: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
                trySend(GattEvent.Notification(c.uuid.toString(), value))
            }

            @Deprecated("API < 33")
            @Suppress("DEPRECATION")
            override fun onCharacteristicChanged(gatt: BluetoothGatt, c: BluetoothGattCharacteristic) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) c.value?.let { trySend(GattEvent.Notification(c.uuid.toString(), it.copyOf())) }
            }
        }
        @Suppress("DEPRECATION")
        val gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        awaitClose {
            runCatching { gatt?.disconnect() }
            runCatching { gatt?.close() }
        }
    }

    private fun props(p: Int): String = buildList {
        if (p and BluetoothGattCharacteristic.PROPERTY_READ != 0) add("read")
        if (p and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) add("write")
        if (p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) add("write-nr")
        if (p and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) add("notify")
        if (p and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) add("indicate")
    }.joinToString(",")

    private companion object {
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
