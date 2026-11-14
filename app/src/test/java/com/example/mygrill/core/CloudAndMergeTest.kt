package com.example.mygrill.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.json.JSONObject
import org.junit.Test
import java.time.Instant

class CloudAndMergeTest {
    private val item = Fixtures.json("aws_device_cooking.json")
    private val updatedAt = Instant.parse(item.getString("updatedAt")).toEpochMilli()

    @Test
    fun parsesAwsTelemetryWithStrippedSpaces() {
        val c = CloudParse.device(item, nowMillis = updatedAt)
        assertEquals(CookPhase.COOKING, c.phase)
        assertEquals(CookMode.AIR_CRISP, c.mode)
        assertEquals(150, c.setpoint)          // °C as set, not converted
        assertEquals(180, c.secondsLeft)
        assertEquals(Prompt.ADD_FOOD, c.prompt)
        assertEquals(118.0, c.airC!!, 0.01)    // 244.4 °F
        assertEquals(54.2, c.grillC!!, 0.01)   // 129.5 °F
        assertFalse(c.lidOpen!!)
        assertEquals(-65, c.rssi)
        assertEquals("OG900-EU", c.model)
        assertEquals(2, c.probes.size)
        assertFalse(c.probes[0].pluggedIn)
        assertEquals(true, c.connected)
    }

    @Test
    fun aylaSpellingWorksToo() {
        assertEquals(Prompt.ADD_FOOD, CloudParse.prompt("1: add food"))
        assertEquals(CookPhase.OFF, CloudParse.phase("powered OFF"))
        assertEquals(CookPhase.WAITING_LID, CloudParse.phase("wait lid closed before cook"))
        assertEquals(CookMode.AIR_CRISP, CookMode.fromWire("air crisp"))
    }

    @Test
    fun beaconWinsForLiveFieldsAndCloudFillsTheRest() {
        val cloud = CloudParse.device(item, nowMillis = updatedAt)
        val beacon = BeaconReading(updatedAt, CookPhase.COOKING, Prompt.FLIP_FOOD, lidOpen = true,
            probesC = listOf(54.0, null), grillC = 100.0)
        val v = Merge.view(beacon, cloud, nowMillis = updatedAt + 1_000)!!
        assertEquals(Prompt.FLIP_FOOD, v.prompt)
        assertTrue(v.lidOpen!!)
        assertEquals(54.0, v.probes[0].tempC!!, 0.0)
        assertEquals(Source.BLE, v.probes[0].source)
        assertEquals(CookMode.AIR_CRISP, v.mode)
        assertEquals(54.2, v.grillC!!, 0.01)   // cloud's calibrated reading beats the beacon field
        assertFalse(v.grillApprox)
        assertEquals(setOf(Source.BLE, Source.CLOUD), v.sources)
    }

    @Test
    fun countdownRunsFromTheEndTime() {
        val cloud = CloudParse.device(item, nowMillis = updatedAt)
        val end = cloud.endTimeUtcSec!!
        val v = Merge.view(null, cloud, nowMillis = (end - 42) * 1000)!!
        assertEquals(42, v.secondsLeft)
    }

    @Test
    fun staleBeaconAndOfflineCloudMeanNoView() {
        val beacon = BeaconReading(0, CookPhase.IDLE, null, false, listOf(null, null), null)
        assertNull(Merge.view(beacon, null, nowMillis = Merge.BEACON_FRESH_MS + 1))
    }

    @Test
    fun idleGrillIgnoresTheCloudsLastCookTemperatures() {
        // Our grill on 2026-10-18, an hour after cooking: AWS still said grill 86 °C / smoke 121 °C,
        // the beacon said 53 °C.
        val cloud = CloudParse.device(item, nowMillis = updatedAt)
        val beacon = BeaconReading(updatedAt, CookPhase.IDLE, null, false, listOf(17.0, null), grillC = 53.3)
        val v = Merge.view(beacon, cloud, nowMillis = updatedAt + 1_000)!!
        assertEquals(CookPhase.IDLE, v.phase)
        assertEquals(53.3, v.grillC!!, 0.0)
        assertTrue(v.grillApprox)
        assertNull(v.airC)
        assertNull(v.smokeC)
        assertNull(v.mode)
        assertNull(v.prompt)
    }

    @Test
    fun disconnectedCloudWithoutBeaconMeansOff() {
        // What the owner saw: grill switched off, the app still said "ready". It must say off.
        val off = JSONObject(item.toString()).apply { getJSONObject("connectivityStatus").put("connected", false) }
        val v = Merge.view(null, CloudParse.device(off, updatedAt), nowMillis = updatedAt + 1_000)!!
        assertEquals(CookPhase.OFF, v.phase)
        assertNull(v.grillC)
        assertNull(v.secondsLeft)
        assertNull(v.mode)
        assertEquals(emptySet<Source>(), v.sources)
        assertEquals(false, v.cloudConnected)
        assertEquals("OG900-EU", v.model)
    }

    @Test
    fun selfSwitchedOffGrillIsOffEvenWhileItStillAdvertises() {
        // The owner's report: the grill turned its panel off by itself, the app still said
        // "connected" and offered commands. Cloud says poweredOFF; the beacon still says idle.
        val offItem = Fixtures.json("aws_device_powered_off.json")
        val at = Instant.parse(offItem.getString("updatedAt")).toEpochMilli()
        val cloud = CloudParse.device(offItem, at)
        assertEquals(CookPhase.OFF, cloud.phase)
        assertEquals(true, cloud.connected)              // the Wi-Fi module stays online
        val beacon = BeaconReading(at, CookPhase.IDLE, null, true, listOf(null, null), grillC = 96.0)
        val v = Merge.view(beacon, cloud, nowMillis = at + 1_000)!!
        assertEquals(CookPhase.OFF, v.phase)
        assertNull(v.mode)
        assertNull(v.secondsLeft)
    }

    @Test
    fun bleOnlyViewMarksTheGrillReadingApproximate() {
        val beacon = BeaconReading(1_000, CookPhase.PREHEAT, null, false, listOf(17.0, null), grillC = 65.6)
        val v = Merge.view(beacon, null, nowMillis = 2_000)!!
        assertEquals(CookPhase.PREHEAT, v.phase)
        assertEquals(65.6, v.grillC!!, 0.0)
        assertTrue(v.grillApprox)
        assertNull(v.secondsLeft)
        assertEquals(setOf(Source.BLE), v.sources)
    }
}
