package com.example.mygrill.core

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/**
 * Parses one device record of the AWS backend (GET /devicesEndUserController/{household}/users/{user}).
 * The live state sits in `telemetry` as JSON strings in the old Ayla schema, with every space
 * stripped from keys and values ("seconds set" -> "secondsset"). Keys are matched space- and
 * case-insensitively so both spellings work. Grill/air/smoke temperatures come in °F; the setpoint
 * and the probes' own temperatures are already °C.
 */
object CloudParse {

    fun device(item: JSONObject, nowMillis: Long): CloudReading {
        val telemetry = item.optJSONObject("telemetry") ?: JSONObject()
        val grill = json(telemetry.opt("GrillState"))
        val cook = json(telemetry.opt("CookState"))
        val probes = json(telemetry.opt("ProbeState"))
        val inputs = grill.obj("inputs")
        val temps = inputs.obj("temps")
        val registry = item.optJSONObject("registry") ?: JSONObject()

        val cookStateName = cook.obj("state").str("state")
        val phase = phase(grill.str("state") ?: cookStateName)
        return CloudReading(
            atMillis = nowMillis,
            updatedAtMillis = item.optString("updatedAt").takeIf { it.isNotEmpty() }
                ?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() },
            connected = item.optJSONObject("connectivityStatus")?.let { if (it.has("connected")) it.optBoolean("connected") else null },
            phase = phase,
            prompt = prompt(grill.str("message")),
            mode = CookMode.fromWire(grill.str("mode")),
            setpoint = grill.int("setpoint"),
            secondsSet = grill.int("secondsset"),
            secondsLeft = grill.int("secondsleft"),
            endTimeUtcSec = grill.long("endtimeutc")?.takeIf { it > 0 },
            smoke = grill.int("smoke")?.let { it != 0 },
            lidOpen = inputs.obj("io").int("lidopen")?.let { it != 0 },
            grillC = temps.fahrenheit("grill"),
            airC = temps.fahrenheit("air"),
            smokeC = temps.fahrenheit("smoke"),
            probes = probes.arr("probes").map(::probe),
            error = grill.int("error"),
            rssi = telemetry.opt("RSSI")?.toString()?.toIntOrNull(),
            model = registry.optString("modelNumber").ifEmpty { registry.optString("model") }.ifEmpty { null },
            serial = registry.optString("serialNumber").ifEmpty { null },
        )
    }

    fun phase(state: String?): CookPhase = when (state?.replace(" ", "")?.lowercase()) {
        null -> CookPhase.UNKNOWN
        "poweredoff" -> CookPhase.OFF
        "idle", "none", "" -> CookPhase.IDLE
        "ignition", "start" -> CookPhase.IGNITION
        "preheat", "preheating" -> CookPhase.PREHEAT
        "cooking", "cook", "heat", "flip", "getfood", "lidopen" -> CookPhase.COOKING
        "rest", "resting" -> CookPhase.RESTING
        "done" -> CookPhase.DONE
        "waitlidclosedbeforecook" -> CookPhase.WAITING_LID
        else -> CookPhase.UNKNOWN
    }

    /** "1:addfood" (AWS) or "1: add food" (Ayla) -> the prompt after the colon. */
    fun prompt(message: String?): Prompt? = when (message?.substringAfter(':')?.replace(" ", "")?.lowercase()) {
        "addfood" -> Prompt.ADD_FOOD
        "flipfood", "flip" -> Prompt.FLIP_FOOD
        "getfood" -> Prompt.GET_FOOD
        "done", "complete" -> Prompt.DONE
        else -> null
    }

    private fun probe(o: JSONObject): CloudProbe {
        val mode = o.obj("mode")
        return CloudProbe(
            pluggedIn = o.int("pluggedin") == 1,
            active = o.int("active") == 1,
            tempC = o.double("temp")?.takeIf { it > 0 },
            targetC = mode.int("setpoint")?.takeIf { it > 0 },
            progress = o.int("progress"),
        )
    }

    // ---------------------------------------------------------------- tolerant JSON access

    private fun json(raw: Any?): JSONObject = when (raw) {
        is JSONObject -> raw
        is String -> runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
        else -> JSONObject()
    }

    /** Value for [key], matching keys with spaces removed and case ignored. */
    private fun JSONObject.find(key: String): Any? {
        if (has(key)) return opt(key)
        val want = key.replace(" ", "").lowercase()
        return keys().asSequence().firstOrNull { it.replace(" ", "").lowercase() == want }?.let(::opt)
    }

    private fun JSONObject.obj(key: String): JSONObject = find(key) as? JSONObject ?: JSONObject()
    private fun JSONObject.arr(key: String): List<JSONObject> =
        (find(key) as? JSONArray)?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } } ?: emptyList()
    private fun JSONObject.str(key: String): String? = find(key)?.takeIf { it != JSONObject.NULL }?.toString()
    private fun JSONObject.double(key: String): Double? = str(key)?.toDoubleOrNull()?.takeIf { it.isFinite() }
    private fun JSONObject.int(key: String): Int? = double(key)?.toInt()
    private fun JSONObject.long(key: String): Long? = double(key)?.toLong()
    private fun JSONObject.fahrenheit(key: String): Double? = double(key)?.takeIf { it > 0 }?.let(::fahrenheitToC)
}
