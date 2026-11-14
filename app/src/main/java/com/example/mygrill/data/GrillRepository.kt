package com.example.mygrill.data

import android.content.Context
import com.example.mygrill.core.BeaconReading
import com.example.mygrill.core.CloudReading
import com.example.mygrill.core.CookCommand
import com.example.mygrill.core.CookEvent
import com.example.mygrill.core.CookEvents
import com.example.mygrill.core.SmokeRule
import com.example.mygrill.core.CookPhase
import com.example.mygrill.core.CookRequest
import com.example.mygrill.core.CookSpecs
import com.example.mygrill.core.ExternalReading
import com.example.mygrill.core.GattSnapshot
import com.example.mygrill.core.Tempwise
import com.example.mygrill.core.GrillView
import com.example.mygrill.core.KeyBundle
import com.example.mygrill.core.Merge
import com.example.mygrill.core.Source
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/** Which channels feed the dashboard. Each one works on its own; AUTO merges both. */
enum class Channel { AUTO, CLOUD, BLE }

enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class CommandKind { START, UPDATE, STOP, SKIP_PREHEAT, WAKE }

/** The one command in flight (or just finished), for the controls' busy / done / failed states. */
sealed interface CommandStatus {
    val kind: CommandKind
    val at: Long

    data class Busy(override val kind: CommandKind, override val at: Long) : CommandStatus
    data class Done(override val kind: CommandKind, val via: Source, override val at: Long) : CommandStatus
    data class Failed(override val kind: CommandKind, val message: String, override val at: Long) : CommandStatus
}

sealed interface LoginPhase {
    data object Idle : LoginPhase
    data object Busy : LoginPhase
    data class Failed(val badCredentials: Boolean, val detail: String, val at: Long) : LoginPhase
}

data class UiState(
    val view: GrillView? = null,
    val channel: Channel = Channel.AUTO,
    val themeMode: ThemeMode = ThemeMode.LIGHT,
    val unitsF: Boolean = false,
    val onboarded: Boolean = false,
    val email: String? = null,
    val loggedIn: Boolean = false,
    val sessionExpired: Boolean = false,
    val keyLoaded: Boolean = false,
    val grillAddress: String? = null,
    val bleProblem: BleProblem? = null,
    val cloudError: String? = null,
    val refreshing: Boolean = false,
    val login: LoginPhase = LoginPhase.Idle,
    val command: CommandStatus? = null,
    /** At least one channel can deliver a command right now. */
    val canControl: Boolean = false,
    /** The grill's panel is off (or it isn't heard) and Bluetooth can try to switch it on. */
    val canWake: Boolean = false,
    /** The last cook we started, to prefill the cook sheet. */
    val lastRequest: CookRequest? = null,
    val autoStop: AutoStop? = null,
    /** Set when the app stopped the grill because the thermometer reached the pull point. */
    val autoStoppedAt: Long? = null,
    /** The cook that just ended, kept on screen until dismissed or a new cook starts. */
    val lastCook: CookSummary? = null,
    /** Preheat: the grill's reading when it began (the bar's start) and seconds left to the target. */
    val preheatFromC: Double? = null,
    val preheatEtaSec: Int? = null,
    /** A start was sent and the grill hasn't reported heating yet: the screen shows it starting. */
    val startingSince: Long? = null,
    /** The grill never reported heating after a start (shown once). */
    val startUnconfirmedAt: Long? = null,
    /**
     * The grill holds the cook until its lid is shut: it says so (the cloud's "waiting for the lid"),
     * or a start is pending while the lid reads open. The panel shows it; the app must too.
     */
    val waitingForLid: Boolean = false,
    /** A made-up grill is on screen (debug demo): no real problems to report. */
    val demo: Boolean = false,
    /** The thermometer cook that just ended at its pull point, for the alert's wording. */
    val lastAutoStop: AutoStop? = null,
    /** A cook is under way (or the grill asks for something): worth watching with the app closed. */
    val cookActive: Boolean = false,
    /** Alerts for the cook's moments: started, hot, flip, pull soon / now, done, off. */
    val notify: Boolean = true,
    /** The Tempwise base's slot 1 holds the white probe; the owner's has the black one there. */
    val extWhiteFirst: Boolean = false,
    /** How much the meat climbs after it leaves the grill; the pull point is the target minus this. */
    val carryoverC: Int = 4,
    /** Estimated seconds until the auto-stop probe reaches its pull point, from its recent rise. */
    val pullEtaSec: Int? = null,
    val external: ExternalState? = null,
    val nowMillis: Long = System.currentTimeMillis(),
)

/**
 * A cook that ends on a thermometer rather than a timer: when [probeKey] ("ninja0", "ninja1",
 * "ext0", ...) reaches [pullC] the app stops the grill. For the grill's own probes the grill also
 * gets the target itself; this is the backup, and the only path for an external thermometer.
 */
data class AutoStop(val probeKey: String, val targetC: Int, val pullC: Int)

/** How a cook ended, for the card that stays after it: what stopped it, when, and the meat then. */
data class CookSummary(
    val startedAt: Long?,
    val endedAt: Long,
    val reason: EndReason,
    val probeKey: String?,
    val pullC: Int?,
    val targetC: Int?,
    /** The probe's reading the moment the heat stopped: the start of the rest-time climb. */
    val probeAtEndC: Double?,
)

enum class EndReason { AUTO_STOP, MANUAL, FINISHED }

/** The owner's own wireless thermometer (Tempwise), independent of the grill's channels. */
data class ExternalState(
    val address: String,
    val name: String?,
    val connected: Boolean = false,
    val reading: ExternalReading? = null,
    val lastRaw: String? = null,
)

/**
 * Owns both channels and merges them into one [UiState]. Runs while anyone holds it: the open app,
 * or [MonitorService] during a cook, so the alerts and the thermometer auto-stop keep working with
 * the phone in a pocket.
 */
class GrillRepository private constructor(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("mygrill", Context.MODE_PRIVATE)
    private val secure = SecureStore(app)
    private val cloud = CloudClient()
    private val scanner = BeaconScanner(app)
    private val tempwiseLink = TempwiseLink(app)
    private var lastExternalLive = 0L
    private val link = GattCommandLink(app)
    private val notifier = Notifier(app)
    val explorer = BleExplorer(app)
    private var externalPaused = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var gattSnapshot: GattSnapshot? = null

    private var session: Session? = secure.get(KEY_SESSION)?.let(Session::fromJson)
    private var keys: KeyBundle? = secure.get(KEY_BUNDLE)?.let { runCatching { KeyBundle.parse(it) }.getOrNull() }
    private var beacon: BeaconReading? = null
    private var cloudReading: CloudReading? = null
    private var jobs = emptyList<Job>()
    private var started = false
    private val holders = mutableSetOf<String>()

    /** The previous merged view, alerts already sent this cook, and since when nothing has run. */
    private var lastView: GrillView? = null
    private val fired = mutableSetOf<CookEvent>()
    private var quietSince: Long? = null
    private var unheardSince: Long? = null
    private var startedAlertAt = 0L
    /** Skip preheat was asked for at start: the grill ignores it then, so it is re-sent once preheat begins. */
    private var skipWhenPreheating = false
    private var autoStopTriedAt = 0L
    private var autoStopFailures = 0
    private var cookStartedAt: Long? = null
    /** Debug builds: a made-up grill on screen. Commands and alerts are off while it's set. */
    private var demo: GrillView? = null
    private var cookProbeKey: String? = null
    private var manualStopAt = 0L

    private val _state = MutableStateFlow(
        UiState(
            channel = runCatching { Channel.valueOf(prefs.getString(PREF_CHANNEL, null) ?: "") }.getOrDefault(Channel.AUTO),
            themeMode = runCatching { ThemeMode.valueOf(prefs.getString(PREF_THEME, null) ?: "") }.getOrDefault(ThemeMode.LIGHT),
            unitsF = prefs.getBoolean(PREF_UNITS_F, false),
            notify = prefs.getBoolean(PREF_NOTIFY, true),
            carryoverC = prefs.getInt(PREF_CARRYOVER, 4),
            extWhiteFirst = prefs.getBoolean(PREF_EXT_WHITE_FIRST, false),
            onboarded = prefs.getBoolean(PREF_ONBOARDED, false),
            email = prefs.getString(PREF_EMAIL, null),
            loggedIn = session != null,
            keyLoaded = keys != null,
            grillAddress = prefs.getString(PREF_ADDRESS, null),
            external = prefs.getString(PREF_EXT_ADDRESS, null)?.let { ExternalState(it, prefs.getString(PREF_EXT_NAME, null)) },
        ),
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    // ------------------------------------------------------------------ lifecycle

    /** [who] needs live data ("ui", "monitor"); the channels run while anyone does. */
    fun hold(who: String) {
        holders += who
        if (started) return
        started = true
        restart()
    }

    fun release(who: String) {
        holders -= who
        if (holders.isNotEmpty()) return
        started = false
        jobs.forEach { it.cancel() }
        jobs = emptyList()
    }

    /** Re-evaluate permissions / Bluetooth / settings and restart whatever should run. */
    fun restart() {
        jobs.forEach { it.cancel() }
        val channel = _state.value.channel
        val problem = if (channel == Channel.CLOUD) null else scanner.problem(keys != null)
        _state.update { it.copy(bleProblem = problem, keyLoaded = keys != null, loggedIn = session != null) }
        if (!started) return
        jobs = buildList {
            add(scope.launch { tick() })
            if (channel != Channel.BLE && session != null) add(scope.launch { pollCloud() })
            if (channel != Channel.CLOUD && problem == null) add(scope.launch { listenBeacon() })
            val external = _state.value.external
            if (external != null && !externalPaused && scanner.problem(hasKey = true) == null) {
                add(scope.launch { listenExternal(external.address) })
            }
        }
        if (channel == Channel.BLE) setCloud(null)
        if (channel == Channel.CLOUD) setBeacon(null)
    }

    /** A restart polls the cloud at once and re-checks Bluetooth. */
    fun refreshNow() = restart()

    // ------------------------------------------------------------------ settings

    fun setChannel(channel: Channel) {
        prefs.edit().putString(PREF_CHANNEL, channel.name).apply()
        _state.update { it.copy(channel = channel) }
        restart()
    }

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putString(PREF_THEME, mode.name).apply()
        _state.update { it.copy(themeMode = mode) }
    }

    fun setUnitsF(f: Boolean) {
        prefs.edit().putBoolean(PREF_UNITS_F, f).apply()
        _state.update { it.copy(unitsF = f) }
    }

    fun setNotify(on: Boolean) {
        prefs.edit().putBoolean(PREF_NOTIFY, on).apply()
        _state.update { it.copy(notify = on) }
    }

    fun swapExternalColours() {
        val v = !_state.value.extWhiteFirst
        prefs.edit().putBoolean(PREF_EXT_WHITE_FIRST, v).apply()
        _state.update { it.copy(extWhiteFirst = v) }
    }

    fun setCarryover(c: Int) {
        val v = c.coerceIn(0, 10)
        prefs.edit().putInt(PREF_CARRYOVER, v).apply()
        _state.update { it.copy(carryoverC = v) }
    }

    /** The notification permission is asked once, on first open; after that only from settings. */
    fun shouldAskNotifications(): Boolean = !prefs.getBoolean(PREF_NOTIFY_ASKED, false)

    fun markNotificationsAsked() = prefs.edit().putBoolean(PREF_NOTIFY_ASKED, true).apply()

    /** Save a key bundle (and the grill address it was derived for). Returns the parse error, if any. */
    fun importKey(text: String, address: String?): String? {
        val parsed = runCatching { KeyBundle.parse(text) }.getOrElse { return it.message ?: "invalid" }
        secure.put(KEY_BUNDLE, text.trim())
        keys = parsed
        val addr = address?.trim()?.uppercase()?.takeIf { it.matches(MAC) }
        if (addr != null) prefs.edit().putString(PREF_ADDRESS, addr).apply()
        _state.update { it.copy(keyLoaded = true, grillAddress = addr ?: it.grillAddress) }
        restart()
        return null
    }

    // ------------------------------------------------------------------ account

    fun login(email: String, password: String) {
        if (_state.value.login == LoginPhase.Busy) return
        _state.update { it.copy(login = LoginPhase.Busy) }
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { cloud.login(email.trim(), password) } }
            result.onSuccess { s ->
                saveSession(s)
                // Signing in after a Bluetooth-only start means the cloud is wanted too.
                val channel = if (_state.value.channel == Channel.BLE) Channel.AUTO else _state.value.channel
                prefs.edit().putString(PREF_EMAIL, email.trim()).putBoolean(PREF_ONBOARDED, true)
                    .putString(PREF_CHANNEL, channel.name).apply()
                _state.update {
                    it.copy(login = LoginPhase.Idle, email = email.trim(), onboarded = true, sessionExpired = false,
                        cloudError = null, channel = channel)
                }
                restart()
            }.onFailure { err ->
                _state.update {
                    it.copy(login = LoginPhase.Failed(err is AuthException, err.message ?: err.javaClass.simpleName, System.currentTimeMillis()))
                }
            }
        }
    }

    /** First run without an account: Bluetooth only. */
    fun skipLogin() {
        prefs.edit().putBoolean(PREF_ONBOARDED, true).putString(PREF_CHANNEL, Channel.BLE.name).apply()
        _state.update { it.copy(onboarded = true, channel = Channel.BLE, login = LoginPhase.Idle) }
        restart()
    }

    fun logout() {
        saveSession(null)
        cloud.forget()
        prefs.edit().remove(PREF_EMAIL).apply()
        setCloud(null)
        _state.update { it.copy(email = null, cloudError = null, sessionExpired = false) }
        restart()
    }

    // ------------------------------------------------------------------ commands

    fun startCook(request: CookRequest, autoStop: AutoStop? = null) {
        if (_state.value.command is CommandStatus.Busy || _state.value.startingSince != null) return
        _state.update {
            it.copy(autoStop = autoStop, autoStoppedAt = null, lastAutoStop = null, lastCook = null,
                startingSince = System.currentTimeMillis(), startUnconfirmedAt = null)
        }
        skipWhenPreheating = request.skipPreheat
        cookProbeKey = autoStop?.probeKey
        autoStopTriedAt = 0L
        autoStopFailures = 0
        cook(CommandKind.START, request)
        // Called from a tap, so the app is in front: the one moment Android lets the watch start.
        MonitorService.start(app)
    }

    /** Re-issue the running cook with changes (temperature, remaining time, Woodfire, probes). */
    fun updateCook(request: CookRequest, autoStop: AutoStop? = _state.value.autoStop) {
        _state.update { it.copy(autoStop = autoStop) }
        cook(CommandKind.UPDATE, request)
    }

    /**
     * The owner gives up on a start the grill never took up: stop waiting, and send a Stop in case
     * it arrived after all (a Stop on an idle grill only wakes its display).
     */
    fun cancelStart() {
        if (_state.value.startingSince == null || _state.value.command is CommandStatus.Busy) return
        skipWhenPreheating = false
        _state.update { it.copy(startingSince = null, waitingForLid = false) }
        stopByOwner()
    }

    /** The owner's Stop (the thermometer's own stop goes through [checkAutoStop]). */
    fun stopByOwner() {
        manualStopAt = System.currentTimeMillis()
        stopCook()
    }

    fun dismissLastCook() = _state.update { it.copy(lastCook = null) }

    /** Show [name] from [com.example.mygrill.core.Demo] instead of the real grill (null: back to real). */
    fun setDemo(name: String?) {
        demo = name?.let { com.example.mygrill.core.Demo.view(it) }
        val now = System.currentTimeMillis()
        val stop = AutoStop("ninja0", 56, 56 - _state.value.carryoverC)
        _state.update {
            it.copy(
                autoStop = stop.takeIf { name == "preheat" || name == "cooking" },
                preheatFromC = 40.0.takeIf { name == "preheat" },
                preheatEtaSec = 230.takeIf { name == "preheat" },
                pullEtaSec = 690.takeIf { name == "cooking" },
                startingSince = when (name) { "starting" -> now - 21_000; "lid" -> now - 9_000; else -> null },
                waitingForLid = name == "lid",
                demo = demo != null,
                lastCook = if (name == "done") CookSummary(now - 43 * 60_000, now - 7 * 60_000, EndReason.AUTO_STOP, "ninja0", stop.pullC, 56, 52.4) else null,
            )
        }
        recompute()
    }

    fun stopCook() = execute(
        CommandKind.STOP,
        ble = CookCommand.bleStop(),
        viaCloud = { s -> cloud.stopCook(s, CookCommand.cloudStop()) },
        onSuccess = {
            _state.update { it.copy(lastRequest = null, autoStop = null) }
            gattSnapshot = null
        },
    )

    /** Reading of any probe by key: the grill's two ports, or the external thermometer's probes. */
    private fun probeTemp(key: String): Double? {
        val s = _state.value
        return when {
            key.startsWith("ninja") -> s.view?.probes?.getOrNull(key.removePrefix("ninja").toInt())?.tempC
            key.startsWith("ext") -> s.external?.takeIf { it.connected }?.reading?.probesC?.getOrNull(key.removePrefix("ext").toInt())
            else -> null
        }
    }

    /** Recent readings per probe, for the time-to-pull estimate: (millis, °C), one every 10 s, 10 min. */
    private val history = mutableMapOf<String, ArrayDeque<Pair<Long, Double>>>()

    private fun recordProbes(now: Long) {
        for (key in listOf("ninja0", "ninja1", "ext0", "ext1", GRILL_KEY)) {
            val t = (if (key == GRILL_KEY) _state.value.view?.grillC else probeTemp(key)) ?: continue
            val h = history.getOrPut(key) { ArrayDeque() }
            if (h.isEmpty() || now - h.last().first >= 10_000) h.addLast(now to t)
            while (h.isNotEmpty() && now - h.first().first > 10 * 60_000) h.removeFirst()
        }
    }

    /**
     * Seconds until [key] reaches [pullC], from a least-squares line through its last minutes.
     * Nothing until there are 3+ minutes of readings and the meat is actually warming (>0.1°/min):
     * a guess from a flat or noisy line would be worse than no number.
     */
    private fun eta(key: String, pullC: Int, minWindowMs: Long = 3 * 60_000L): Int? {
        val h = history[key] ?: return null
        if (h.size < 4 || h.last().first - h.first().first < minWindowMs) return null
        val t0 = h.first().first
        val xs = h.map { (it.first - t0) / 1000.0 }
        val ys = h.map { it.second }
        val mx = xs.average()
        val my = ys.average()
        val sxx = xs.sumOf { (it - mx) * (it - mx) }
        if (sxx == 0.0) return null
        val slope = xs.indices.sumOf { (xs[it] - mx) * (ys[it] - my) } / sxx // °C per second
        if (slope * 60 < 0.1) return null
        val remaining = pullC - ys.last()
        return if (remaining <= 0) 0 else (remaining / slope).toInt()
    }

    /** Stop the grill when a thermometer-driven cook reaches its pull point. Runs on every refresh. */
    private fun checkAutoStop() {
        recordProbes(System.currentTimeMillis())
        val a = _state.value.autoStop
        val v = _state.value.view
        val preheating = v?.phase == CookPhase.PREHEAT || v?.phase == CookPhase.IGNITION
        val target = v?.setpoint?.let { sp -> if (v.mode == com.example.mygrill.core.CookMode.GRILL && sp in 1..3) CookSpecs.GRILL_LEVEL_C[sp - 1] else sp }
        _state.update {
            it.copy(
                pullEtaSec = a?.let { s -> eta(s.probeKey, s.pullC) },
                // The grill climbs fast: a minute of readings is enough for its estimate.
                preheatEtaSec = if (preheating && target != null) eta(GRILL_KEY, target, 60_000L) else null,
            )
        }
        a ?: return
        val phase = _state.value.view?.phase ?: return
        if (phase == CookPhase.IDLE || phase == CookPhase.OFF || phase == CookPhase.DONE) {
            _state.update { it.copy(autoStop = null) } // the cook ended some other way
            return
        }
        if (!phase.heating || _state.value.command is CommandStatus.Busy) return
        val t = probeTemp(a.probeKey) ?: return
        if (t >= a.pullC - PULL_SOON_C) announce(CookEvent.PULL_SOON)
        if (t >= a.pullC) {
            // The target stays armed until a stop goes through: a failed stop (out of range, no
            // internet) is retried every few seconds rather than letting the grill keep heating.
            val now = System.currentTimeMillis()
            if (now - autoStopTriedAt < AUTO_STOP_RETRY_MS) return
            autoStopTriedAt = now
            if (_state.value.lastAutoStop != a) _state.update { it.copy(autoStoppedAt = now, lastAutoStop = a) }
            announce(CookEvent.PULL_NOW)
            stopCook()
        }
    }

    /**
     * Skip = the running cook re-sent with the skip flag while the grill is in that stage (sent with
     * the start it's ignored; seen on our grill and in the open-source integration). During Woodfire
     * ignition it skips the ignition (the cloud gets its own "skip ignition" key).
     */
    fun skipPreheat() {
        val current = currentRequest() ?: return fail(CommandKind.SKIP_PREHEAT, "unknown cook settings")
        val igniting = _state.value.view?.phase == CookPhase.IGNITION
        cook(CommandKind.SKIP_PREHEAT, current.copy(skipPreheat = true, skipIgnition = igniting))
    }

    /**
     * Switch the panel back on without touching the grill: a stop wakes it (it lights the display;
     * over Bluetooth verified on our grill 2026-10-19, through the cloud reported by the owner).
     * Never sent while a cook runs, where a stop would end it.
     */
    fun wake() {
        if (_state.value.view?.phase?.heating == true) return
        execute(CommandKind.WAKE, ble = CookCommand.bleStop(), viaCloud = { s -> cloud.stopCook(s, CookCommand.cloudStop()) }, wake = true)
    }

    /** The running cook as a request, from what we sent or else from what the grill reports. */
    fun currentRequest(): CookRequest? {
        val view = _state.value.view ?: return _state.value.lastRequest
        val mode = view.mode ?: return _state.value.lastRequest
        _state.value.lastRequest?.takeIf { it.mode == mode }?.let { last ->
            return last.copy(seconds = remainingSeconds(view) ?: last.seconds)
        }
        val spec = CookSpecs.of(mode) ?: return null
        val temp = if (spec.isLevel) CookSpecs.grillLevel(view.setpoint) else view.setpoint
        return CookRequest(
            mode = mode,
            temp = temp ?: spec.tempDefault,
            seconds = remainingSeconds(view) ?: spec.minutesDefault * 60,
            smoke = view.smoke ?: (spec.smoke == SmokeRule.REQUIRED),
            skipPreheat = view.phase != CookPhase.PREHEAT && view.phase != CookPhase.IGNITION,
            probeTargetsC = view.probes.map { it.targetC },
        )
    }

    /** The cook timer only starts after preheat: until then the full time is still ahead. */
    private fun remainingSeconds(view: GrillView): Int? = when (view.phase) {
        CookPhase.PREHEAT, CookPhase.IGNITION -> view.secondsSet
        else -> view.secondsLeft?.takeIf { it > 0 } ?: view.secondsSet
    }

    private fun cook(kind: CommandKind, request: CookRequest) {
        val ble = runCatching { CookCommand.ble(request) }.getOrElse { return fail(kind, it.message ?: "invalid") }
        val cloudCmd = CookCommand.cloud(request)
        execute(kind, ble = ble, viaCloud = { s -> cloud.startCook(s, cloudCmd) }, onSuccess = {
            _state.update { it.copy(lastRequest = request.copy(skipPreheat = false)) }
        })
    }

    /** Channels to try for a command, best first. AUTO prefers BLE while the grill is heard. */
    private fun routes(): List<Source> {
        val ble = keys != null && _state.value.grillAddress != null && scanner.hasPermission()
        val cloudOk = session != null
        val heard = beacon?.let { System.currentTimeMillis() - it.atMillis < Merge.BEACON_FRESH_MS } == true
        return when (_state.value.channel) {
            Channel.BLE -> listOfNotNull(Source.BLE.takeIf { ble })
            Channel.CLOUD -> listOfNotNull(Source.CLOUD.takeIf { cloudOk })
            Channel.AUTO -> buildList {
                if (ble && heard) add(Source.BLE)
                if (cloudOk) add(Source.CLOUD)
                if (ble && !heard) add(Source.BLE)
            }
        }
    }

    private fun execute(
        kind: CommandKind,
        ble: ByteArray?,
        viaCloud: ((Session) -> Unit)?,
        onSuccess: () -> Unit = {},
        wake: Boolean = false,
    ) {
        // One command at a time, end to end: a second tap while one is in flight is dropped.
        if (_state.value.command is CommandStatus.Busy) return
        if (demo != null) return fail(kind, "demo mode: nothing is sent")
        val candidates = if (wake) wakeRoutes() else routes()
        val routes = candidates.filter { (it == Source.BLE && ble != null) || (it == Source.CLOUD && viaCloud != null) }
        if (routes.isEmpty()) return fail(kind, "no channel can reach the grill")
        _state.update { it.copy(command = CommandStatus.Busy(kind, System.currentTimeMillis())) }
        scope.launch {
            var error: Throwable? = null
            for (route in routes) {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        when (route) {
                            Source.BLE -> {
                                val reply = link.send(_state.value.grillAddress!!, keys!!, ble!!)
                                if (reply != null) gattSnapshot = GattSnapshot(System.currentTimeMillis(), reply)
                            }
                            Source.CLOUD -> viaCloud!!(liveSession())
                        }
                    }
                }
                if (result.isSuccess) {
                    onSuccess()
                    settle(CommandStatus.Done(kind, route, System.currentTimeMillis()))
                    if (route == Source.CLOUD || _state.value.loggedIn) burstRefresh()
                    return@launch
                }
                error = result.exceptionOrNull()
            }
            if (kind == CommandKind.START) _state.update { it.copy(startingSince = null) }
            if (kind == CommandKind.STOP && _state.value.autoStop != null && ++autoStopFailures >= 2) announce(CookEvent.STOP_FAILED)
            settle(CommandStatus.Failed(kind, error?.message ?: "failed", System.currentTimeMillis()))
        }
    }

    /**
     * Routes for waking a panel that switched itself off: Bluetooth first while the grill is heard
     * advertising, the cloud while it is still online there; whichever is left after that.
     */
    private fun wakeRoutes(): List<Source> {
        val s = _state.value
        val ble = s.channel != Channel.CLOUD && keys != null && s.grillAddress != null && scanner.hasPermission()
        val cloudOk = s.channel != Channel.BLE && session != null && cloudReading?.connected == true
        val heard = beacon?.let { System.currentTimeMillis() - it.atMillis < Merge.BEACON_FRESH_MS } == true
        return buildList {
            if (ble && heard) add(Source.BLE)
            if (cloudOk) add(Source.CLOUD)
            if (ble && !heard) add(Source.BLE)
        }
    }

    private fun fail(kind: CommandKind, message: String) = settle(CommandStatus.Failed(kind, message, System.currentTimeMillis()))

    /** Show a result for a few seconds, then let the controls go back to normal. */
    private fun settle(status: CommandStatus) {
        _state.update { it.copy(command = status) }
        recompute()
        scope.launch {
            delay(RESULT_SHOW_MS)
            _state.update { if (it.command == status) it.copy(command = null) else it }
        }
    }

    /** After a command, look at the cloud sooner than the regular poll would. */
    private fun burstRefresh() {
        scope.launch {
            for (wait in listOf(2_000L, 4_000L, 6_000L)) {
                delay(wait)
                fetchCloudOnce()
            }
        }
    }

    private fun liveSession(): Session {
        val s = session ?: throw AuthException("not signed in")
        return if (s.expiresAtMs < System.currentTimeMillis()) cloud.refresh(s).also(::saveSession) else s
    }

    // ------------------------------------------------------------------ external thermometer

    fun setExternal(address: String, name: String?) {
        prefs.edit().putString(PREF_EXT_ADDRESS, address).putString(PREF_EXT_NAME, name).apply()
        _state.update { it.copy(external = ExternalState(address, name)) }
        restart()
    }

    fun clearExternal() {
        prefs.edit().remove(PREF_EXT_ADDRESS).remove(PREF_EXT_NAME).apply()
        _state.update { it.copy(external = null) }
        restart()
    }

    /** The finder talks to devices directly; keep our own link off the thermometer meanwhile. */
    fun pauseExternal(paused: Boolean) {
        externalPaused = paused
        restart()
    }

    /** The Tempwise base, passively: every advertisement is a fresh reading of both probes. */
    /**
     * Two ears on the base: its advertisements (every ~3 s, but silent while any phone holds its
     * link) and a read-only link of our own, read once a second, which shares the vendor app's link
     * when that one is up. Whichever is newer wins.
     */
    private suspend fun listenExternal(address: String) = coroutineScope {
        launch {
            scanner.tempwise(address)
                .catch { updateExternal { it.copy(connected = false) } }
                .collect { advert ->
                    val liveFresh = advert.atMillis - lastExternalLive < EXTERNAL_LIVE_FRESH_MS
                    updateExternal { it.copy(connected = true, reading = Tempwise.withAdvert(it.reading, advert, liveFresh)) }
                }
        }
        launch { linkExternal(address) }
    }

    private suspend fun linkExternal(address: String) {
        var wait = 2_000L
        while (currentCoroutineContext().isActive) {
            try {
                tempwiseLink.connect(address).collect { event ->
                    wait = 2_000L
                    val now = System.currentTimeMillis()
                    when (event) {
                        is TempwiseEvent.Live -> Tempwise.parseLive(event.value)?.let { live ->
                            lastExternalLive = now
                            updateExternal { it.copy(connected = true, reading = Tempwise.withLive(it.reading, live, now)) }
                        }
                        is TempwiseEvent.Battery -> updateExternal { it.copy(reading = Tempwise.withBattery(it.reading, event.value)) }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
            delay(wait)
            wait = (wait * 2).coerceAtMost(30_000L)
        }
    }

    /** The base goes quiet when it's switched off or out of range: stop trusting its last reading. */
    private fun ageExternal(now: Long) {
        val e = _state.value.external ?: return
        if (e.connected && (e.reading == null || now - e.reading.atMillis > EXTERNAL_FRESH_MS)) updateExternal { it.copy(connected = false) }
    }

    private fun updateExternal(change: (ExternalState) -> ExternalState) {
        _state.update { s -> s.copy(external = s.external?.let(change)) }
    }

    // ------------------------------------------------------------------ channels

    private suspend fun tick() {
        while (scope.isActive) {
            recompute()
            delay(1_000)
        }
    }

    private suspend fun pollCloud() {
        while (scope.isActive) {
            fetchCloudOnce()
            delay(CLOUD_POLL_MS)
        }
    }

    private suspend fun fetchCloudOnce() {
        val s = session ?: return
        _state.update { it.copy(refreshing = true) }
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val live = if (s.expiresAtMs < System.currentTimeMillis()) cloud.refresh(s) else s
                if (live !== s) saveSession(live)
                try {
                    cloud.fetchGrill(live, System.currentTimeMillis())
                } catch (e: AuthException) {
                    // A rejected token mid-life: renew once and retry.
                    val renewed = cloud.refresh(live).also(::saveSession)
                    cloud.fetchGrill(renewed, System.currentTimeMillis())
                }
            }
        }
        result.onSuccess { reading ->
            setCloud(reading)
            _state.update { it.copy(refreshing = false, cloudError = null) }
        }.onFailure { err ->
            when (err) {
                is AuthException -> {
                    saveSession(null)
                    _state.update { it.copy(refreshing = false, sessionExpired = true, cloudError = null) }
                    restart()
                }
                is IOException -> _state.update { it.copy(refreshing = false, cloudError = err.message ?: "network") }
                else -> _state.update { it.copy(refreshing = false, cloudError = err.message ?: err.javaClass.simpleName) }
            }
        }
    }

    private suspend fun listenBeacon() {
        val k = keys ?: return
        scanner.readings(k.beacon, _state.value.grillAddress)
            .catch { _state.update { s -> s.copy(bleProblem = scanner.problem(keys != null)) } }
            .collect { (address, reading) ->
                if (_state.value.grillAddress == null) {
                    prefs.edit().putString(PREF_ADDRESS, address).apply()
                    _state.update { it.copy(grillAddress = address) }
                }
                setBeacon(reading)
            }
    }

    private fun setBeacon(reading: BeaconReading?) {
        beacon = reading
        recompute()
    }

    private fun setCloud(reading: CloudReading?) {
        cloudReading = reading
        recompute()
    }

    private fun recompute() {
        val now = System.currentTimeMillis()
        ageExternal(now)
        val view = demo ?: Merge.view(beacon, cloudReading, now, gattSnapshot)
        // No commands into the void: a grill that is off or unheard can't take one.
        val reachable = view != null && view.phase != CookPhase.OFF
        // A stop switches a self-switched-off panel back on, over Bluetooth or the cloud.
        val canWake = !reachable && wakeRoutes().isNotEmpty()
        _state.update {
            it.copy(view = view, nowMillis = now, canControl = reachable && (demo != null || routes().isNotEmpty()), canWake = canWake,
                cookActive = demo == null && cookActive(view, now))
        }
        if (demo != null) return
        trackCook(lastView, view, now)
        CookEvents.between(lastView, view).forEach(::announce)
        if (view != null) lastView = view
        resolveStart(view, now)
        val lid = view?.phase == CookPhase.WAITING_LID || (_state.value.startingSince != null && view?.lidOpen == true)
        if (lid != _state.value.waitingForLid) {
            if (lid) announce(CookEvent.LID_OPEN)
            _state.update { it.copy(waitingForLid = lid) }
        }
        if (skipWhenPreheating && view?.phase == CookPhase.PREHEAT && _state.value.command !is CommandStatus.Busy) {
            skipWhenPreheating = false
            skipPreheat()
        }
        checkAutoStop()
    }

    /**
     * Notes when a cook begins (and the grill's reading as preheat starts, for the bar) and, when the
     * heat stops, writes the summary that stays on screen: what stopped it and the meat at that moment.
     */
    private fun trackCook(prev: GrillView?, now: GrillView?, at: Long) {
        if (prev == null || now == null) return
        fun live(p: CookPhase) = p.heating || p == CookPhase.WAITING_LID
        val warming = { p: CookPhase -> p == CookPhase.PREHEAT || p == CookPhase.IGNITION }
        if (!live(prev.phase) && live(now.phase)) cookStartedAt = at
        if (!warming(prev.phase) && warming(now.phase)) _state.update { it.copy(preheatFromC = now.grillC) }
        if (live(prev.phase) && !live(now.phase)) {
            val s = _state.value
            val auto = s.lastAutoStop?.takeIf { s.autoStoppedAt != null && at - s.autoStoppedAt < 3 * 60_000 }
            val key = auto?.probeKey ?: s.autoStop?.probeKey ?: cookProbeKey
            val target = auto ?: s.autoStop
            _state.update {
                it.copy(
                    preheatFromC = null,
                    lastCook = CookSummary(
                        startedAt = cookStartedAt,
                        endedAt = at,
                        reason = when {
                            auto != null -> EndReason.AUTO_STOP
                            at - manualStopAt < 2 * 60_000 -> EndReason.MANUAL
                            else -> EndReason.FINISHED
                        },
                        probeKey = key,
                        pullC = target?.pullC,
                        targetC = target?.targetC,
                        probeAtEndC = key?.let(::probeTemp),
                    ),
                )
            }
            cookStartedAt = null
        }
    }

    /** The start in flight ends when the grill reports heating, or after a while without that. */
    private fun resolveStart(view: GrillView?, now: Long) {
        val since = _state.value.startingSince ?: return
        val phase = view?.phase
        when {
            phase != null && (phase.heating || phase == CookPhase.WAITING_LID) -> _state.update { it.copy(startingSince = null) }
            // An open lid holds the start for as long as the grill itself waits; it isn't a silent grill.
            now - since > (if (view?.lidOpen == true) LID_WAIT_MS else START_CONFIRM_MS) && _state.value.command !is CommandStatus.Busy -> {
                skipWhenPreheating = false
                _state.update { it.copy(startingSince = null, startUnconfirmedAt = now) }
            }
        }
    }

    /**
     * A cook runs, or the grill asks for something. While the grill isn't heard the last answer
     * stands (Bluetooth drops for a minute all the time), for up to half an hour.
     */
    private fun cookActive(view: GrillView?, now: Long): Boolean {
        if (view == null) {
            val since = unheardSince ?: now.also { unheardSince = it }
            return _state.value.cookActive && now - since < UNHEARD_GIVE_UP_MS
        }
        unheardSince = null
        val active = view.phase.heating || view.phase == CookPhase.WAITING_LID || view.phase == CookPhase.RESTING ||
            view.prompt != null || _state.value.autoStop != null
        // A new cook may alert again: forget what was sent once the grill has been quiet for a minute.
        if (active) quietSince = null else {
            val since = quietSince ?: now.also { quietSince = it }
            if (now - since > QUIET_RESET_MS) fired.clear()
        }
        return active
    }

    /** One alert per moment per cook, and only if the owner wants them. A start opens a new cook. */
    private fun announce(event: CookEvent) {
        if (event == CookEvent.STARTED) {
            val now = System.currentTimeMillis()
            if (now - startedAlertAt < RESTART_ALERT_GAP_MS) return
            startedAlertAt = now
            fired.clear()
        }
        if (!fired.add(event)) return
        if (_state.value.notify) notifier.alert(event, _state.value)
    }

    private fun saveSession(s: Session?) {
        session = s
        secure.put(KEY_SESSION, s?.toJson())
        _state.update { it.copy(loggedIn = s != null) }
    }

    companion object {
        private const val CLOUD_POLL_MS = 10_000L
        private const val RESULT_SHOW_MS = 5_000L
        private const val EXTERNAL_RETRY_MS = 5_000L
        /** The base advertises every ~3 s; half a minute without one means it's off or away. */
        private const val EXTERNAL_LIVE_FRESH_MS = 5_000L
        private const val EXTERNAL_FRESH_MS = 30_000L
        private const val PREF_EXT_WHITE_FIRST = "ext_white_first"
        private const val PREF_EXT_ADDRESS = "external_address"
        private const val PREF_EXT_NAME = "external_name"
        private const val PREF_CHANNEL = "channel"
        private const val PREF_THEME = "theme"
        private const val PREF_UNITS_F = "units_f"
        private const val PREF_NOTIFY = "notify"
        private const val PREF_CARRYOVER = "carryover_c"
        private const val PREF_NOTIFY_ASKED = "notify_asked"
        private const val PULL_SOON_C = 3
        private const val QUIET_RESET_MS = 60_000L
        private const val RESTART_ALERT_GAP_MS = 2 * 60_000L
        private const val AUTO_STOP_RETRY_MS = 8_000L
        private const val GRILL_KEY = "grill"
        /** From the tap: up to 20 s of cloud confirmation plus the grill's own first report. */
        private const val START_CONFIRM_MS = 35_000L
        private const val LID_WAIT_MS = 10 * 60_000L
        private const val UNHEARD_GIVE_UP_MS = 30 * 60_000L
        private const val PREF_ONBOARDED = "onboarded"
        private const val PREF_EMAIL = "email"
        private const val PREF_ADDRESS = "grill_address"
        private const val KEY_SESSION = "session"
        private const val KEY_BUNDLE = "key_bundle"
        private val MAC = Regex("^([0-9A-F]{2}:){5}[0-9A-F]{2}$")

        @Volatile private var instance: GrillRepository? = null
        fun get(context: Context): GrillRepository =
            instance ?: synchronized(this) { instance ?: GrillRepository(context).also { instance = it } }
    }
}
