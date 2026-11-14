package com.example.mygrill.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.example.mygrill.R
import com.example.mygrill.core.CookMode
import com.example.mygrill.core.CookPhase
import com.example.mygrill.core.Prompt
import kotlin.math.roundToInt

/**
 * Wraps a left-to-right fragment ("°C", "-55 dBm", "1:05:00") in a bidi isolate, so the RTL
 * layout doesn't reorder it into "C°" or "dBm 55-".
 */
fun ltr(s: String): String = "⁦$s⁩"

fun unit(unitsF: Boolean): String = ltr(if (unitsF) "°F" else "°C")

fun temp(c: Double?, unitsF: Boolean, withUnit: Boolean = true): String {
    if (c == null) return "—"
    val v = if (unitsF) (c * 9 / 5 + 32).roundToInt() else c.roundToInt()
    return ltr(if (withUnit) "$v°${if (unitsF) "F" else "C"}" else "$v°")
}

/** A temperature difference: converts the step, not the point (no +32). */
fun delta(c: Double, unitsF: Boolean): String {
    val v = if (unitsF) (c * 9 / 5).roundToInt() else c.roundToInt()
    return ltr("$v°")
}

fun countdown(seconds: Int?): String {
    if (seconds == null) return "—"
    val h = seconds / 3600
    val m = seconds % 3600 / 60
    val s = seconds % 60
    return ltr(if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s))
}

@Composable
fun ago(ms: Long?): String = when {
    ms == null -> stringResource(R.string.not_heard)
    ms < 5_000 -> stringResource(R.string.ago_now)
    ms < 60_000 -> stringResource(R.string.ago_seconds, (ms / 1000).toInt())
    ms < 3_600_000 -> stringResource(R.string.ago_minutes, (ms / 60_000).toInt())
    else -> stringResource(R.string.ago_hours, (ms / 3_600_000).toInt())
}

@StringRes
fun CookPhase.label(): Int = when (this) {
    CookPhase.OFF -> R.string.phase_off
    CookPhase.IDLE -> R.string.phase_idle
    CookPhase.IGNITION -> R.string.phase_ignition
    CookPhase.PREHEAT -> R.string.phase_preheat
    CookPhase.COOKING -> R.string.phase_cooking
    CookPhase.RESTING -> R.string.phase_resting
    CookPhase.DONE -> R.string.phase_done
    CookPhase.WAITING_LID -> R.string.phase_waiting_lid
    CookPhase.UNKNOWN -> R.string.phase_unknown
}

@StringRes
fun Prompt.label(): Int = when (this) {
    Prompt.ADD_FOOD -> R.string.prompt_add_food
    Prompt.FLIP_FOOD -> R.string.prompt_flip_food
    Prompt.DONE -> R.string.prompt_done
    Prompt.GET_FOOD -> R.string.prompt_get_food
}

@StringRes
fun CookMode.label(): Int = when (this) {
    CookMode.GRILL -> R.string.mode_grill
    CookMode.SMOKER -> R.string.mode_smoker
    CookMode.AIR_CRISP -> R.string.mode_air_crisp
    CookMode.BAKE -> R.string.mode_bake
    CookMode.ROAST -> R.string.mode_roast
    CookMode.BROIL -> R.string.mode_broil
    CookMode.DEHYDRATE -> R.string.mode_dehydrate
    CookMode.REHEAT -> R.string.mode_reheat
    CookMode.GRIDDLE -> R.string.mode_griddle
}

/** "AC000W012345678" -> "AC000W…678": enough to recognise, not to copy. */
fun masked(s: String?): String = when {
    s == null -> "—"
    s.length <= 9 -> s
    else -> "${s.take(6)}…${s.takeLast(3)}"
}

/** A remaining time as people say it: "12 דק'" under an hour, "1:20" (hours) beyond. */
@Composable
fun etaText(seconds: Int): String {
    val m = (seconds + 59) / 60
    return if (m < 60) stringResource(R.string.minutes_short, m) else ltr("%d:%02d".format(m / 60, m % 60))
}
