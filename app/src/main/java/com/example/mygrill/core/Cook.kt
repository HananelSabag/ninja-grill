package com.example.mygrill.core

import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * What each mode accepts on the EU/IL grill, straight from the vendor app's own tables
 * (cook_temps.json / cook_times.json, "international"). Grill mode takes a heat level 1–3.
 * Woodfire: required for smoker, offered for grill/air crisp/bake/roast (the EU manual allows
 * it with Air Crisp too), never for dehydrate/reheat. No probes while dehydrating.
 */
data class ModeSpec(
    val mode: CookMode,
    val tempMin: Int,
    val tempMax: Int,
    val tempStep: Int,
    val tempDefault: Int,
    val minutesMin: Int,
    val minutesMax: Int,
    val minutesDefault: Int,
    val smoke: SmokeRule,
    val probes: Boolean,
) {
    val isLevel get() = mode == CookMode.GRILL
}

enum class SmokeRule { NEVER, OPTIONAL, REQUIRED }

object CookSpecs {
    val all = listOf(
        ModeSpec(CookMode.GRILL, 1, 3, 1, 3, 1, 60, 10, SmokeRule.OPTIONAL, true),
        ModeSpec(CookMode.SMOKER, 55, 210, 5, 120, 10, 720, 240, SmokeRule.REQUIRED, true),
        ModeSpec(CookMode.AIR_CRISP, 150, 240, 5, 200, 1, 60, 20, SmokeRule.OPTIONAL, true),
        ModeSpec(CookMode.BAKE, 120, 210, 10, 160, 1, 240, 30, SmokeRule.OPTIONAL, true),
        ModeSpec(CookMode.ROAST, 120, 220, 5, 180, 1, 240, 25, SmokeRule.OPTIONAL, true),
        ModeSpec(CookMode.DEHYDRATE, 40, 90, 5, 60, 10, 720, 360, SmokeRule.NEVER, false),
        ModeSpec(CookMode.REHEAT, 130, 210, 5, 170, 1, 60, 15, SmokeRule.NEVER, true),
    )

    fun of(mode: CookMode): ModeSpec? = all.firstOrNull { it.mode == mode }

    /** What grill mode's Lo / Med / Hi heat to, in °C, as our grill reports them. */
    val GRILL_LEVEL_C = listOf(204, 234, 265)

    /** Manual probe targets the vendor app allows (probe_presets.json, international "Manual"). */
    const val PROBE_MIN = 40
    const val PROBE_MAX = 99

    /**
     * The grill reports grill mode's target in °C; map it back to the heat level. Measured on our
     * grill: Lo = 204 (anything lower is raised to it, 2026-10-19), Med = 234, Hi = 265.
     */
    fun grillLevel(setpoint: Int?): Int? = when {
        setpoint == null -> null
        setpoint in 1..3 -> setpoint
        setpoint < 219 -> 1
        setpoint < 250 -> 2
        else -> 3
    }
}

/** One cook to start (or to re-issue with changes while it runs). */
data class CookRequest(
    val mode: CookMode,
    /** Heat level 1–3 for grill, °C otherwise. */
    val temp: Int,
    val seconds: Int,
    val smoke: Boolean,
    val skipPreheat: Boolean,
    val probeTargetsC: List<Int?> = listOf(null, null),
    /** Woodfire's pellet ignition, skipped the same way as preheat (the cloud names it apart). */
    val skipIgnition: Boolean = false,
) {
    /** Rejects anything outside the mode's limits. This drives a heating element: never clamp silently. */
    fun validate(): CookRequest {
        val spec = requireNotNull(CookSpecs.of(mode)) { "mode $mode cannot be started on this grill" }
        require(temp in spec.tempMin..spec.tempMax) { "temp $temp outside ${spec.tempMin}..${spec.tempMax}" }
        // Live changes re-send the remaining time, which may be under the mode's minimum.
        require(seconds in 60..spec.minutesMax * 60) { "time ${seconds}s outside 60..${spec.minutesMax * 60}" }
        require(spec.smoke != SmokeRule.NEVER || !smoke) { "$mode cannot use Woodfire" }
        require(spec.smoke != SmokeRule.REQUIRED || smoke) { "$mode needs Woodfire" }
        require(probeTargetsC.size == 2) { "two probe slots" }
        probeTargetsC.filterNotNull().forEach {
            require(spec.probes) { "$mode has no probe targets" }
            require(it in CookSpecs.PROBE_MIN..CookSpecs.PROBE_MAX) { "probe target $it outside ${CookSpecs.PROBE_MIN}..${CookSpecs.PROBE_MAX}" }
        }
        return this
    }
}

/** The two encodings of a cook command: the BLE struct and the cloud's JSON (spaced spelling). */
object CookCommand {
    const val CLOUD_START_ID = 1001
    const val CLOUD_STOP_ID = 1000

    /** 23-byte BLE cook command (type 0x0A); verified on our grill with dehydrate 40 °C / 600 s. */
    fun ble(r: CookRequest): ByteArray {
        r.validate()
        val b = ByteBuffer.allocate(23).order(ByteOrder.LITTLE_ENDIAN)
        b.put(0, 0x0A.toByte())
        b.put(2, r.mode.bleByte.toByte())
        b.put(3, (if (r.smoke) 1 else 0).toByte())
        b.putShort(4, r.temp.toShort())
        b.putInt(6, r.seconds)
        r.probeTargetsC.forEachIndexed { i, t -> if (t != null) probeSlot(b, 10 + i * 6, t) }
        b.put(22, (if (r.skipPreheat || r.skipIgnition) 1 else 0).toByte())
        return b.array()
    }

    /** Stop = a grill-mode cook with zero temperature and time. */
    fun bleStop(): ByteArray = ByteArray(23).also {
        it[0] = 0x0A
        it[2] = CookMode.GRILL.bleByte.toByte()
    }

    fun cloud(r: CookRequest): JSONObject {
        r.validate()
        return JSONObject()
            .put("id", CLOUD_START_ID)
            .put("mode", cloudMode(r.mode))
            .put("seconds set", r.seconds)
            .put("temp", r.temp)
            .put("smoke", if (r.smoke) 1 else 0)
            .put("skip preheat", if (r.skipPreheat) 1 else 0)
            .apply { if (r.skipIgnition) put("skip ignition", 1) }
            .apply {
                r.probeTargetsC.forEachIndexed { i, t ->
                    if (t != null) put("probe$i", JSONObject().put("mode", "manual").put("setpoint", t))
                }
            }
    }

    fun cloudStop(): JSONObject = JSONObject()
        .put("id", CLOUD_STOP_ID).put("mode", "grill").put("temp", 0)
        .put("seconds set", 0).put("smoke", 0).put("skip preheat", 0)

    /** The firmware's spelling, with the space ("air crisp"). */
    private fun cloudMode(m: CookMode) = if (m == CookMode.AIR_CRISP) "air crisp" else m.wire

    /** Probe slot: target °C (i16) then the constant tail the vendor app sends (00 63 00 01). */
    private fun probeSlot(b: ByteBuffer, off: Int, targetC: Int) {
        b.putShort(off, targetC.toShort())
        b.put(off + 2, 0x00.toByte())
        b.put(off + 3, 0x63.toByte())
        b.put(off + 4, 0x00.toByte())
        b.put(off + 5, 0x01.toByte())
    }
}
