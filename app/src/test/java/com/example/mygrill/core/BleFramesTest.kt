package com.example.mygrill.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BleFramesTest {

    @Test
    fun stateFrameFromOurDehydrateTest() {
        val s = BleFrames.parseState(Fixtures.hex("gatt_body_state_dehydrate_40c_600s"))!!
        assertEquals(CookMode.DEHYDRATE, s.mode)
        assertEquals(40, s.setpoint)
        assertEquals(600, s.secondsSet)
        assertEquals(600, s.secondsLeft)
        assertTrue(s.active)
        assertEquals(31.5, s.chamberC!!, 0.0)
    }

    @Test
    fun stateFramesCarryThePanelState() {
        assertEquals(7, BleFrames.parseState(Fixtures.hex("gatt_body_state_dehydrate_40c_600s"))!!.power)
        val woken = BleFrames.parseState(Fixtures.hex("gatt_body_state_woken_idle"))!!
        assertEquals(4, woken.power)
        assertEquals(false, woken.active)
        assertEquals(30.5, woken.chamberC!!, 0.0)
    }

    @Test
    fun infoFrameCarriesTheDsn() {
        val info = BleFrames.parseInfo(Fixtures.hex("gatt_body_info"))!!
        assertEquals("AC000W000000000", info.dsn)
        assertTrue(info.serial!!.startsWith("SND"))
    }

    @Test
    fun otherFrameTypesAreNotParsedAsState() {
        assertNull(BleFrames.parseState(Fixtures.hex("gatt_body_info")))
        assertNull(BleFrames.parseInfo(Fixtures.hex("gatt_body_state_dehydrate_40c_600s")))
    }

    @Test
    fun maskIsSelfInverse() {
        val buf = ByteArray(48) { (it * 7).toByte() }
        assertNotEquals(buf.toList(), BleFrames.mask(buf).toList())
        assertArrayEquals(buf, BleFrames.mask(BleFrames.mask(buf)))
    }

    @Test
    fun rejectsFramesWithTheWrongKey() {
        val wrong = Slot(ByteArray(32) { 1 }, ByteArray(16) { 2 })
        assertNull(BleFrames.decode(ByteArray(48) { (it * 13 + 5).toByte() }, wrong))
    }

    @Test
    fun decodesARealCapturedFrame() {
        val keys = Fixtures.keyBundleOrSkip()
        val private = Fixtures.privateOrSkip()
        val frame = BleFrames.decode(private.getString("gatt_encrypted_info").hexToBytes(), keys.rx)!!
        assertTrue(frame.token != 0L)
        assertEquals(private.getString("dsn"), BleFrames.parseInfo(frame.body)!!.dsn)
    }
}
