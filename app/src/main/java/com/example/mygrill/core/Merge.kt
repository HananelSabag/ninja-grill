package com.example.mygrill.core

/** Which channel a value on screen came from. */
enum class Source { BLE, CLOUD }

data class ProbeView(val tempC: Double?, val targetC: Int?, val progress: Int?, val source: Source?)

/** Everything the dashboard shows, already merged from the beacon and the cloud. */
data class GrillView(
    val phase: CookPhase,
    val prompt: Prompt?,
    val lidOpen: Boolean?,
    val mode: CookMode?,
    val setpoint: Int?,
    val secondsSet: Int?,
    val secondsLeft: Int?,
    val smoke: Boolean?,
    val grillC: Double?,
    /** The grill temperature came only from the beacon field, whose units aren't pinned yet. */
    val grillApprox: Boolean,
    val airC: Double?,
    val smokeC: Double?,
    val probes: List<ProbeView>,
    val sources: Set<Source>,
    val beaconAgeMs: Long?,
    val cloudUpdatedAgeMs: Long?,
    val cloudConnected: Boolean?,
    val rssi: Int?,
    val model: String?,
    val serial: String?,
)

/** The grill's own state reply to our last BLE command, and when it came. */
data class GattSnapshot(val atMillis: Long, val state: GattState)

object Merge {
    /** The grill advertises every 1–2 s; a reading older than this means nobody's hearing it. */
    const val BEACON_FRESH_MS = 30_000L
    /** Cloud telemetry older than this (while the grill idles) is a stale cache, not live data. */
    const val CLOUD_LIVE_MS = 5 * 60_000L

    /**
     * The beacon is the live truth for phase, prompt, lid and probe temperatures whenever it's heard;
     * the cloud adds what the beacon lacks (mode, setpoint, time, resting/done) and covers it when
     * the grill is out of Bluetooth range.
     */
    fun view(beacon: BeaconReading?, cloud: CloudReading?, nowMillis: Long, gatt: GattSnapshot? = null): GrillView? {
        val b = beacon?.takeIf { nowMillis - it.atMillis < BEACON_FRESH_MS }
        val cloudAge = cloud?.updatedAtMillis?.let { nowMillis - it }
        val c = cloud?.takeIf { it.connected == true || (it.connected == null && cloudAge != null && cloudAge < CLOUD_LIVE_MS) }
        if (b == null && c == null) {
            // Not advertising and the cloud says it's disconnected: the grill is off (or off Wi-Fi).
            // Say so plainly instead of replaying its last known state.
            return cloud?.let { offline(it, cloudAge, nowMillis, beacon) }
        }

        val phase = when {
            b == null -> c!!.phase
            // The grill switches its panel off after ~10 idle minutes but keeps advertising, and its
            // beacon then still reads "idle". The cloud says what it is: poweredOFF. Trust that.
            b.phase == CookPhase.IDLE && c?.phase == CookPhase.OFF -> CookPhase.OFF
            // The beacon only knows idle/ignition/preheat/cooking; the cloud names the end of a cook.
            b.phase == CookPhase.IDLE && c != null && c.phase in setOf(CookPhase.RESTING, CookPhase.DONE) &&
                cloudAge != null && cloudAge < CLOUD_LIVE_MS -> c.phase
            b.phase == CookPhase.UNKNOWN && c != null -> c.phase
            else -> b.phase
        }
        // AWS refreshes `updatedAt` on every heartbeat but its telemetry only while a cook runs: an
        // idle grill keeps reporting the temperatures of its last cook (seen on ours: cloud 86 °C
        // while the beacon read 53 °C an hour after cooking). So cloud readings count only during
        // and right after a cook.
        val cloudLive = c != null && (phase.heating || phase == CookPhase.RESTING || phase == CookPhase.DONE)

        val secondsLeft = c?.let {
            val end = it.endTimeUtcSec
            if (end != null && phase.heating) maxOf(0L, end - nowMillis / 1000).toInt() else it.secondsLeft
        }

        val probes = (0..1).map { i ->
            val cp = c?.probes?.getOrNull(i)
            val bleTemp = b?.probesC?.getOrNull(i)
            ProbeView(
                tempC = bleTemp ?: cp?.tempC?.takeIf { cp.pluggedIn && cloudLive },
                targetC = cp?.targetC?.takeIf { cp.active },
                progress = cp?.progress?.takeIf { cp.active },
                source = when {
                    bleTemp != null -> Source.BLE
                    cp?.tempC != null && cp.pluggedIn && cloudLive -> Source.CLOUD
                    else -> null
                },
            )
        }

        // Without the cloud (BLE only), the reply to our own last command still tells mode, target
        // and time; its countdown runs on from when it arrived (the timer only runs once cooking).
        val snap = gatt?.takeIf { !cloudLive && it.state.active && phase.heating }
        val g = snap?.state
        val gattLeft = snap?.let {
            val elapsed = if (phase == CookPhase.COOKING) ((nowMillis - it.atMillis) / 1000).toInt() else 0
            maxOf(0, it.state.secondsLeft - elapsed)
        }

        val cloudGrill = c?.grillC?.takeIf { cloudLive }
        return GrillView(
            phase = phase,
            prompt = if (b != null) b.prompt else c?.prompt?.takeIf { cloudLive },
            lidOpen = b?.lidOpen ?: c?.lidOpen?.takeIf { cloudLive },
            mode = c?.mode?.takeIf { cloudLive } ?: g?.mode,
            setpoint = c?.setpoint?.takeIf { cloudLive && phase.heating } ?: g?.setpoint,
            secondsSet = c?.secondsSet?.takeIf { cloudLive } ?: g?.secondsSet,
            secondsLeft = secondsLeft?.takeIf { cloudLive && phase.heating } ?: gattLeft,
            smoke = c?.smoke?.takeIf { cloudLive },
            grillC = cloudGrill ?: b?.grillC,
            grillApprox = cloudGrill == null && b?.grillC != null,
            airC = c?.airC?.takeIf { cloudLive },
            smokeC = c?.smokeC?.takeIf { cloudLive },
            probes = probes,
            sources = buildSet {
                if (b != null) add(Source.BLE)
                if (c != null) add(Source.CLOUD)
            },
            beaconAgeMs = beacon?.let { nowMillis - it.atMillis },
            cloudUpdatedAgeMs = cloudAge,
            cloudConnected = cloud?.connected,
            rssi = c?.rssi,
            model = cloud?.model,
            serial = cloud?.serial,
        )
    }

    private fun offline(cloud: CloudReading, cloudAge: Long?, nowMillis: Long, beacon: BeaconReading?) = GrillView(
        phase = CookPhase.OFF, prompt = null, lidOpen = null, mode = null, setpoint = null, secondsSet = null,
        secondsLeft = null, smoke = null, grillC = null, grillApprox = false, airC = null, smokeC = null,
        probes = listOf(ProbeView(null, null, null, null), ProbeView(null, null, null, null)),
        sources = emptySet(),
        beaconAgeMs = beacon?.let { nowMillis - it.atMillis },
        cloudUpdatedAgeMs = cloudAge,
        cloudConnected = cloud.connected,
        rssi = null,
        model = cloud.model,
        serial = cloud.serial,
    )
}
