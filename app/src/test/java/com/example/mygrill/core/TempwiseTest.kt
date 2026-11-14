package com.example.mygrill.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real advertisements of a TNT-2BW, captured 2026-11-03 (its MAC swapped for A4:C1:38:00:11:22). */
class TempwiseTest {
    private val mac = "A4:C1:38:00:11:22"

    /** Android splits off the first two bytes as the "company id" (little-endian). */
    private fun parse(hex: String, address: String? = mac): ExternalReading? {
        val raw = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val cid = (raw[0].toInt() and 0xFF) or ((raw[1].toInt() and 0xFF) shl 8)
        return Tempwise.parseAdvert(cid, raw.copyOfRange(2, raw.size), address, 0)
    }

    @Test
    fun bothProbesInTheBase() {
        val r = parse("8db8ed6922110038c1a4fe7ffe7f1de4fe7f1ae4dd4b")!!
        assertEquals(listOf<Double?>(null, null), r.probesC)
        assertEquals(listOf<Int?>(29, 26), r.handlesC)
        assertEquals(listOf(true, true), r.docked)
        assertEquals(listOf<Int?>(100, 100), r.probeBatteryPct)
        assertEquals(75, r.baseBatteryPct)
    }

    @Test
    fun probeOneWarmedInAHand() {
        val r = parse("8db8ed6922110038c1a42c010e011f64fe7f1ae48d4b")!!
        assertEquals(30.0, r.probesC[0]!!, 0.001)
        assertNull(r.probesC[1])
        assertEquals(31, r.handlesC[0])
        assertEquals(listOf(false, true), r.docked)
    }

    @Test
    fun probeTwoWarmedInAHand() {
        val r = parse("8db8ed6922110038c1a4fe7ffe7f20e436012164d84b")!!
        assertNull(r.probesC[0])
        assertEquals(31.0, r.probesC[1]!!, 0.001)
        assertEquals(33, r.handlesC[1])
        assertEquals(listOf(true, false), r.docked)
    }

    @Test
    fun bothOut() {
        val r = parse("8db8ed6922110038c1a40401fa001c6404011b64884a")!!
        assertEquals(26.0, r.probesC[0]!!, 0.001)
        assertEquals(26.0, r.probesC[1]!!, 0.001)
        assertEquals(74, r.baseBatteryPct)
    }

    @Test
    fun anotherBasesAdvertIsIgnored() {
        assertNull(parse("8db8ed6922110038c1a4fe7ffe7f1de4fe7f1ae4dd4b", address = "A4:C1:38:00:00:01"))
        assertNull(parse("8db8ed69"))
    }

    @Test
    fun recognisesByName() {
        assertTrue(Tempwise.looksLikeOne("TNT-2BW", emptyList()))
        assertTrue(Tempwise.looksLikeOne("BG-BT1W", emptyList()))
        assertFalse(Tempwise.looksLikeOne("JBL PartyBox", emptyList()))
    }

    private fun bytes(hex: String) = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /** Reads of FF01 over the link, same day: probe 1 out of the base, probe 2 in it. */
    @Test
    fun liveRead() {
        val live = Tempwise.parseLive(bytes("18010e011efe7f190803"))!!
        assertEquals(28.0, live.probesC[0]!!, 0.001)
        assertNull(live.probesC[1])
        assertEquals(listOf<Int?>(30, 25), live.handlesC)
        assertNull(Tempwise.parseLive(bytes("1801")))
    }

    @Test
    fun liveReadKeepsTheBatteriesAndMarksTheDock() {
        val advert = parse("8db8ed6922110038c1a4fe7ffe7f1de4fe7f1ae4dd4b")!!
        val r = Tempwise.withLive(advert, Tempwise.parseLive(bytes("0e0104011efe7f190803"))!!, 5)
        assertEquals(27.0, r.probesC[0]!!, 0.001)
        assertEquals(listOf(false, true), r.docked)
        assertEquals(75, r.baseBatteryPct)
        assertEquals(5L, r.atMillis)
        val b = Tempwise.withBattery(r, bytes("4c6464"))!!
        assertEquals(76, b.baseBatteryPct)
        assertEquals(listOf<Int?>(100, 100), b.probeBatteryPct)
    }

    @Test
    fun anAdvertDoesNotUndoANewerLiveRead() {
        val live = Tempwise.withLive(null, Tempwise.parseLive(bytes("18010e011efe7f190803"))!!, 9)
        val advert = parse("8db8ed6922110038c1a40401fa001c6404011b64884a")!!
        val kept = Tempwise.withAdvert(live, advert, liveIsFresh = true)
        assertEquals(28.0, kept.probesC[0]!!, 0.001)
        assertEquals(74, kept.baseBatteryPct)
        assertEquals(26.0, Tempwise.withAdvert(live, advert, liveIsFresh = false).probesC[1]!!, 0.001)
    }
}
