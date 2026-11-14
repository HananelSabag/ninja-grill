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
import android.content.Context
import android.os.Build
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

/** What the thermometer link hears: a live read (FF01) or the batteries (2A19). */
sealed interface TempwiseEvent {
    class Live(val value: ByteArray) : TempwiseEvent
    class Battery(val value: ByteArray) : TempwiseEvent
}

/**
 * A read-only link to the Tempwise base: subscribe to the live characteristic and read it about
 * once a second, the batteries once a minute. When the vendor's app already holds the base, Android
 * shares that link, so both apps hear it. Never writes a command; the flow ends when the link drops.
 */
@SuppressLint("MissingPermission") // callers check BeaconScanner.problem() first
class TempwiseLink(private val context: Context) {
    private val adapter get() = context.getSystemService(BluetoothManager::class.java)?.adapter

    fun connect(address: String): Flow<TempwiseEvent> = callbackFlow {
        val device = adapter?.getRemoteDevice(address.uppercase()) ?: run { close(); return@callbackFlow }
        var live: BluetoothGattCharacteristic? = null
        var battery: BluetoothGattCharacteristic? = null

        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) gatt.discoverServices()
                else if (newState == BluetoothProfile.STATE_DISCONNECTED) close()
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                val chars = gatt.services.flatMap { it.characteristics }
                live = chars.firstOrNull { it.uuid == LIVE } ?: run { close(); return }
                battery = chars.firstOrNull { it.uuid == BATTERY }
                val ch = live!!
                gatt.setCharacteristicNotification(ch, true)
                val cccd = ch.getDescriptor(CCCD) ?: return
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    @Suppress("DEPRECATION")
                    run { cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; gatt.writeDescriptor(cccd) }
                }
            }

            override fun onCharacteristicChanged(gatt: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) = heard(c, value)

            override fun onCharacteristicRead(gatt: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) heard(c, value)
            }

            @Deprecated("API < 33")
            @Suppress("DEPRECATION")
            override fun onCharacteristicChanged(gatt: BluetoothGatt, c: BluetoothGattCharacteristic) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) c.value?.let { heard(c, it.copyOf()) }
            }

            @Deprecated("API < 33")
            @Suppress("DEPRECATION")
            override fun onCharacteristicRead(gatt: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU && status == BluetoothGatt.GATT_SUCCESS) c.value?.let { heard(c, it.copyOf()) }
            }

            private fun heard(c: BluetoothGattCharacteristic, value: ByteArray) {
                when (c.uuid) {
                    LIVE -> trySend(TempwiseEvent.Live(value))
                    BATTERY -> trySend(TempwiseEvent.Battery(value))
                }
            }
        }
        @Suppress("DEPRECATION")
        val gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)

        // One operation at a time on a GATT link: a read that finds it busy just waits a turn.
        launch {
            var n = 0
            while (isActive) {
                delay(READ_EVERY_MS)
                val ch = (if (n % BATTERY_EVERY == 0) battery else null) ?: live ?: continue
                if (gatt?.readCharacteristic(ch) == true) n++
            }
        }
        awaitClose {
            runCatching { gatt?.disconnect() }
            runCatching { gatt?.close() }
        }
    }

    private companion object {
        const val READ_EVERY_MS = 1_000L
        const val BATTERY_EVERY = 60
        val LIVE: UUID = UUID.fromString("0000ff01-0000-1000-8000-00805f9b34fb")
        val BATTERY: UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
