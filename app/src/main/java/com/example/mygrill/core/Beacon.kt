package com.example.mygrill.core

/**
 * The grill's encrypted BLE advertisement ("beacon"). It carries the live state the GATT link
 * does not (probes, lid, phase, prompt) and is broadcast only while nothing is connected.
 *
 * The main advertisement holds 20 bytes of manufacturer data for company 0x0C4F; the scan
 * response holds another 23 we don't need. Cipher: AES-ECB used as CBC with ciphertext stealing,
 * keyed by the bundle's beacon slot. Fields are bit-packed LSB-first.
 * Layout from coxtor/ninja-woodfire-integration, checked against our OG901 on 2026-10-18.
 */
object Beacon {
    const val COMPANY_ID = 0x0C4F
    const val MFG_LEN = 20

    private val PROBE0 = 78 to 10       // °C, 0 = not plugged in
    private val PROBE1 = 88 to 10
    private val LID = 48 to 1           // 1 = open
    private val PHASE = 25 to 5
    private val PROMPT = 98 to 6
    private val GRILL_F = 68 to 10      // grill sensor; °F by all evidence so far (cloud uses °F too)
    /**
     * Panel state, the same enum the GATT state frame carries at byte 12: 3 = panel off (the grill
     * switches itself off after ~10 idle minutes but keeps advertising), 4 = on and idle, 7 = cooking.
     * Pinned on our grill on 2026-10-19: 3 before a BLE stop, 4 right after it woke the display.
     */
    private val POWER = 16 to 3
    const val POWER_OFF = 3

    /** The first company-0x0C4F manufacturer AD in a raw scan record: always the main advertisement's. */
    fun mfgFromScanRecord(record: ByteArray): ByteArray? {
        var i = 0
        while (i + 1 < record.size) {
            val len = record[i].toInt() and 0xFF
            if (len == 0 || i + 1 + len > record.size) break
            val type = record[i + 1].toInt() and 0xFF
            val company = if (len >= 3) (record[i + 2].toInt() and 0xFF) or ((record[i + 3].toInt() and 0xFF) shl 8) else -1
            if (type == 0xFF && company == COMPANY_ID) {
                val data = record.copyOfRange(i + 4, i + 1 + len)
                return if (data.size >= MFG_LEN) data.copyOf(MFG_LEN) else null
            }
            i += 1 + len
        }
        return null
    }

    fun decrypt(mfg: ByteArray, slot: Slot): ByteArray {
        require(mfg.size in 16..31) { "beacon segment must be 16..31 bytes" }
        if (mfg.size == 16) return xor(Aes.ecbDecrypt(slot.key, mfg), slot.iv)
        val tail = mfg.size - 16
        val d0 = xor(Aes.ecbDecrypt(slot.key, mfg.copyOf(16)), slot.iv)
        val last = xor(Aes.ecbDecrypt(slot.key, d0.copyOfRange(tail, 16) + mfg.copyOfRange(16, mfg.size)), slot.iv)
        return d0.copyOf(tail) + last
    }

    fun parse(plain: ByteArray, atMillis: Long): BeaconReading = BeaconReading(
        atMillis = atMillis,
        phase = when (bits(plain, PHASE)) {
            0 -> if (bits(plain, POWER) == POWER_OFF) CookPhase.OFF else CookPhase.IDLE
            2 -> CookPhase.IGNITION
            3 -> CookPhase.PREHEAT
            4 -> CookPhase.COOKING
            else -> CookPhase.UNKNOWN
        },
        prompt = when (bits(plain, PROMPT)) {
            1 -> Prompt.ADD_FOOD
            4 -> Prompt.FLIP_FOOD
            6 -> Prompt.DONE
            7 -> Prompt.GET_FOOD
            else -> null
        },
        lidOpen = bits(plain, LID) == 1,
        probesC = listOf(probe(bits(plain, PROBE0)), probe(bits(plain, PROBE1))),
        grillC = bits(plain, GRILL_F).takeIf { it > 0 }?.let(::fahrenheitToC),
    )

    fun read(mfg: ByteArray, slot: Slot, atMillis: Long): BeaconReading = parse(decrypt(mfg, slot), atMillis)

    /** A 10-bit probe field at its maximum reads like an open circuit: treat anything absurd as unplugged. */
    private fun probe(raw: Int): Double? = raw.takeIf { it in 1..300 }?.toDouble()

    internal fun bits(buf: ByteArray, field: Pair<Int, Int>): Int {
        val (off, width) = field
        var value = 0
        for (i in 0 until width) {
            val pos = off + i
            if (pos shr 3 >= buf.size) break
            value = value or (((buf[pos shr 3].toInt() shr (pos and 7)) and 1) shl i)
        }
        return value
    }

    private fun xor(a: ByteArray, b: ByteArray) = ByteArray(a.size) { (a[it].toInt() xor b[it].toInt()).toByte() }
}

fun fahrenheitToC(f: Number): Double = Math.round((f.toDouble() - 32) * 5 / 9 * 10) / 10.0
