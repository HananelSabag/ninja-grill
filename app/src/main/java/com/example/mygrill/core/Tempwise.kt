package com.example.mygrill.core

/**
 * One reading from the external wireless thermometer: a Tempwise TNT-2BW base and its two
 * probes. Index 0 and 1 are the base's probe slots, not colours (the owner names them).
 */
data class ExternalReading(
    val atMillis: Long,
    /** Each probe's tip (the meat), °C; null while the probe sits in the base or has nothing. */
    val probesC: List<Double?>,
    /** Each probe's handle (the air around the meat), whole °C. */
    val handlesC: List<Int?>,
    val docked: List<Boolean>,
    val probeBatteryPct: List<Int?>,
    val baseBatteryPct: Int?,
)

/**
 * Tempwise TNT-2BW (an INKBIRD-family two-probe base, Telink chip). It broadcasts everything in
 * its advertisement, in the clear: no pairing, no handshake, no account. Read passively, like the
 * grill's beacon, so the vendor app can keep its connection and ours never drops.
 *
 * Manufacturer data, with the 2-byte "company id" put back in front (little-endian): 8D B8 ED 69,
 * the base's MAC reversed (6 bytes), then 12 bytes (pinned 2026-11-03 by warming each tip by hand):
 *   0-1  probe 1 tip, int16 LE, tenths of °C (0x7FFE = in the base / no reading)
 *   2-3  probe 1 second sensor (same encoding; not shown)
 *   4    probe 1 handle, whole °C
 *   5    probe 1 status: bit 7 = in the base, bits 0-6 = battery %
 *   6-7  probe 2 tip
 *   8    probe 2 handle
 *   9    probe 2 status
 *   10   which probes sit in their slots (bit mask)
 *   11   base battery %
 *
 * The base stops advertising while a phone holds its link (the vendor's app does whenever it is
 * open), so the app also reads the live characteristic FF01 over that link, about once a second.
 * Its 10 bytes: probe 1 tip int16 @0, second sensor @2, handle @4; probe 2 tip @5, handle @7;
 * two bytes not yet known. A read is fresh every time; nothing is ever written but the
 * notification switch.
 */
object Tempwise {
    private val NAME_HINTS = listOf("TNT-", "TEMPWISE", "BG-BT", "INKBIRD", "IBT")
    private const val NONE = 0x7FFE

    /** Tips and handles from one read of the live characteristic. */
    data class Live(val probesC: List<Double?>, val handlesC: List<Int?>)

    fun looksLikeOne(name: String?, serviceUuids: List<String>): Boolean =
        name != null && NAME_HINTS.any { name.uppercase().contains(it) }

    /**
     * Decodes one advertisement. [companyId] and [data] are Android's split of the manufacturer
     * data; [address] (the base's MAC), when known, must match the copy inside the payload.
     */
    fun parseAdvert(companyId: Int, data: ByteArray, address: String?, atMillis: Long): ExternalReading? {
        val raw = byteArrayOf((companyId and 0xFF).toByte(), (companyId shr 8 and 0xFF).toByte()) + data
        if (raw.size < 22) return null
        if (address != null) {
            val mac = address.split(":").mapNotNull { it.toIntOrNull(16) }.reversed()
            if (mac.size != 6 || (0 until 6).any { (raw[4 + it].toInt() and 0xFF) != mac[it] }) return null
        }
        val b = raw.copyOfRange(10, 22).map { it.toInt() and 0xFF }
        fun docked(status: Int) = status and 0x80 != 0
        fun battery(status: Int) = (status and 0x7F).takeIf { it <= 100 }
        return ExternalReading(
            atMillis = atMillis,
            probesC = listOf(tip(b, 0), tip(b, 6)),
            handlesC = listOf(b[4], b[8]),
            docked = listOf(docked(b[5]), docked(b[9])),
            probeBatteryPct = listOf(battery(b[5]), battery(b[9])),
            baseBatteryPct = b[11].takeIf { it <= 100 },
        )
    }

    fun parseLive(value: ByteArray): Live? {
        if (value.size < 8) return null
        val b = value.map { it.toInt() and 0xFF }
        return Live(probesC = listOf(tip(b, 0), tip(b, 5)), handlesC = listOf(b[4], b[7]))
    }

    /** Folds a live read into the last full reading; a tip with no reading means it's in the base. */
    fun withLive(prev: ExternalReading?, live: Live, atMillis: Long): ExternalReading = ExternalReading(
        atMillis = atMillis,
        probesC = live.probesC,
        handlesC = live.handlesC,
        docked = live.probesC.map { it == null },
        probeBatteryPct = prev?.probeBatteryPct ?: listOf(null, null),
        baseBatteryPct = prev?.baseBatteryPct,
    )

    /** The battery characteristic (2A19) on this base holds three bytes: base, probe 1, probe 2. */
    fun withBattery(prev: ExternalReading?, value: ByteArray): ExternalReading? {
        if (prev == null || value.size < 3) return prev
        val b = value.map { (it.toInt() and 0xFF).takeIf { v -> v <= 100 } }
        return prev.copy(baseBatteryPct = b[0], probeBatteryPct = listOf(b[1], b[2]))
    }

    /**
     * An advertisement arriving while live reads are flowing: they are newer and come more often,
     * so only the advert's battery and dock news is taken.
     */
    fun withAdvert(prev: ExternalReading?, advert: ExternalReading, liveIsFresh: Boolean): ExternalReading =
        if (prev == null || !liveIsFresh) advert
        else prev.copy(probeBatteryPct = advert.probeBatteryPct, baseBatteryPct = advert.baseBatteryPct)

    private fun tip(b: List<Int>, i: Int): Double? {
        val v = b[i] or (b[i + 1] shl 8)
        if (v == NONE || v == 0x7FFF) return null
        return v.toShort() / 10.0
    }
}
