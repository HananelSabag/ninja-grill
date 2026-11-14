package com.example.mygrill.core

/**
 * Made-up grills for looking at every screen without lighting the real one (debug builds only):
 * adb shell am start -n com.example.mygrill/.MainActivity --es demo preheat
 * The repository refuses every command while one of these is on.
 */
object Demo {
    val names = listOf("idle", "preheat", "cooking", "timer", "done", "off", "starting", "lid")

    fun view(name: String): GrillView? {
        fun v(
            phase: CookPhase, mode: CookMode? = CookMode.GRILL, setpoint: Int? = 265, grill: Double? = null, air: Double? = null,
            smoke: Double? = null, probe0: Double? = 16.0, target0: Int? = null, set: Int? = null, left: Int? = null,
        ) = GrillView(
            phase = phase, prompt = null, lidOpen = false, mode = mode, setpoint = setpoint, secondsSet = set, secondsLeft = left,
            smoke = false, grillC = grill, grillApprox = false, airC = air, smokeC = smoke,
            probes = listOf(ProbeView(probe0, target0, null, Source.BLE), ProbeView(null, null, null, null)),
            sources = setOf(Source.BLE, Source.CLOUD), beaconAgeMs = 1_000, cloudUpdatedAgeMs = 6_000, cloudConnected = true,
            rssi = -61, model = "OG900-EU", serial = null,
        )
        return when (name) {
            "idle" -> v(CookPhase.IDLE, setpoint = null, grill = 31.0, smoke = 24.0)
            "preheat" -> v(CookPhase.PREHEAT, grill = 134.0, air = 118.0, smoke = 31.0, probe0 = 18.0, target0 = 56, set = 3600)
            "cooking" -> v(CookPhase.COOKING, grill = 262.0, air = 241.0, smoke = 58.0, probe0 = 48.0, target0 = 56, set = 3600, left = 2280)
            "timer" -> v(CookPhase.COOKING, mode = CookMode.ROAST, setpoint = 180, grill = 179.0, air = 182.0, smoke = 40.0, probe0 = null, set = 1500, left = 1100)
            "done" -> v(CookPhase.IDLE, setpoint = null, grill = 188.0, smoke = 55.0, probe0 = 55.0)
            // A start that the grill hasn't answered yet, and one held by an open lid.
            "starting" -> v(CookPhase.IDLE, setpoint = null, grill = 31.0, smoke = 24.0)
            "lid" -> v(CookPhase.IDLE, setpoint = null, grill = 31.0, smoke = 24.0).copy(lidOpen = true)
            "off" -> v(CookPhase.OFF, setpoint = null, grill = 22.0, probe0 = null).copy(sources = setOf(Source.CLOUD))
            else -> null
        }
    }
}
