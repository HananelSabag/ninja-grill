package com.example.mygrill.ui

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.mygrill.ui.theme.G
import kotlin.math.sin

// ------------------------------------------------------------------ haptics

enum class Haptic { Tap, Confirm, Reject }

fun View.haptic(type: Haptic) {
    val constant = when (type) {
        Haptic.Tap -> HapticFeedbackConstants.VIRTUAL_KEY
        Haptic.Confirm -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.LONG_PRESS
        Haptic.Reject -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS
    }
    performHapticFeedback(constant)
}

/** A short horizontal shake, played once per failure. */
@Composable
fun rememberShake(trigger: Long?): Animatable<Float, *> {
    val shake = remember { Animatable(0f) }
    LaunchedEffect(trigger) {
        if (trigger != null) {
            shake.snapTo(0f)
            shake.animateTo(0f, keyframes {
                durationMillis = 360
                -12f at 50
                10f at 110
                -7f at 170
                4f at 240
                0f at 320
            })
        }
    }
    return shake
}

// ------------------------------------------------------------------ surfaces

/** One surface for one group. Elevation is tone alone: no outline, no shadow. */
@Composable
fun Card(modifier: Modifier = Modifier, color: Color = G.surface, padding: Int = 18, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(color)
            .padding(padding.dp),
        content = content,
    )
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, trailing: String? = null) {
    Row(modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleMedium, color = G.text, modifier = Modifier.weight(1f))
        if (trailing != null) Text(trailing, style = MaterialTheme.typography.bodySmall, color = G.textMuted)
    }
}

/**
 * The page ground is a sky: deep blue at the top fading to a pale horizon, a slow sun drifting
 * along the top, a few soft clouds drifting the other way, and a white studio light behind the
 * grill that lifts the photo off it. While it cooks the sun and the light warm toward a sunset.
 */
@Composable
fun HeatBackground(heating: Boolean, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val warm by androidx.compose.animation.core.animateFloatAsState(if (heating) 1f else 0f, tween(1400), label = "warm")
    val drift by rememberInfiniteTransition(label = "bg").animateFloat(
        0f, 1f, infiniteRepeatable(tween(24_000, easing = LinearEasing), RepeatMode.Reverse), label = "drift",
    )
    val c = G
    Box(
        modifier.fillMaxSize().drawBehind {
            val w = size.width
            val h = size.height
            val top = lerp(c.groundTop, c.spotWarm.copy(alpha = 1f), warm * 0.25f)
            drawRect(Brush.verticalGradient(0f to top, 0.5f to c.bg, 1f to c.groundBottom))
            val sun = Offset(w * (0.15f + 0.7f * drift), h * 0.03f + h * 0.01f * sin(drift * 6f))
            val sunR = w * 0.72f
            drawCircle(Brush.radialGradient(listOf(lerp(c.sun, c.heatFill.copy(alpha = 0.32f), warm), Color.Transparent), sun, sunR), sunR, sun)
            // Each cloud is a few soft puffs in a row; they drift against the sun.
            val shift = (0.5f - drift) * w * 0.10f
            for ((x, y, r) in CLOUD_PUFFS) {
                val p = Offset(w * x + shift, h * y)
                drawCircle(Brush.radialGradient(listOf(c.haze, Color.Transparent), p, w * r), w * r, p)
            }
            // The studio light sits behind the grill photo near the top of the page.
            val spot = Offset(w * 0.5f, minOf(h * 0.27f, 290.dp.toPx()))
            val spotR = w * 0.78f
            drawCircle(Brush.radialGradient(listOf(lerp(c.spot, c.spotWarm, warm), Color.Transparent), spot, spotR), spotR, spot)
            drawRect(Brush.radialGradient(listOf(Color.Transparent, c.vignette), Offset(w / 2, h * 0.42f), maxOf(w, h) * 0.85f))
        },
    ) { content() }
}

/** Cloud puffs as (x, y) fractions of the page and a radius as a fraction of its width. */
private val CLOUD_PUFFS = listOf(
    Triple(0.06f, 0.075f, 0.16f), Triple(0.20f, 0.065f, 0.20f), Triple(0.34f, 0.08f, 0.13f),
    Triple(0.70f, 0.17f, 0.12f), Triple(0.82f, 0.155f, 0.17f), Triple(0.95f, 0.17f, 0.12f),
)

// ------------------------------------------------------------------ controls

/**
 * What can be pressed looks pressable: raised on a soft shadow tinted by the theme, a gentle
 * top-to-bottom tone and a lit top edge, and it sinks with a spring while the finger is down.
 * Information stays flat (tone only), so the two never read alike. Selected = the primary, raised higher.
 */
@Composable
fun Modifier.control(shape: Shape, selected: Boolean, interaction: MutableInteractionSource, enabled: Boolean = true, soft: Boolean = false): Modifier {
    val pressed by interaction.collectIsPressedAsState()
    val scale by androidx.compose.animation.core.animateFloatAsState(
        if (pressed) 0.96f else 1f, spring(dampingRatio = 0.55f, stiffness = 700f), label = "press",
    )
    val lift by androidx.compose.animation.core.animateDpAsState(
        when {
            !enabled -> 0.dp
            pressed -> 1.dp
            selected -> 6.dp
            else -> 3.dp
        },
        tween(160), label = "lift",
    )
    val c = G
    // soft: selected without the full primary fill (a cell that owns the control below it).
    val strong = selected && !soft
    val top = when {
        strong -> c.primaryHi
        selected -> lerp(c.primaryContainer, c.controlTop, 0.45f)
        else -> c.controlTop
    }
    val bottom = when {
        strong -> c.primary
        selected -> c.primaryContainer
        else -> c.controlBottom
    }
    val tint = if (selected) c.primary else c.controlShadow
    val edge = if (strong) Color.White.copy(alpha = if (c.dark) 0.18f else 0.28f) else c.controlEdge
    return this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .shadow(lift, shape, clip = false, ambientColor = tint.copy(alpha = 0.22f), spotColor = tint.copy(alpha = 0.32f))
        .clip(shape)
        .background(Brush.verticalGradient(listOf(top, bottom)))
        .drawWithContent {
            drawContent()
            // The lit top edge: one hairline of light where a raised surface catches it.
            drawLine(edge, Offset(size.height * 0.25f, 0.75f), Offset(size.width - size.height * 0.25f, 0.75f), strokeWidth = 1.5f)
        }
}

// ------------------------------------------------------------------ small parts

@Composable
fun Dot(color: Color, size: Int = 8, pulsing: Boolean = false) {
    val alpha by rememberInfiniteTransition(label = "dot").animateFloat(
        1f, if (pulsing) 0.35f else 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "dotAlpha",
    )
    Box(Modifier.size(size.dp).clip(CircleShape).background(color.copy(alpha = alpha)))
}

/** A rounded label on a tinted ground; text takes the ground's own dark tone. */
@Composable
fun Pill(text: String, container: Color, content: Color, @DrawableRes icon: Int? = null, dot: Color? = null, pulsing: Boolean = false) {
    Row(
        Modifier.clip(CircleShape).background(container).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dot != null) {
            Dot(dot, 8, pulsing)
            Spacer(Modifier.width(6.dp))
        }
        if (icon != null) {
            Icon(painterResource(icon), contentDescription = null, tint = content, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, color = content, maxLines = 1)
    }
}

/**
 * Segmented choice without outlines: a tonal track with the selected option lifted onto the
 * primary colour. Every segment is a full 48dp target.
 */
@Composable
fun <T> Segmented(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    val view = LocalView.current
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(G.surfaceAlt)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { (value, label) ->
            val on = value == selected
            val bg by animateColorAsState(if (on) G.primary else Color.Transparent, tween(180), label = "seg")
            val fg by animateColorAsState(if (on) G.onPrimary else G.textSecondary, tween(180), label = "segFg")
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 44.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(bg)
                    .clickable(role = Role.RadioButton) { view.haptic(Haptic.Tap); onSelect(value) },
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = MaterialTheme.typography.labelLarge, color = fg, textAlign = TextAlign.Center, maxLines = 1)
            }
        }
    }
}

/** A row that is one big tap target: icon, label and value or chevron. */
@Composable
fun InfoLine(@DrawableRes icon: Int, label: String, value: String, valueColor: Color = G.text, note: String? = null) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(G.surfaceAlt), contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), contentDescription = null, tint = G.textSecondary, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = G.text)
            if (note != null) Text(note, style = MaterialTheme.typography.bodySmall, color = G.textMuted)
        }
        Text(value, style = MaterialTheme.typography.titleMedium, color = valueColor, maxLines = 1)
    }
}

@Composable
fun VSpace(dp: Int) = Spacer(Modifier.height(dp.dp))
