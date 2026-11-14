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
import com.example.mygrill.core.BleFrames
import com.example.mygrill.core.GattState
import com.example.mygrill.core.KeyBundle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.security.SecureRandom
import java.util.UUID

class GattException(message: String) : Exception(message)

/**
 * One short BLE session per command: connect, subscribe, read the challenge, CONNECT for a
 * session token, send the command, wait for the grill's state reply, disconnect. Holding the link
 * no longer than that keeps the grill advertising its beacon and leaves the single BLE slot free
 * for the official app.
 */
class GattCommandLink(private val context: Context) {
    private val random = SecureRandom()

    @SuppressLint("MissingPermission") // BLUETOOTH_CONNECT is part of the permission request
    suspend fun send(address: String, keys: KeyBundle, body: ByteArray): GattState? = withContext(Dispatchers.IO) {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: throw GattException("no Bluetooth")
        val device = adapter.getRemoteDevice(address.uppercase())
        val session = Session()
        @Suppress("DEPRECATION") // the replacement needs API 37; this one works on every version we support
        val gatt = device.connectGatt(context, false, session.callback, BluetoothDevice.TRANSPORT_LE)
            ?: throw GattException("connectGatt failed")
        try {
            withTimeout(SESSION_TIMEOUT_MS) { session.run(gatt, keys, body) }
        } finally {
            runCatching { gatt.disconnect() }
            runCatching { gatt.close() }
        }
    }

    private inner class Session {
        val ops = Channel<Pair<String, Int>>(Channel.UNLIMITED)
        val frames = Channel<ByteArray>(Channel.UNLIMITED)
        var token = 0L

        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                when {
                    newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS -> ops.trySend("connect" to status)
                    newState == BluetoothProfile.STATE_DISCONNECTED -> ops.trySend("disconnect" to status)
                    else -> ops.trySend("connect" to status)
                }
            }

            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) { ops.trySend("mtu" to status) }
            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) { ops.trySend("services" to status) }
            override fun onDescriptorWrite(gatt: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) { ops.trySend("descriptor" to status) }
            override fun onCharacteristicWrite(gatt: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) { ops.trySend("write" to status) }

            override fun onCharacteristicRead(gatt: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
                ops.trySend("read" to status)
            }

            @Deprecated("API < 33")
            @Suppress("DEPRECATION")
            override fun onCharacteristicRead(gatt: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) ops.trySend("read" to status)
            }

            override fun onCharacteristicChanged(gatt: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
                frames.trySend(value)
            }

            @Deprecated("API < 33")
            @Suppress("DEPRECATION")
            override fun onCharacteristicChanged(gatt: BluetoothGatt, c: BluetoothGattCharacteristic) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) c.value?.let { frames.trySend(it.copyOf()) }
            }
        }

        @SuppressLint("MissingPermission")
        suspend fun run(gatt: BluetoothGatt, keys: KeyBundle, body: ByteArray): GattState? {
            await("connect", "grill did not accept the connection (another app connected?)")
            gatt.requestMtu(247)
            awaitOrSkip("mtu")
            if (!gatt.discoverServices()) throw GattException("service discovery failed")
            await("services", "service discovery failed")
            val service = gatt.getService(UUID.fromString(BleFrames.SERVICE)) ?: throw GattException("not a Ninja grill (no FCBB service)")
            for ((uuid, enable) in listOf(
                BleFrames.INDICATE to BluetoothGattDescriptor.ENABLE_INDICATION_VALUE,
                BleFrames.NOTIFY to BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE,
            )) {
                val ch = service.getCharacteristic(UUID.fromString(uuid)) ?: continue
                gatt.setCharacteristicNotification(ch, true)
                val cccd = ch.getDescriptor(CCCD) ?: continue
                if (writeDescriptor(gatt, cccd, enable)) awaitOrSkip("descriptor")
            }
            val challenge = service.getCharacteristic(UUID.fromString(BleFrames.CHALLENGE)) ?: throw GattException("no b001")
            val write = service.getCharacteristic(UUID.fromString(BleFrames.WRITE)) ?: throw GattException("no b002")
            if (gatt.readCharacteristic(challenge)) awaitOrSkip("read")

            // Reading b001 usually makes the grill send its info frame with a token already;
            // otherwise ask for a session explicitly (a sleepy grill may miss the first one).
            collect(TOKEN_WAIT_MS, keys) { token != 0L }
            var attempts = 0
            while (token == 0L && attempts++ < 3) {
                writeFrame(gatt, write, BleFrames.encode(BleFrames.CONNECT, keys.tx, 0, ::randomBytes))
                collect(TOKEN_WAIT_MS, keys) { token != 0L }
            }
            if (token == 0L) throw GattException("no session token from the grill")

            writeFrame(gatt, write, BleFrames.encode(body, keys.tx, token, ::randomBytes))
            var state: GattState? = null
            collect(REPLY_WAIT_MS, keys) { frame ->
                state = BleFrames.parseState(frame) ?: state
                state != null
            }
            return state
        }

        /** Decode incoming frames for up to [ms], remembering the newest token, until [done] says so. */
        private suspend fun collect(ms: Long, keys: KeyBundle, done: (ByteArray) -> Boolean) {
            withTimeoutOrNull(ms) {
                while (true) {
                    val decoded = BleFrames.decode(frames.receive(), keys.rx) ?: continue
                    if (decoded.token != 0L) token = decoded.token
                    if (done(decoded.body)) break
                }
            }
        }

        @SuppressLint("MissingPermission")
        @Suppress("DEPRECATION")
        private suspend fun writeFrame(gatt: BluetoothGatt, ch: BluetoothGattCharacteristic, frame: ByteArray) {
            val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(ch, frame, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
            } else {
                ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                ch.value = frame
                gatt.writeCharacteristic(ch)
            }
            if (!ok) throw GattException("write rejected")
            await("write", "write failed")
        }

        @SuppressLint("MissingPermission")
        @Suppress("DEPRECATION")
        private fun writeDescriptor(gatt: BluetoothGatt, d: BluetoothGattDescriptor, value: ByteArray): Boolean =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(d, value) == BluetoothStatusCodes.SUCCESS
            } else {
                d.value = value
                gatt.writeDescriptor(d)
            }

        private suspend fun await(kind: String, error: String) {
            val (got, status) = withTimeoutOrNull(OP_TIMEOUT_MS) { nextOp(kind) } ?: throw GattException("$error (timeout)")
            if (got == "disconnect") throw GattException("grill disconnected ($status)")
            if (status != BluetoothGatt.GATT_SUCCESS) throw GattException("$error ($status)")
        }

        /** For steps the grill may skip answering (MTU on some stacks): don't fail the session over them. */
        private suspend fun awaitOrSkip(kind: String) {
            val result = withTimeoutOrNull(OP_TIMEOUT_MS) { nextOp(kind) }
            if (result?.first == "disconnect") throw GattException("grill disconnected (${result.second})")
        }

        private suspend fun nextOp(kind: String): Pair<String, Int> {
            while (true) {
                val op = ops.receive()
                if (op.first == kind || op.first == "disconnect") return op
            }
        }
    }

    private fun randomBytes(n: Int) = ByteArray(n).also(random::nextBytes)

    private companion object {
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        const val OP_TIMEOUT_MS = 6_000L
        const val TOKEN_WAIT_MS = 3_000L
        const val REPLY_WAIT_MS = 6_000L
        const val SESSION_TIMEOUT_MS = 40_000L
    }
}
