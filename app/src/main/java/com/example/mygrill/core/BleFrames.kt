package com.example.mygrill.core

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * GATT frames on characteristics b001..b004 of service 0xFCBB.
 * Payload: session_id u32 | magic 0x019F6B4D | token u32 | field2 u32 | len u8 | body | padding,
 * XOR-masked by its first four bytes, then AES-256-CBC with the direction's slot.
 * Session: read b001, write CONNECT; the grill answers with a token that every command carries.
 */
object BleFrames {
    const val SERVICE = "0000fcbb-0000-1000-8000-00805f9b34fb"
    const val CHALLENGE = "0000b001-0000-1000-8000-00805f9b34fb"
    const val WRITE = "0000b002-0000-1000-8000-00805f9b34fb"
    const val NOTIFY = "0000b003-0000-1000-8000-00805f9b34fb"
    const val INDICATE = "0000b004-0000-1000-8000-00805f9b34fb"
    const val MAGIC = 0x019F6B4D

    /** Session request: type 0x10 and 22 zero bytes, sent with token 0. */
    val CONNECT: ByteArray = byteArrayOf(0x10) + ByteArray(22)

    class Frame(val token: Long, val body: ByteArray)

    /** One app->grill frame. [random] supplies the session id and padding (SecureRandom in the app). */
    fun encode(body: ByteArray, tx: Slot, token: Long, random: (Int) -> ByteArray): ByteArray {
        require(body.size in 1..255) { "body must be 1..255 bytes" }
        val head = ByteBuffer.allocate(17 + body.size).order(ByteOrder.LITTLE_ENDIAN)
            .put(random(4))
            .putInt(MAGIC)
            .putInt(token.toInt())
            .putInt(0)
            .put(body.size.toByte())
            .put(body)
            .array()
        val pad = (16 - head.size % 16) % 16
        return Aes.cbcEncrypt(tx, mask(head + random(pad)))
    }

    fun decode(frame: ByteArray, rx: Slot): Frame? {
        if (frame.size < 16 || frame.size % 16 != 0) return null
        val pt = mask(Aes.cbcDecrypt(rx, frame))
        val le = ByteBuffer.wrap(pt).order(ByteOrder.LITTLE_ENDIAN)
        if (le.getInt(4) != MAGIC) return null
        val len = pt[16].toInt() and 0xFF
        // A length that does not fit means a corrupt or forged frame, not a short read.
        if (len == 0 || 17 + len > pt.size) return null
        return Frame(le.getInt(8).toLong() and 0xFFFFFFFFL, pt.copyOfRange(17, 17 + len))
    }

    /** Self-inverse obfuscation keyed by the first four bytes. */
    internal fun mask(buf: ByteArray): ByteArray {
        val out = buf.copyOf()
        for (i in 4 until out.size) out[i] = (out[i].toInt() xor out[i and 3].toInt()).toByte()
        return out
    }

    /** Grill-info (0x0c): the Ayla DSN ("AC000W…") and the appliance serial ("SND…"). */
    fun parseInfo(body: ByteArray): GrillInfo? {
        if (body.isEmpty() || body[0] != 0x0C.toByte() || body.size < 18) return null
        fun ascii(from: Int, to: Int) = body.copyOfRange(from, minOf(to, body.size))
            .takeWhile { it != 0.toByte() }.toByteArray().toString(Charsets.US_ASCII).trim()
        val serialAt = body.toList().windowed(3).indexOfFirst { it == listOf('S'.code.toByte(), 'N'.code.toByte(), 'D'.code.toByte()) }
        val serial = if (serialAt >= 0) ascii(serialAt, serialAt + 20).filter { it.isLetterOrDigit() } else null
        return GrillInfo(dsn = ascii(2, 18).ifEmpty { null }, serial = serial?.ifEmpty { null })
    }

    /**
     * GrillState (0x0f): a fixed 138-byte struct. Offsets verified on our grill with a
     * dehydrate 40 °C / 600 s cook: mode @16, setpoint @18, seconds set @24, seconds left @28,
     * active @29. The ×20 value at @110 / @114 is NOT the cooking chamber: in the 2026-10-19 grill
     * test it read 30.0 °C at both ends of a preheat while the grill sensor (beacon and cloud) passed
     * 130 °C; most likely a circuit-board sensor (the cloud lists Temp_MainPCB / Temp_UIPCB). Kept
     * for diagnostics, never shown as the grill's temperature.
     */
    fun parseState(body: ByteArray): GattState? {
        if (body.size < 30 || body[0] != 0x0F.toByte()) return null
        val le = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN)
        fun u16(o: Int) = le.getShort(o).toInt() and 0xFFFF
        val chamberRaw = if (body.size >= 118) u16(110).takeIf { it != 0 } ?: u16(114) else 0
        return GattState(
            power = body[12].toInt() and 0xFF,
            mode = CookMode.fromByte(u16(16)),
            setpoint = u16(18),
            secondsSet = le.getInt(24),
            secondsLeft = u16(28),
            active = body[29] != 0.toByte(),
            chamberC = chamberRaw.takeIf { it != 0 }?.let { it / 20.0 },
        )
    }
}

data class GrillInfo(val dsn: String?, val serial: String?)

data class GattState(
    /** Panel state, same enum as the beacon's: 3 off, 4 idle, 7 cooking. */
    val power: Int,
    val mode: CookMode?,
    val setpoint: Int,
    val secondsSet: Int,
    val secondsLeft: Int,
    val active: Boolean,
    val chamberC: Double?,
)
