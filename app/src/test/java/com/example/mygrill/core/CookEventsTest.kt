package com.example.mygrill.core

import org.junit.Assert.assertEquals
import org.junit.Test

class CookEventsTest {
    private fun view(phase: CookPhase, prompt: Prompt? = null) = GrillView(
        phase = phase, prompt = prompt, lidOpen = false, mode = CookMode.GRILL, setpoint = 204,
        secondsSet = 600, secondsLeft = 600, smoke = false, grillC = 100.0, grillApprox = false,
        airC = null, smokeC = null, probes = emptyList(), sources = setOf(Source.BLE),
        beaconAgeMs = 0, cloudUpdatedAgeMs = null, cloudConnected = null, rssi = null, model = null, serial = null,
    )

    private fun events(a: GrillView?, b: GrillView?) = CookEvents.between(a, b)

    @Test
    fun aCookThatStartsAndPreheats() {
        assertEquals(listOf(CookEvent.STARTED), events(view(CookPhase.IDLE), view(CookPhase.PREHEAT)))
        assertEquals(listOf(CookEvent.STARTED), events(view(CookPhase.OFF), view(CookPhase.IGNITION)))
        assertEquals(listOf(CookEvent.PREHEAT_DONE), events(view(CookPhase.PREHEAT), view(CookPhase.COOKING)))
        assertEquals(listOf(CookEvent.PREHEAT_DONE), events(view(CookPhase.PREHEAT), view(CookPhase.PREHEAT, Prompt.ADD_FOOD)))
    }

    @Test
    fun promptsAndTheEnd() {
        assertEquals(listOf(CookEvent.FLIP), events(view(CookPhase.COOKING), view(CookPhase.COOKING, Prompt.FLIP_FOOD)))
        assertEquals(listOf(CookEvent.DONE), events(view(CookPhase.COOKING), view(CookPhase.DONE)))
        assertEquals(listOf(CookEvent.DONE), events(view(CookPhase.COOKING), view(CookPhase.COOKING, Prompt.GET_FOOD)))
        assertEquals(listOf(CookEvent.TURNED_OFF), events(view(CookPhase.IDLE), view(CookPhase.OFF)))
    }

    @Test
    fun nothingWithoutARealTransition() {
        assertEquals(emptyList<CookEvent>(), events(null, view(CookPhase.COOKING)))       // app opened mid-cook
        assertEquals(emptyList<CookEvent>(), events(view(CookPhase.COOKING), null))       // grill not heard
        assertEquals(emptyList<CookEvent>(), events(view(CookPhase.COOKING), view(CookPhase.COOKING)))
        assertEquals(emptyList<CookEvent>(), events(view(CookPhase.WAITING_LID), view(CookPhase.COOKING)))
    }
}
