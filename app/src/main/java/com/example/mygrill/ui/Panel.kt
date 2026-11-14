package com.example.mygrill.ui

import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.mygrill.ui.theme.G
import com.example.mygrill.ui.theme.Numeric
import kotlinx.coroutines.launch
import kotlin.math.abs

/*
 * The planner works like the grill's own panel: a readout of the cook's numbers, one of them
 * selected, and one control for the selected one. Everything pressable here is raised
 * (Modifier.control); what only informs stays flat.
 */

/** A number that rolls up or down to its new value, like the probe reading. */
@Composable
fun RollingNumber(text: String, numeric: Int?, style: TextStyle, color: Color, modifier: Modifier = Modifier) {
    AnimatedContent(
        targetState = text to numeric,
        transitionSpec = {
            val up = (targetState.second ?: 0) >= (initialState.second ?: 0)
            (slideInVertically(tween(180)) { if (up) it / 2 else -it / 2 } + fadeIn(tween(180))) togetherWith
                (slideOutVertically(tween(140)) { if (up) -it / 2 else it / 2 } + fadeOut(tween(140)))
        },
        modifier = modifier,
        label = "roll",
    ) { (t, _) -> Text(t, style = style, color = color, maxLines = 1) }
}

/** One number of the readout: caption, value, a line under it. Selected = it owns the control below. */
@Composable
fun RowScope.ReadoutCell(caption: String, value: String, numeric: Int?, sub: String, selected: Boolean, dot: Boolean = false, onClick: (() -> Unit)?) {
    val shape = RoundedCornerShape(14.dp)
    val press = remember { MutableInteractionSource() }
    val caret = G.primary
    Column(
        Modifier
            .weight(1f)
            .heightIn(min = 84.dp)
            .control(shape, selected, press, soft = true)
            .then(if (onClick != null) Modifier.clickable(interactionSource = press, indication = ripple(), role = Role.Tab, onClick = onClick) else Modifier)
            .drawBehind {
                if (selected) {
                    val w = 24.dp.toPx()
                    drawRoundRect(caret, Offset((size.width - w) / 2, size.height - 4.dp.toPx()), Size(w, 3.dp.toPx()), CornerRadius(2.dp.toPx()))
                }
            }
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(caption, style = MaterialTheme.typography.labelMedium, color = G.textSecondary, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
            if (dot) {
                Spacer(Modifier.width(4.dp))
                Box(Modifier.size(6.dp).clip(CircleShape).background(G.primary))
            }
        }
        RollingNumber(value, numeric, MaterialTheme.typography.headlineSmall.merge(Numeric), G.text)
        Text(sub, style = MaterialTheme.typography.labelSmall, color = G.textSecondary, maxLines = 1)
    }
}

/**
 * A wheel you spin with a thumb, like a phone's clock: the row in the middle band is the value.
 * Rows can carry a word (rare, medium-rare…) so presets live on the same wheel as every other
 * degree; a tap on any row rolls it to the middle. A tick you can feel on every row.
 */
@Composable
fun WheelPicker(
    values: List<Int>,
    value: Int,
    text: (Int) -> String,
    note: @Composable (Int) -> String?,
    onChange: (Int) -> Unit,
    marks: Map<Int, Color> = emptyMap(),
) {
    if (values.isEmpty()) return
    val rowH = 44.dp
    val visible = 5
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val start = values.indexOfFirst { it >= value }.let { if (it < 0) values.lastIndex else it }
    val state = rememberLazyListState(initialFirstVisibleItemIndex = start)
    val fling = rememberSnapFlingBehavior(lazyListState = state)
    val change by rememberUpdatedState(onChange)
    val centre by remember(values) {
        derivedStateOf {
            val info = state.layoutInfo
            val mid = (info.viewportStartOffset + info.viewportEndOffset) / 2
            info.visibleItemsInfo.minByOrNull { abs(it.offset + it.size / 2 - mid) }?.index ?: start
        }
    }
    // A tick per row while it spins; the value lands when it settles.
    LaunchedEffect(centre) {
        if (state.isScrollInProgress) {
            view.performHapticFeedback(if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_FREQUENT_TICK else HapticFeedbackConstants.CLOCK_TICK)
        }
    }
    LaunchedEffect(state.isScrollInProgress) {
        if (!state.isScrollInProgress) values.getOrNull(centre)?.let { if (it != value) change(it) }
    }
    // Follow changes from outside (a preset, another mode) without fighting the thumb.
    LaunchedEffect(value, values) {
        val i = values.indexOf(value)
        if (i >= 0 && i != centre && !state.isScrollInProgress) state.scrollToItem(i)
    }
    val band = RoundedCornerShape(14.dp)
    Box(Modifier.fillMaxWidth().height(rowH * visible).clip(RoundedCornerShape(18.dp)).background(G.surfaceAlt)) {
        Box(
            Modifier.align(Alignment.Center).fillMaxWidth().padding(horizontal = 6.dp).height(rowH)
                .control(band, selected = true, interaction = remember { MutableInteractionSource() }),
        )
        LazyColumn(
            state = state,
            flingBehavior = fling,
            contentPadding = PaddingValues(vertical = rowH * (visible / 2)),
            modifier = Modifier.fillMaxSize(),
        ) {
            itemsIndexed(values) { i, v ->
                val d = abs(i - centre)
                val onBand = d == 0
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(rowH)
                        .graphicsLayer { alpha = when (d) { 0 -> 1f; 1 -> 0.62f; 2 -> 0.32f; else -> 0.15f } }
                        .clickable(interactionSource = null, indication = null) { scope.launch { state.animateScrollToItem(i) } },
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    marks[v]?.let {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(it))
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        text(v),
                        style = (if (onBand) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium).merge(Numeric),
                        color = if (onBand) G.onPrimary else G.text,
                    )
                    note(v)?.let {
                        Spacer(Modifier.width(10.dp))
                        Text(it, style = MaterialTheme.typography.labelLarge, color = if (onBand) G.onPrimary.copy(alpha = 0.85f) else G.textSecondary)
                    }
                }
            }
        }
    }
}

/** Equal choices in one row that never wraps: a name and, under it, what it really means. */
@Composable
fun <T> LevelTrack(options: List<Triple<T, String, String>>, selected: T?, onPick: (T) -> Unit) {
    val view = LocalView.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (value, name, sub) ->
            val on = value == selected
            val press = remember { MutableInteractionSource() }
            val fg = if (on) G.onPrimary else G.textSecondary
            Column(
                Modifier.weight(1f).heightIn(min = 56.dp)
                    .control(RoundedCornerShape(12.dp), on, press)
                    .clickable(interactionSource = press, indication = ripple(), role = Role.RadioButton) { view.haptic(Haptic.Tap); onPick(value) }
                    .padding(vertical = 6.dp, horizontal = 2.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(name, style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 1, softWrap = false)
                Text(sub, style = MaterialTheme.typography.titleSmall.merge(Numeric), color = fg, maxLines = 1)
            }
        }
    }
}

/** What ends the cook, as one row of chips: each connected probe with its live reading, or the timer. */
@Composable
fun SourceChips(options: List<Pair<String, String>>, selected: String, onPick: (String) -> Unit, @DrawableRes timerIcon: Int, @DrawableRes probeIcon: Int) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (key, text) ->
            val on = key == selected
            val press = remember { MutableInteractionSource() }
            val fg = if (on) G.onPrimary else G.text
            Row(
                Modifier.heightIn(min = 44.dp)
                    .control(RoundedCornerShape(12.dp), on, press)
                    .clickable(interactionSource = press, indication = ripple(), role = Role.RadioButton) { onPick(key) }
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(painterResource(if (key == "time") timerIcon else probeIcon), contentDescription = null, tint = fg, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(6.dp))
                Text(text, style = MaterialTheme.typography.labelLarge.merge(Numeric), color = fg, maxLines = 1)
            }
        }
    }
}

/** An on/off option as a tile: icon, name, and what it means right now. */
@Composable
fun RowScope.OptionTile(@DrawableRes icon: Int, title: String, sub: String, on: Boolean, enabled: Boolean, onToggle: () -> Unit) {
    val press = remember { MutableInteractionSource() }
    val fg = if (on) G.onPrimary else if (enabled) G.text else G.textMuted
    Row(
        Modifier.weight(1f).heightIn(min = 56.dp)
            .control(RoundedCornerShape(14.dp), on, press, enabled = enabled)
            .clickable(interactionSource = press, indication = ripple(), enabled = enabled, role = Role.Switch, onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = fg, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Column {
            Text(title, style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 1)
            Text(sub, style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp), color = if (on) G.onPrimary.copy(alpha = 0.8f) else G.textSecondary, maxLines = 1)
        }
    }
}
