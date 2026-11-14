package com.example.mygrill.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.example.mygrill.R
import com.example.mygrill.core.Source
import com.example.mygrill.ui.theme.G
import com.example.mygrill.ui.theme.LiveNumeral
import com.example.mygrill.ui.theme.Numeric
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** Any probe the app can read: the grill's two ports and the owner's Tempwise probes, alike. */
data class ProbeEntry(val key: String, val label: String, val tempC: Double?, val targetC: Int?, val source: Source?)

/**
 * Every probe in one short row: name and reading, nothing more. The grill's two ports always show
 * (so an unplugged one says so); a tile to add a Tempwise when none is set up.
 */
@Composable
fun ProbeStrip(probes: List<ProbeEntry>, unitsF: Boolean, onAddThermometer: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        probes.forEach { p ->
            Column(
                Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(16.dp)).background(G.surface).padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(R.drawable.ic_thermo), contentDescription = null, tint = G.textMuted, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(p.label, style = MaterialTheme.typography.labelMedium, color = G.textSecondary, maxLines = 1)
                }
                if (p.tempC != null) {
                    Text(temp(p.tempC, unitsF, withUnit = false), style = MaterialTheme.typography.headlineSmall.merge(Numeric), color = G.text)
                } else {
                    Text(stringResource(R.string.probe_unplugged), style = MaterialTheme.typography.bodyMedium, color = G.textMuted, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
        if (onAddThermometer != null) {
            Column(
                Modifier.weight(0.75f).fillMaxHeight().heightIn(min = 64.dp).clip(RoundedCornerShape(16.dp)).background(G.surface.copy(alpha = 0.55f))
                    .clickable(onClick = onAddThermometer).padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(painterResource(R.drawable.ic_plus), contentDescription = null, tint = G.primary, modifier = Modifier.size(16.dp))
                Text(stringResource(R.string.ext_title), style = MaterialTheme.typography.labelLarge, color = G.primary)
            }
        }
    }
}

/**
 * The probe a cook is riding on: the reading rolls to each new value, then the pull point and the
 * target, a track that shows how close, and how long until it's time to pull.
 */
@Composable
fun ProbeLiveCard(entry: ProbeEntry, unitsF: Boolean, carryoverC: Int, etaSec: Int?, doneness: String?, footer: String? = null) {
    val reading = entry.tempC
    val target = entry.targetC
    val pull = target?.minus(carryoverC)
    val reached = target != null && reading != null && reading >= target
    val pulled = pull != null && reading != null && reading >= pull
    Card(padding = 16) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                listOfNotNull(entry.label, doneness).joinToString(" · "),
                style = MaterialTheme.typography.labelLarge, color = G.textSecondary, modifier = Modifier.weight(1f),
            )
            when {
                reached -> Pill(stringResource(R.string.probe_reached), G.okContainer, G.onOkContainer)
                pulled -> Pill(stringResource(R.string.probe_pull_now), G.heatContainer, G.onHeatContainer)
                // Blue, not green: green is kept for "reached".
                etaSec != null -> Pill(stringResource(R.string.eta_short, etaText(etaSec)), G.primaryContainer, G.onPrimaryContainer)
            }
        }
        VSpace(4)
        Row(verticalAlignment = Alignment.Bottom) {
            if (reading == null) {
                Text(stringResource(R.string.probe_unplugged), style = MaterialTheme.typography.headlineSmall, color = G.textMuted)
            } else {
                val shown = if (unitsF) (reading * 9 / 5 + 32).roundToInt() else reading.roundToInt()
                AnimatedContent(
                    targetState = shown,
                    transitionSpec = {
                        val up = targetState > initialState
                        (slideInVertically(tween(260)) { if (up) it / 2 else -it / 2 } + fadeIn(tween(260))) togetherWith
                            (slideOutVertically(tween(200)) { if (up) -it / 2 else it / 2 } + fadeOut(tween(200)))
                    },
                    label = "numeral",
                ) { value -> Text(ltr("$value°"), style = LiveNumeral, color = if (reached) G.ok else G.text) }
            }
            Spacer(Modifier.width(12.dp))
            if (target != null && pull != null) {
                Text(
                    stringResource(R.string.pull_and_target, temp(pull.toDouble(), unitsF, withUnit = false), temp(target.toDouble(), unitsF, withUnit = false)),
                    style = MaterialTheme.typography.bodyMedium.merge(Numeric), color = G.textSecondary, modifier = Modifier.padding(bottom = 8.dp),
                )
            } else {
                Text(stringResource(R.string.probe_no_target), style = MaterialTheme.typography.bodyMedium, color = G.textSecondary, modifier = Modifier.padding(bottom = 8.dp))
            }
        }
        if (target != null && pull != null && reading != null) {
            VSpace(10)
            DonenessTrack(reading, pull, target, unitsF)
        }
        if (footer != null) Text(footer, style = MaterialTheme.typography.labelMedium.merge(Numeric), color = G.textMuted, modifier = Modifier.padding(top = 6.dp))
    }
}

/**
 * Reading against pull point and target on one line. The window starts 35° under the target so
 * the last stretch, the part that matters, fills most of the width. Fills right-to-left in RTL.
 */
@Composable
private fun DonenessTrack(tempC: Double, pullC: Int, targetC: Int, unitsF: Boolean) {
    val start = targetC - 35.0
    val end = targetC + 5.0
    fun frac(v: Double) = ((v - start) / (end - start)).toFloat().coerceIn(0f, 1f)
    val fill by animateFloatAsState(frac(tempC), tween(500), label = "track")
    val color by animateColorAsState(
        when {
            tempC >= targetC -> G.ok
            tempC >= pullC -> G.heat
            else -> G.heatFill
        },
        tween(400), label = "trackColor",
    )
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val track = G.track
    val pullColor = G.heat
    val targetColor = G.ok
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val widthPx = with(LocalDensity.current) { maxWidth.toPx() }
        Column {
            Canvas(Modifier.fillMaxWidth().height(10.dp)) {
                fun x(f: Float) = if (rtl) size.width * (1 - f) else size.width * f
                val h = size.height
                drawRoundRect(track, size = Size(size.width, h), cornerRadius = CornerRadius(h / 2))
                val fx = x(fill)
                val left = if (rtl) fx else 0f
                drawRoundRect(color, Offset(left, 0f), Size(size.width * fill, h), CornerRadius(h / 2))
                for ((v, c) in listOf(pullC to pullColor, targetC to targetColor)) {
                    val mx = x(frac(v.toDouble()))
                    drawRoundRect(c, Offset(mx - 1.5.dp.toPx(), -4.dp.toPx()), Size(3.dp.toPx(), h + 8.dp.toPx()), CornerRadius(1.5.dp.toPx()))
                }
            }
        }
    }
}

/**
 * A timed cook's lead: the countdown as the screen's big number, the clock time it ends, and a bar
 * of how much has gone.
 */
@Composable
fun TimeCard(secondsLeft: Int?, secondsSet: Int?, nowMillis: Long) {
    Card(padding = 16) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_timer), contentDescription = null, tint = G.textMuted, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.time_title), style = MaterialTheme.typography.labelLarge, color = G.textSecondary, modifier = Modifier.weight(1f))
            if (secondsLeft != null) {
                val end = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(nowMillis + secondsLeft * 1000L))
                Text(stringResource(R.string.ends_at, ltr(end)), style = MaterialTheme.typography.labelLarge.merge(Numeric), color = G.textSecondary)
            }
        }
        VSpace(4)
        // The grill's own clock: hours and minutes (0:06 is six minutes, 6:00 six hours).
        val minutes = secondsLeft?.let { (it + 59) / 60 }
        Text(minutes?.let { hm(it) } ?: "—", style = LiveNumeral, color = G.text)
        minutes?.let { Text(stringResource(R.string.left_caption, duration(it)), style = MaterialTheme.typography.bodyMedium, color = G.textSecondary) }
        val done = if (secondsLeft != null && secondsSet != null && secondsSet > 0) 1f - secondsLeft / secondsSet.toFloat() else null
        if (done != null) {
            VSpace(10)
            Bar(done.coerceIn(0f, 1f), G.primary)
        }
    }
}

/** A plain progress bar that fills from the reading direction's start. */
@Composable
fun Bar(fraction: Float, color: Color) {
    val f by animateFloatAsState(fraction, tween(500), label = "bar")
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val track = G.track
    Canvas(Modifier.fillMaxWidth().height(8.dp)) {
        val h = size.height
        drawRoundRect(track, size = size, cornerRadius = CornerRadius(h / 2))
        val w = size.width * f
        drawRoundRect(color, Offset(if (rtl) size.width - w else 0f, 0f), Size(w, h), CornerRadius(h / 2))
    }
}
