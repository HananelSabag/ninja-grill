package com.example.mygrill.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class BeaconTest {

    @Test
    fun firstCaptureWasAPanelOffGrill() {
        // Taken on 2026-10-18 before we knew the power byte: it reads 3, panel off, still advertising.
        val r = Beacon.parse(Fixtures.hex("beacon_plain_idle"), atMillis = 0)
        assertEquals(CookPhase.OFF, r.phase)
        assertNull(r.prompt)
        assertFalse(r.lidOpen)
        assertEquals(listOf(18.0, null), r.probesC) // wired probe in port 1 reading the evening air
    }

    @Test
    fun wakeTestPanelOffThenIdle() {
        // Owner-approved test, 2026-10-19 21:59: one BLE stop lit the display; byte 2 went 3 -> 4.
        assertEquals(CookPhase.OFF, Beacon.parse(Fixtures.hex("beacon_plain_panel_off"), 0).phase)
        assertEquals(CookPhase.IDLE, Beacon.parse(Fixtures.hex("beacon_plain_woken_idle"), 0).phase)
    }

    @Test
    fun preheatBeaconShowsPhaseAndRisingGrillSensor() {
        val first = Beacon.parse(Fixtures.hex("beacon_plain_preheat_first"), 0)
        val last = Beacon.parse(Fixtures.hex("beacon_plain_preheat_last"), 0)
        assertEquals(CookPhase.PREHEAT, first.phase)
        assertEquals(CookPhase.PREHEAT, last.phase)
        assertEquals(17.0, first.probesC[0]!!, 0.0)
        // Raw 85 -> 150 over ~2 minutes of grill-mode preheat, read as °F.
        assertEquals(29.4, first.grillC!!, 0.01)
        assertEquals(65.6, last.grillC!!, 0.01)
    }

    @Test
    fun bitReaderIsLsbFirst() {
        val buf = byteArrayOf(0b1011_0000.toByte(), 0b0000_0001)
        assertEquals(0b1011, Beacon.bits(buf, 4 to 4))
        assertEquals(0b1_1011, Beacon.bits(buf, 4 to 5))
    }

    @Test
    fun firstManufacturerAdIsTheMainAdvertisement() {
        val mfg1 = ByteArray(20) { it.toByte() }
        val mfg2 = ByteArray(23) { 0x55 }
        val record = byteArrayOf(2, 0x01, 0x06) +
            byteArrayOf(23, 0xFF.toByte(), 0x4F, 0x0C) + mfg1 +
            byteArrayOf(26, 0xFF.toByte(), 0x4F, 0x0C) + mfg2
        assertArrayEquals(mfg1, Beacon.mfgFromScanRecord(record))
    }

    @Test
    fun ignoresOtherCompanies() {
        val record = byteArrayOf(23, 0xFF.toByte(), 0x75, 0x00) + ByteArray(20)
        assertNull(Beacon.mfgFromScanRecord(record))
    }

    @Test
    fun decryptsARealCapturedBeacon() {
        val keys = Fixtures.keyBundleOrSkip()
        val plain = Beacon.decrypt(Fixtures.privateOrSkip().getString("beacon_encrypted_idle").hexToBytes(), keys.beacon)
        // Same capture window as the idle plaintext: everything but the rolling bytes 8 and 16..18 matches.
        val idle = Fixtures.hex("beacon_plain_idle")
        for (i in listOf(0, 1, 2, 3, 4, 5, 6, 7, 9, 10, 11, 12, 13, 14, 15, 19)) assertEquals("byte $i", idle[i], plain[i])
    }
}
