package com.example.mygrill.ui

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.example.mygrill.R
import com.example.mygrill.core.CookMode
import com.example.mygrill.core.CookRequest
import com.example.mygrill.core.CookSpecs
import com.example.mygrill.core.ModeSpec
import com.example.mygrill.core.SmokeRule
import com.example.mygrill.data.AutoStop
import com.example.mygrill.ui.theme.G
import com.example.mygrill.ui.theme.Numeric

/** The numbers of a cook the readout shows: heat, what ends it (a probe's target or a timer), and the cap. */
enum class PlanField { HEAT, END, CAP, OPTIONS, MODE }

/**
 * A cook being planned, held above the screen so the bottom bar's button can send it. Each mode
 * remembers its own heat, timer and cap while the owner flips between modes. What ends the cook
 * follows the probes until the owner picks: the first probe that reads, else the timer.
 */
@Stable
class PlannerState(initial: CookRequest?, initialAutoStop: AutoStop?, val editing: Boolean) {
    var mode by mutableStateOf(initial?.mode ?: CookMode.GRILL)
    private val temps = mutableStateMapOf<CookMode, Int>()
    private val minutes = mutableStateMapOf<CookMode, Int>()
    private val caps = mutableStateMapOf<CookMode, Int>()
    private var smokeOn by mutableStateOf(initial?.smoke ?: false)
    private var skip by mutableStateOf(false)
    /** "time", a probe key, or null: automatic. */
    private var sourceChoice by mutableStateOf(initialAutoStop?.probeKey ?: if (editing) "time" else null)
    private var targetC by mutableIntStateOf(initialAutoStop?.targetC ?: 56)
    /** The cell that owns the control; null = the natural one (the target when a probe ends the cook). */
    var focus by mutableStateOf<PlanField?>(null)
    /** What the owner changed. Mid-cook only these make an update worth sending. */
    val touched = mutableStateListOf<PlanField>()

    init {
        initial?.let {
            temps[it.mode] = it.temp
            val m = (it.seconds / 60).coerceAtLeast(1)
            if (initialAutoStop != null) caps[it.mode] = m else minutes[it.mode] = m
        }
    }

    private fun touch(f: PlanField) {
        if (f !in touched) touched += f
    }

    val spec: ModeSpec get() = CookSpecs.of(mode) ?: CookSpecs.all.first()
    private val minMinutes get() = if (editing) 1 else spec.minutesMin
    val minutesRange get() = minMinutes..spec.minutesMax

    var heat: Int
        get() = temps[mode]?.coerceIn(spec.tempMin, spec.tempMax) ?: spec.tempDefault
        set(v) { temps[mode] = v.coerceIn(spec.tempMin, spec.tempMax); touch(PlanField.HEAT) }

    /** Timer length when the timer ends the cook. */
    var mins: Int
        get() = minutes[mode]?.coerceIn(minutesRange) ?: spec.minutesDefault.coerceIn(minutesRange)
        set(v) { minutes[mode] = v.coerceIn(minutesRange); touch(PlanField.END) }

    /** Upper bound on a thermometer cook; the mode's maximum unless the owner dials it down. */
    var cap: Int
        get() = caps[mode]?.coerceIn(minutesRange) ?: spec.minutesMax
        set(v) { caps[mode] = v.coerceIn(minutesRange); touch(PlanField.CAP) }

    var target: Int
        get() = targetC
        set(v) { targetC = v.coerceIn(CookSpecs.PROBE_MIN, CookSpecs.PROBE_MAX); touch(PlanField.END) }

    var smoke: Boolean
        get() = when (spec.smoke) {
            SmokeRule.NEVER -> false
            SmokeRule.REQUIRED -> true
            SmokeRule.OPTIONAL -> smokeOn
        }
        set(v) { smokeOn = v; touch(PlanField.OPTIONS) }

    var skipPreheat: Boolean
        get() = skip
        set(v) { skip = v; touch(PlanField.OPTIONS) }

    fun source(probes: List<ProbeEntry>): String {
        val reading = probes.filter { it.tempC != null }.map { it.key }
        return when {
            !spec.probes -> "time"
            // A chosen probe that was unplugged no longer ends anything: fall back like a fresh plan.
            sourceChoice != null && (sourceChoice == "time" || sourceChoice in reading) -> sourceChoice!!
            else -> reading.firstOrNull() ?: "time"
        }
    }

    fun switchMode(m: CookMode) {
        if (m == mode) return
        mode = m
        focus = PlanField.HEAT
        if (editing) touch(PlanField.MODE)
    }

    fun pickSource(key: String) {
        sourceChoice = key
        touch(PlanField.END)
    }

    fun thermometer(probes: List<ProbeEntry>): Boolean = source(probes) != "time"

    fun probeKey(probes: List<ProbeEntry>): String = source(probes).takeIf { it != "time" } ?: "ninja0"

    fun focused(probes: List<ProbeEntry>): PlanField {
        val f = focus ?: if (thermometer(probes)) PlanField.END else PlanField.HEAT
        return if (f == PlanField.CAP && !thermometer(probes)) PlanField.END else f
    }

    fun request(probes: List<ProbeEntry>): CookRequest {
        val byProbe = thermometer(probes)
        val port = probeKey(probes).removePrefix("ninja").toIntOrNull()
        return CookRequest(
            mode = mode,
            temp = heat,
            seconds = (if (byProbe) cap else mins) * 60,
            smoke = smoke,
            // Ignored by the grill at start; the repository re-sends it once preheat begins.
            skipPreheat = !editing && skipPreheat,
            // The grill's own probe gets the target too, so the grill ends the cook even with the app gone.
            probeTargetsC = List(2) { i -> target.takeIf { byProbe && port == i } },
        )
    }

    fun autoStop(probes: List<ProbeEntry>, carryoverC: Int): AutoStop? =
        if (thermometer(probes)) AutoStop(probeKey(probes), target, target - carryoverC) else null
}

/**
 * The planner as a panel: the mode tiles, a readout of the cook's numbers (tap one), one control
 * for the selected number, and the two options as tiles. Mid-cook the mode can change too: the
 * grill takes the new program as a fresh cook command.
 */
@Composable
fun CookPlanner(p: PlannerState, probes: List<ProbeEntry>, unitsF: Boolean, carryoverC: Int) {
    val view = LocalView.current
    val spec = p.spec
    val byProbe = p.thermometer(probes)
    val key = p.probeKey(probes)
    val probe = probes.firstOrNull { it.key == key }
    val focus = p.focused(probes)
    Column(Modifier.animateContentSize(tween(220))) {
        ModeRow(p.mode) { view.haptic(Haptic.Tap); p.switchMode(it) }
        if (p.editing && PlanField.MODE in p.touched) {
            Text(stringResource(R.string.mode_switch_note), style = MaterialTheme.typography.bodySmall, color = G.heat, modifier = Modifier.padding(top = 8.dp))
        }
        VSpace(12)

        // ---- readout
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val heatC = if (spec.isLevel) CookSpecs.GRILL_LEVEL_C[p.heat - 1] else p.heat
            ReadoutCell(
                caption = stringResource(R.string.cell_heat),
                value = temp(heatC.toDouble(), unitsF, withUnit = false), numeric = heatC,
                sub = if (spec.isLevel) stringResource(levelName(p.heat)) else stringResource(R.string.cell_range, temp(spec.tempMin.toDouble(), unitsF, false), temp(spec.tempMax.toDouble(), unitsF, false)),
                selected = focus == PlanField.HEAT, dot = p.editing && PlanField.HEAT in p.touched,
            ) { p.focus = PlanField.HEAT }
            if (byProbe) {
                ReadoutCell(
                    caption = probe?.label ?: stringResource(R.string.finish_probe),
                    value = temp(p.target.toDouble(), unitsF, withUnit = false), numeric = p.target,
                    sub = if (probe?.tempC == null) stringResource(R.string.probe_unplugged) else donenessName(p.target) ?: stringResource(R.string.cell_custom),
                    selected = focus == PlanField.END, dot = p.editing && PlanField.END in p.touched,
                ) { p.focus = PlanField.END }
                ReadoutCell(
                    caption = stringResource(R.string.cell_cap),
                    value = hm(p.cap), numeric = p.cap,
                    sub = stringResource(if (p.cap >= spec.minutesMax) R.string.cap_max else R.string.cap_limit),
                    selected = focus == PlanField.CAP, dot = p.editing && PlanField.CAP in p.touched,
                ) { p.focus = PlanField.CAP }
            } else {
                ReadoutCell(
                    caption = stringResource(R.string.time_title),
                    value = hm(p.mins), numeric = p.mins,
                    sub = stringResource(if (p.editing) R.string.cell_time_left else R.string.cell_hm),
                    selected = focus == PlanField.END, dot = p.editing && PlanField.END in p.touched,
                ) { p.focus = PlanField.END }
            }
        }
        VSpace(14)

        // ---- the one control
        AnimatedContent(
            targetState = Triple(focus, byProbe, p.mode),
            transitionSpec = { fadeIn(tween(140)) togetherWith fadeOut(tween(90)) using SizeTransform(clip = false) },
            label = "zone",
        ) { (f, thermo, _) ->
            Column {
                when (f) {
                    PlanField.HEAT -> HeatControl(p, unitsF)
                    PlanField.END -> {
                        // The choice of what ends the cook exists only when a probe is actually plugged in.
                        val reading = probes.filter { it.tempC != null }
                        if (spec.probes && reading.isNotEmpty()) {
                            SourceChips(
                                reading.map { e -> e.key to e.label + " · " + temp(e.tempC, unitsF, withUnit = false) } +
                                    ("time" to stringResource(R.string.time_title)),
                                selected = p.source(probes),
                                onPick = { view.haptic(Haptic.Tap); p.pickSource(it) },
                                timerIcon = R.drawable.ic_timer, probeIcon = R.drawable.ic_thermo,
                            )
                            VSpace(10)
                        }
                        if (thermo) {
                            // One wheel: every degree, with the doneness names written on their degrees.
                            WheelPicker(
                                values = (CookSpecs.PROBE_MIN..CookSpecs.PROBE_MAX).toList(),
                                value = p.target,
                                text = { temp(it.toDouble(), unitsF, withUnit = false) },
                                note = { donenessName(it) },
                                onChange = { p.target = it },
                                marks = DONENESS.associate { it.second to G.ok },
                            )
                            VSpace(8)
                            PullLine(p.target - carryoverC, carryoverC, p.target, unitsF)
                        } else {
                            MinutesWheel(p.mins, p.minutesRange) { p.mins = it }
                        }
                    }
                    PlanField.CAP -> {
                        MinutesWheel(p.cap, p.minutesRange) { p.cap = it }
                        Text(
                            stringResource(R.string.cap_hint, hm(spec.minutesMax)),
                            style = MaterialTheme.typography.bodySmall, color = G.textSecondary, modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    PlanField.OPTIONS, PlanField.MODE -> Unit
                }
            }
        }

        // ---- options
        val showSmoke = spec.smoke != SmokeRule.NEVER
        val showSkip = !p.editing
        if (showSmoke || showSkip) {
            VSpace(14)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (showSmoke) {
                    OptionTile(
                        R.drawable.ic_mode_smoke, stringResource(R.string.woodfire),
                        stringResource(
                            when {
                                spec.smoke == SmokeRule.REQUIRED -> R.string.woodfire_always
                                p.smoke -> R.string.woodfire_on
                                else -> R.string.opt_off
                            },
                        ),
                        on = p.smoke, enabled = spec.smoke == SmokeRule.OPTIONAL,
                    ) { p.smoke = !p.smoke }
                }
                if (showSkip) {
                    OptionTile(
                        R.drawable.ic_skip, stringResource(R.string.skip_tile),
                        stringResource(if (p.skipPreheat) R.string.skip_on else R.string.opt_off),
                        on = p.skipPreheat, enabled = true,
                    ) { p.skipPreheat = !p.skipPreheat }
                }
            }
        }
    }
}

@Composable
private fun HeatControl(p: PlannerState, unitsF: Boolean) {
    val spec = p.spec
    if (spec.isLevel) {
        LevelTrack(
            (1..3).map { Triple(it, stringResource(levelName(it)), temp(CookSpecs.GRILL_LEVEL_C[it - 1].toDouble(), unitsF, withUnit = false)) },
            p.heat,
        ) { p.heat = it }
    } else {
        WheelPicker(
            values = (spec.tempMin..spec.tempMax step spec.tempStep).toList(),
            value = p.heat,
            text = { temp(it.toDouble(), unitsF, withUnit = false) },
            note = { if (it == spec.tempDefault) stringResource(R.string.default_word) else null },
            onChange = { p.heat = it },
        )
    }
}

/** Time on a wheel: every minute up to half an hour, then 5-minute steps, 15 past two hours. */
@Composable
private fun MinutesWheel(value: Int, range: IntRange, onChange: (Int) -> Unit) {
    val values = remember(range, value) {
        val steps = buildList {
            var m = range.first
            while (m <= range.last) {
                add(m)
                m += when {
                    m < 30 -> 1
                    m < 120 -> 5
                    else -> 15
                }
            }
            if (last() != range.last) add(range.last)
        }
        // The current value always sits on the wheel, even off the steps (a cook's time left).
        (steps + value.coerceIn(range)).distinct().sorted()
    }
    WheelPicker(values = values, value = value, text = { hm(it) }, note = { null }, onChange = onChange)
}

/** "Pull at 52° · +4° resting · target 56°": the live card's own words, in its colours. */
@Composable
private fun PullLine(pullC: Int, carryoverC: Int, targetC: Int, unitsF: Boolean) {
    val heat = G.heat
    val ok = G.ok
    val text = buildAnnotatedString {
        append(stringResource(R.string.pull_at))
        withStyle(SpanStyle(color = heat)) { append(temp(pullC.toDouble(), unitsF, withUnit = false)) }
        append(" · ")
        append(ltr("+" + delta(carryoverC.toDouble(), unitsF)))
        append(" ")
        append(stringResource(R.string.resting))
        append(" · ")
        append(stringResource(R.string.target_word))
        append(" ")
        withStyle(SpanStyle(color = ok)) { append(temp(targetC.toDouble(), unitsF, withUnit = false)) }
    }
    Text(text, style = MaterialTheme.typography.bodyMedium.merge(Numeric), color = G.textSecondary)
}

/**
 * Asked before any heat: what will happen, in three lines, and the safety note. Updating a running
 * cook asks too, since it can raise the heat.
 */
@Composable
fun CookConfirmDialog(
    request: CookRequest,
    autoStop: AutoStop?,
    probes: List<ProbeEntry>,
    unitsF: Boolean,
    editing: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = G.surface,
        icon = {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(22.dp)).background(G.heatContainer), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_flame), contentDescription = null, tint = G.heat, modifier = Modifier.size(22.dp))
            }
        },
        title = { Text(stringResource(if (editing) R.string.confirm_update_title else R.string.confirm_ignite_title), color = G.text) },
        text = {
            Column {
                SummaryRow(stringResource(R.string.sheet_mode), heatSummary(request, unitsF))
                HorizontalDivider(color = G.surfaceAlt)
                SummaryRow(stringResource(R.string.sum_finish), finishSummary(request, autoStop, probes, unitsF))
                HorizontalDivider(color = G.surfaceAlt)
                SummaryRow(stringResource(R.string.woodfire), stringResource(if (request.smoke) R.string.sum_on else R.string.sum_off))
                VSpace(12)
                Text(
                    stringResource(R.string.confirm_ignite_hint),
                    style = MaterialTheme.typography.bodyMedium, color = G.textSecondary,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(G.surfaceAlt).padding(12.dp),
                )
            }
        },
        confirmButton = {
            Button(onClick = onConfirm) { Text(stringResource(if (editing) R.string.cook_update else R.string.ignite_short)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = G.textSecondary, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.titleMedium.merge(Numeric), color = G.text)
    }
}

/** "Grill · High (265°)", "Roast · 180°". */
@Composable
fun heatSummary(r: CookRequest, unitsF: Boolean): String {
    val mode = stringResource(r.mode.label())
    return if (r.mode == CookMode.GRILL) {
        "$mode · ${stringResource(levelName(r.temp))} (${temp(CookSpecs.GRILL_LEVEL_C[r.temp - 1].toDouble(), unitsF, withUnit = false)})"
    } else {
        "$mode · ${temp(r.temp.toDouble(), unitsF)}"
    }
}

/** "Probe 1 · pull 52° · target 56°" or "0:25". */
@Composable
fun finishSummary(r: CookRequest, a: AutoStop?, probes: List<ProbeEntry>, unitsF: Boolean): String =
    if (a != null) {
        val name = probes.firstOrNull { it.key == a.probeKey }?.label.orEmpty()
        stringResource(R.string.sum_probe_pull, name, temp(a.pullC.toDouble(), unitsF, withUnit = false), temp(a.targetC.toDouble(), unitsF, withUnit = false))
    } else {
        hm(r.seconds / 60)
    }

// ------------------------------------------------------------------ parts

@Composable
private fun ModeRow(selected: CookMode, onPick: (CookMode) -> Unit) {
    Row(
        // Room around the tiles for their shadows.
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CookSpecs.all.forEach { s -> ModeTile(s.mode, s.mode == selected) { onPick(s.mode) } }
    }
}

@Composable
private fun ModeTile(mode: CookMode, selected: Boolean, onClick: () -> Unit) {
    val fg by animateColorAsState(if (selected) G.onPrimary else G.text, tween(180), label = "tileFg")
    val press = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Column(
        Modifier
            .widthIn(min = 72.dp)
            .heightIn(min = 64.dp)
            .control(RoundedCornerShape(16.dp), selected, press)
            .clickable(interactionSource = press, indication = androidx.compose.material3.ripple(), role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(painterResource(mode.icon()), contentDescription = null, tint = fg, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(4.dp))
        Text(stringResource(mode.label()), style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 1)
    }
}

/** Beef and lamb doneness as final temperatures after resting; the pull point follows from them. */
private val DONENESS = listOf(
    R.string.doneness_rare to 52, R.string.doneness_medium_rare to 56, R.string.doneness_medium to 60,
    R.string.doneness_medium_well to 65, R.string.doneness_well to 70,
)

@Composable
fun donenessName(targetC: Int): String? = DONENESS.firstOrNull { it.second == targetC }?.let { stringResource(it.first) }

private fun levelName(level: Int): Int = listOf(R.string.heat_lo, R.string.heat_med, R.string.heat_hi)[(level - 1).coerceIn(0, 2)]

/** Hours and minutes the way the grill writes them: 0:25, 4:00. Digits only, isolated left-to-right. */
fun hm(minutes: Int): String = ltr("%d:%02d".format(minutes / 60, minutes % 60))

/** "45 דק'" or "4:00 שעות": the digits isolated left-to-right, the Hebrew word left alone. */
@Composable
fun duration(minutes: Int): String =
    if (minutes < 60) stringResource(R.string.minutes_short, minutes)
    else stringResource(R.string.hours_minutes, hm(minutes))

@DrawableRes
fun CookMode.icon(): Int = when (this) {
    CookMode.GRILL -> R.drawable.ic_mode_grill
    CookMode.SMOKER -> R.drawable.ic_mode_smoke
    CookMode.AIR_CRISP -> R.drawable.ic_mode_air
    CookMode.BAKE -> R.drawable.ic_mode_bake
    CookMode.ROAST -> R.drawable.ic_mode_roast
    CookMode.DEHYDRATE -> R.drawable.ic_mode_dehydrate
    CookMode.REHEAT -> R.drawable.ic_mode_reheat
    else -> R.drawable.ic_flame
}
