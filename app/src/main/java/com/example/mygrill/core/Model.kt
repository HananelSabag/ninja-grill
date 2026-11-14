package com.example.mygrill.core

/** Where the grill is in a cook, merged from the beacon's phase and the cloud's state strings. */
enum class CookPhase { OFF, IDLE, IGNITION, PREHEAT, COOKING, RESTING, DONE, WAITING_LID, UNKNOWN;

    val heating: Boolean get() = this == IGNITION || this == PREHEAT || this == COOKING
}

/** What the grill's own display is asking for. */
enum class Prompt { ADD_FOOD, FLIP_FOOD, DONE, GET_FOOD }

/**
 * Cook modes, with the firmware's BLE mode byte and the cloud's spelling (spaces stripped).
 * Broil and griddle have no known BLE byte (-1): the EU grill doesn't offer them over BLE.
 */
enum class CookMode(val bleByte: Int, val wire: String) {
    GRILL(1, "grill"), SMOKER(2, "smoker"), AIR_CRISP(3, "aircrisp"), BAKE(4, "bake"),
    ROAST(5, "roast"), BROIL(-1, "broil"), DEHYDRATE(7, "dehydrate"), REHEAT(8, "reheat"), GRIDDLE(-1, "griddle");

    companion object {
        fun fromByte(b: Int): CookMode? = entries.firstOrNull { it.bleByte == b && b >= 0 }
        fun fromWire(s: String?): CookMode? {
            val key = s?.replace(" ", "")?.lowercase() ?: return null
            return entries.firstOrNull { it.wire == key } ?: if (key == "smoke") SMOKER else null
        }
    }
}

/** One decoded advertisement. */
data class BeaconReading(
    val atMillis: Long,
    val phase: CookPhase,
    val prompt: Prompt?,
    val lidOpen: Boolean,
    val probesC: List<Double?>,
    val grillC: Double?,
)

/** One probe as the cloud reports it (temperature already °C; target from its probe mode). */
data class CloudProbe(val pluggedIn: Boolean, val active: Boolean, val tempC: Double?, val targetC: Int?, val progress: Int?)

/** The grill as the AWS backend last saw it. */
data class CloudReading(
    val atMillis: Long,
    val updatedAtMillis: Long?,
    val connected: Boolean?,
    val phase: CookPhase,
    val prompt: Prompt?,
    val mode: CookMode?,
    val setpoint: Int?,
    val secondsSet: Int?,
    val secondsLeft: Int?,
    val endTimeUtcSec: Long?,
    val smoke: Boolean?,
    val lidOpen: Boolean?,
    val grillC: Double?,
    val airC: Double?,
    val smokeC: Double?,
    val probes: List<CloudProbe>,
    val error: Int?,
    val rssi: Int?,
    val model: String?,
    val serial: String?,
)
