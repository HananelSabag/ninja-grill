package com.example.mygrill.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.mygrill.R
import com.example.mygrill.core.CookMode
import com.example.mygrill.core.CookPhase
import com.example.mygrill.core.CookSpecs
import com.example.mygrill.core.GrillView
import com.example.mygrill.ui.theme.G
import com.example.mygrill.ui.theme.Numeric
import kotlin.math.PI
import kotlin.math.sin

/**
 * Both grill pictures (res/drawable-nodpi/grill_closed / grill_open: our own drawings, or a local
 * build's photos) share one canvas (720×715) at one scale, aligned on the control panel's display,
 * so opening the lid never resizes the grill. The fractions below are that canvas's display window.
 */
private const val PHOTO_ASPECT = 720f / 715f
private const val HEADROOM = 168f / 715f
private const val LCD_LEFT = 0.4167f
private const val LCD_TOP = 0.8084f
private const val LCD_RIGHT = 0.5806f
private const val LCD_BOTTOM = 0.8657f
/** The mode LEDs (Smoker, Grill, Air fry, Roast, Bake, Dehydrate, Reheat) and the Woodfire flame. */
private const val LED_LEFT = 0.3375f
private const val LED_RIGHT = 0.3500f
private val LED_ROWS = listOf(0.8120f, 0.8201f, 0.8282f, 0.8364f, 0.8445f, 0.8529f, 0.8613f)
private const val FLAME_LEFT = 0.3680f
private const val FLAME_TOP = 0.8085f
private const val FLAME_RIGHT = 0.4000f
private const val FLAME_BOTTOM = 0.8630f

/** The program the panel shows while the grill waits (the one being put together in the app). */
data class PanelPlan(val mode: CookMode, val heat: String, val time: String, val smoke: Boolean)

/**
 * The grill at the top of the screen, looking like it is: grey and dim when off, lit when on,
 * glowing with heat rising off the hood while it heats (smoke when Woodfire is on), the open-lid
 * photo when the lid opens. Its display shows what the real one does: the heat and the time left.
 */
@Composable
fun GrillHero(view: GrillView?, unitsF: Boolean, canWake: Boolean, plan: PanelPlan? = null) {
    val phase = view?.phase
    val asleep = view == null || phase == CookPhase.OFF
    val heating = phase?.heating == true
    val saturation by animateFloatAsState(if (asleep) 0f else 1f, tween(700), label = "sat")
    val alpha by animateFloatAsState(if (asleep) 0.55f else 1f, tween(700), label = "alpha")
    val filter = if (saturation < 1f) ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(saturation) }) else null

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth(0.78f).aspectRatio(PHOTO_ASPECT)) {
            HeatAura(heating = heating, smoke = view?.smoke == true, modifier = Modifier.matchParentSize())
            // Photo and the panel drawn over it fade together, so a sleeping grill dims as one piece.
            Box(Modifier.matchParentSize().alpha(alpha)) {
                // The crossfade fills the box itself: its own wrapper would otherwise size to nothing.
                Crossfade(targetState = view?.lidOpen == true, modifier = Modifier.matchParentSize(), animationSpec = tween(380), label = "lid") { open ->
                    Image(
                        painterResource(if (open) R.drawable.grill_open else R.drawable.grill_closed),
                        contentDescription = null,
                        contentScale = ContentScale.FillBounds,
                        colorFilter = filter,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                GrillDisplay(view, asleep, plan, unitsF, Modifier.matchParentSize())
            }
        }
        VSpace(6)
        val (label, container, content) = phaseStyle(view, unitsF)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Pill(label, container, content, dot = content, pulsing = heating)
            if (view?.lidOpen == true) {
                Spacer(Modifier.width(8.dp))
                Pill(stringResource(R.string.lid_open), G.heatContainer, G.onHeatContainer, icon = R.drawable.ic_lid)
            }
        }
        VSpace(6)
        when {
            view == null -> Hint(stringResource(R.string.phase_searching_hint))
            phase == CookPhase.OFF -> Hint(stringResource(if (canWake) R.string.grill_off_hint_wake else R.string.grill_off_hint))
        }
    }
}

/** A cook is on: heating, waiting for the lid, resting or just done. */
fun running(phase: CookPhase?): Boolean = phase != null &&
    (phase.heating || phase == CookPhase.WAITING_LID || phase == CookPhase.RESTING || phase == CookPhase.DONE)

/**
 * Behind the photo: a warm glow that breathes while the grill heats, and three wavy lines rising
 * off the hood into the space above it (grey smoke instead when Woodfire is on). Flat otherwise.
 */
@Composable
private fun HeatAura(heating: Boolean, smoke: Boolean, modifier: Modifier) {
    val on by animateFloatAsState(if (heating) 1f else 0f, tween(900), label = "aura")
    val t = rememberInfiniteTransition(label = "heat")
    val rise by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2600, easing = LinearEasing)), label = "rise")
    val breathe by t.animateFloat(0.75f, 1f, infiniteRepeatable(tween(1800), RepeatMode.Reverse), label = "breathe")
    val glow = G.heatFill
    val wave = if (smoke) Color(0xFF8A96A3) else G.heatFill
    Canvas(modifier) {
        if (on <= 0f) return@Canvas
        val w = size.width
        val h = size.height
        val c = Offset(w * 0.5f, h * 0.55f)
        drawCircle(Brush.radialGradient(listOf(glow.copy(alpha = 0.30f * on * breathe), Color.Transparent), c, w * 0.62f), w * 0.62f, c)
        // Rising lines: each one climbs from the top of the hood and fades as it goes.
        val top = h * 0.02f
        val bottom = h * (HEADROOM + 0.06f)
        val span = bottom - top
        for (i in 0..2) {
            val x0 = w * (0.38f + 0.12f * i)
            val phase = (rise + i * 0.33f) % 1f
            val path = Path()
            val steps = 24
            for (s in 0..steps) {
                val f = s / steps.toFloat()
                val y = bottom - span * f - span * 0.25f * phase
                val x = x0 + sin((f * 2.2f + phase * 2f + i) * PI.toFloat()) * w * 0.018f
                if (s == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            val fade = (1f - phase) * on
            drawPath(path, wave.copy(alpha = 0.45f * fade), style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
        }
    }
}

/**
 * Covers the photo's printed digits with a live display: heat on the left, time on the right, the
 * way the grill writes them (HI, 180, PrE, 0:23). Dark when the panel is off.
 */
@Composable
private fun GrillDisplay(view: GrillView?, asleep: Boolean, plan: PanelPlan?, unitsF: Boolean, modifier: Modifier) {
    // Photo coordinates run left to right: anchor at the absolute top-left, not RTL's start corner.
    BoxWithConstraints(modifier, contentAlignment = AbsoluteAlignment.TopLeft) {
        val density = LocalDensity.current
        val left = maxWidth * LCD_LEFT
        val top = maxHeight * LCD_TOP
        val width = maxWidth * (LCD_RIGHT - LCD_LEFT)
        val height = maxHeight * (LCD_BOTTOM - LCD_TOP)
        val lit by animateColorAsState(if (asleep) Color(0x00EAF2FF) else Color(0xFFEAF2FF), tween(600), label = "lcd")
        val cooking = running(view?.phase)
        // Waiting: the panel shows the program being put together; cooking: what the grill reports.
        val heatText = if (!cooking && plan != null) plan.heat else view?.let(::lcdHeat).orEmpty()
        val timeText = if (!cooking && plan != null) plan.time else view?.let(::lcdTime).orEmpty()
        val mode = if (cooking) view?.mode else plan?.mode
        val smoke = if (cooking) view?.smoke == true else plan?.smoke == true
        val panelDark = Color(0xFF191B1F)
        val ledOn = Color(0xFFF2F6FF)
        val ledIndex = when (mode) {
            CookMode.SMOKER -> 0; CookMode.GRILL -> 1; CookMode.AIR_CRISP -> 2; CookMode.ROAST -> 3
            CookMode.BAKE -> 4; CookMode.DEHYDRATE -> 5; CookMode.REHEAT -> 6; else -> null
        }
        // The mode LEDs and the Woodfire flame, drawn over the picture's own (a photo shows Smoker lit).
        androidx.compose.foundation.Canvas(Modifier.matchParentSize()) {
            val w = size.width
            val h = size.height
            drawRect(panelDark, Offset(w * LED_LEFT, h * (LED_ROWS.first() - 0.004f)), androidx.compose.ui.geometry.Size(w * (LED_RIGHT - LED_LEFT), h * (LED_ROWS.last() - LED_ROWS.first() + 0.008f)))
            if (!asleep && ledIndex != null) {
                val y = h * LED_ROWS[ledIndex]
                drawRoundRect(
                    ledOn, Offset(w * (LED_LEFT + 0.002f), y - 0.9.dp.toPx()), androidx.compose.ui.geometry.Size(w * (LED_RIGHT - LED_LEFT - 0.004f), 1.8.dp.toPx()),
                    androidx.compose.ui.geometry.CornerRadius(1.dp.toPx()),
                )
            }
            if (asleep || !smoke) {
                drawRect(panelDark.copy(alpha = 0.78f), Offset(w * FLAME_LEFT, h * FLAME_TOP), androidx.compose.ui.geometry.Size(w * (FLAME_RIGHT - FLAME_LEFT), h * (FLAME_BOTTOM - FLAME_TOP)))
            }
        }
        // Monospace glyphs are ~0.6 em wide: shrink so heat and time always fit side by side.
        // A number gets the panel's small unit mark beside it (HI / MED / LO don't), in the owner's units.
        val numeric = heatText.isNotEmpty() && heatText.all { it.isDigit() }
        val shownHeat = if (numeric && unitsF) "${(heatText.toInt() * 9 / 5.0 + 32).toInt()}" else heatText
        val unitMark = if (numeric) (if (unitsF) "°F" else "°C") else ""
        val chars = (shownHeat.length + timeText.length + 1 + (if (numeric) 1 else 0)).coerceAtLeast(6)
        val fontSize = with(density) { minOf(height * 0.62f, width / (chars * 0.64f)).toSp() }
        val style = TextStyle(
            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = fontSize, lineHeight = fontSize,
            color = lit, letterSpacing = 0.sp, shadow = Shadow(lit.copy(alpha = lit.alpha * 0.6f), blurRadius = 6f),
        )
        Box(
            Modifier
                .absoluteOffset(left, top)
                .size(width, height)
                .clip(RoundedCornerShape(2.dp))
                .background(Color(0xFF15181C)),
            contentAlignment = Alignment.Center,
        ) {
            if (!asleep && view != null) {
                // The panel reads left to right whatever the app's language.
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Row(verticalAlignment = Alignment.Top) {
                            Text(shownHeat, style = style, maxLines = 1, softWrap = false)
                            if (unitMark.isNotEmpty()) Text(unitMark, style = style.copy(fontSize = style.fontSize * 0.5f, lineHeight = style.fontSize * 0.5f), maxLines = 1, softWrap = false)
                        }
                        Text(timeText, style = style, maxLines = 1, softWrap = false)
                    }
                }
            }
        }
    }
}

private fun lcdHeat(v: GrillView): String = when {
    !running(v.phase) -> "---"
    v.mode == CookMode.GRILL -> when (CookSpecs.grillLevel(v.setpoint)) { 1 -> "LO"; 2 -> "MED"; 3 -> "HI"; else -> "---" }
    v.setpoint != null -> "${v.setpoint}"
    else -> "---"
}

private fun lcdTime(v: GrillView): String = when (v.phase) {
    CookPhase.IGNITION -> "IGn"
    CookPhase.PREHEAT -> "PrE"
    CookPhase.DONE -> "End"
    CookPhase.WAITING_LID -> "Lid"
    // Hours and minutes, rounded up, like the grill: 23 minutes left reads 0:23.
    CookPhase.COOKING, CookPhase.RESTING -> v.secondsLeft?.let { s -> ((s + 59) / 60).let { m -> "%d:%02d".format(m / 60, m % 60) } } ?: "-:--"
    else -> "-:--"
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = G.textSecondary, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp))
}

@Composable
private fun phaseStyle(view: GrillView?, unitsF: Boolean): Triple<String, Color, Color> {
    val phase = view?.phase
    // What the grill's own display asks for (add food, flip, get food) takes over the pill.
    view?.prompt?.let { return Triple(stringResource(it.label()), G.heatContainer, G.onHeatContainer) }
    val base = stringResource(phase?.label() ?: R.string.conn_searching)
    val label = view?.takeIf { running(phase) }?.let { modeLine(it, unitsF) }?.let { "$base · $it" } ?: base
    return when (phase) {
        CookPhase.IGNITION, CookPhase.PREHEAT, CookPhase.COOKING, CookPhase.WAITING_LID -> Triple(label, G.heatContainer, G.onHeatContainer)
        CookPhase.RESTING, CookPhase.DONE -> Triple(label, G.okContainer, G.onOkContainer)
        CookPhase.IDLE -> Triple(label, G.primaryContainer, G.onPrimaryContainer)
        else -> Triple(label, G.surfaceStrong, G.textSecondary)
    }
}

/** "Grill High", "Roast 180°" (no unit: the pill is short), plus Woodfire when it's on. */
@Composable
private fun modeLine(view: GrillView, unitsF: Boolean): String? {
    val mode = view.mode ?: return null
    val parts = mutableListOf(stringResource(mode.label()))
    if (mode == CookMode.GRILL) {
        CookSpecs.grillLevel(view.setpoint)?.let { parts += stringResource(listOf(R.string.heat_lo, R.string.heat_med, R.string.heat_hi)[it - 1]) }
    } else {
        view.setpoint?.let { parts += temp(it.toDouble(), unitsF, withUnit = false) }
    }
    if (view.smoke == true) parts += stringResource(R.string.woodfire)
    return parts.joinToString(" ")
}
