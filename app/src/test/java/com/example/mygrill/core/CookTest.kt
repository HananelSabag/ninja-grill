package com.example.mygrill.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.random.Random

class CookTest {
    private val dehydrate40 = CookRequest(CookMode.DEHYDRATE, temp = 40, seconds = 600, smoke = false, skipPreheat = false)

    @Test
    fun bleCommandMatchesTheOneOurGrillAccepted() {
        // The exact command sent in the 2026-10-18 test; the grill switched to dehydrate 40 °C / 600 s.
        val expected = "0a000700280058020000".hexToBytes() + ByteArray(13)
        assertArrayEquals(expected, CookCommand.ble(dehydrate40))
    }

    @Test
    fun probeTargetsAndFlagsLandInTheirSlots() {
        val r = CookRequest(CookMode.SMOKER, 110, 4 * 3600, smoke = true, skipPreheat = true, probeTargetsC = listOf(74, null))
        val b = CookCommand.ble(r)
        assertEquals(2, b[2].toInt())                  // smoker
        assertEquals(1, b[3].toInt())                  // woodfire
        assertEquals(110, (b[4].toInt() and 0xFF) or (b[5].toInt() shl 8))
        assertArrayEquals("4a0000630001".hexToBytes(), b.copyOfRange(10, 16))
        assertArrayEquals(ByteArray(6), b.copyOfRange(16, 22))
        assertEquals(1, b[22].toInt())                 // skip preheat
    }

    @Test
    fun stopIsAZeroGrillCook() {
        assertArrayEquals(byteArrayOf(0x0A, 0, 1) + ByteArray(20), CookCommand.bleStop())
    }

    @Test
    fun cloudCommandUsesTheSpacedSpelling() {
        val j = CookCommand.cloud(CookRequest(CookMode.AIR_CRISP, 200, 1200, smoke = true, skipPreheat = false, probeTargetsC = listOf(null, 63)))
        assertEquals(CookCommand.CLOUD_START_ID, j.getInt("id"))
        assertEquals("air crisp", j.getString("mode"))
        assertEquals(1200, j.getInt("seconds set"))
        assertEquals(1, j.getInt("smoke"))
        assertEquals(0, j.getInt("skip preheat"))
        assertFalse(j.has("probe0"))
        assertEquals(63, j.getJSONObject("probe1").getInt("setpoint"))
        assertEquals("manual", j.getJSONObject("probe1").getString("mode"))
    }

    @Test
    fun outOfRangeRequestsAreRejectedNotClamped() {
        assertThrows(IllegalArgumentException::class.java) { CookRequest(CookMode.GRILL, 4, 600, false, false).validate() }
        assertThrows(IllegalArgumentException::class.java) { CookRequest(CookMode.SMOKER, 300, 600, true, false).validate() }
        assertThrows(IllegalArgumentException::class.java) { CookRequest(CookMode.SMOKER, 110, 600, smoke = false, skipPreheat = false).validate() }
        assertThrows(IllegalArgumentException::class.java) { CookRequest(CookMode.DEHYDRATE, 60, 600, smoke = true, skipPreheat = false).validate() }
        assertThrows(IllegalArgumentException::class.java) { CookRequest(CookMode.DEHYDRATE, 60, 600, false, false, listOf(70, null)).validate() }
        assertThrows(IllegalArgumentException::class.java) { CookRequest(CookMode.ROAST, 180, 30, false, false).validate() }
        assertThrows(IllegalArgumentException::class.java) { CookRequest(CookMode.BROIL, 200, 600, false, false).validate() }
    }

    @Test
    fun skipIgnitionUsesTheSkipByteAndItsOwnCloudKey() {
        val r = CookRequest(CookMode.SMOKER, 110, 3600, smoke = true, skipPreheat = true, skipIgnition = true)
        assertEquals(1, CookCommand.ble(r)[22].toInt())
        assertEquals(1, CookCommand.cloud(r).getInt("skip ignition"))
        assertFalse(CookCommand.cloud(r.copy(skipIgnition = false)).has("skip ignition"))
    }

    @Test
    fun grillLevelFromTheCloudsSetpoint() {
        assertEquals(1, CookSpecs.grillLevel(1))
        assertEquals(1, CookSpecs.grillLevel(204))   // Lo: what our grill made of a 50 °C request
        assertEquals(2, CookSpecs.grillLevel(234))   // Med: a live grill-mode state frame
        assertEquals(3, CookSpecs.grillLevel(265))   // our grill's July Ayla data, grill mode
        assertNull(CookSpecs.grillLevel(null))
    }

    @Test
    fun framesRoundTrip() {
        val slot = Slot(Random(1).nextBytes(32), Random(2).nextBytes(16))
        val rnd = Random(3)
        val frame = BleFrames.encode(CookCommand.ble(dehydrate40), slot, token = 0xCAFEL) { rnd.nextBytes(it) }
        assertEquals(0, frame.size % 16)
        val back = BleFrames.decode(frame, slot)!!
        assertEquals(0xCAFEL, back.token)
        assertArrayEquals(CookCommand.ble(dehydrate40), back.body)
    }

    @Test
    fun connectFrameWithTheRealTxKeyIsThreeBlocks() {
        val keys = Fixtures.keyBundleOrSkip()
        val frame = BleFrames.encode(BleFrames.CONNECT, keys.tx, 0) { ByteArray(it) }
        assertEquals(48, frame.size)
        assertArrayEquals(BleFrames.CONNECT, BleFrames.decode(frame, keys.tx)!!.body)
    }
}
