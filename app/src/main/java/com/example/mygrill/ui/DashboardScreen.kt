package com.example.mygrill.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.withStyle
import com.example.mygrill.R
import com.example.mygrill.core.CookMode
import com.example.mygrill.core.CookPhase
import com.example.mygrill.core.CookSpecs
import com.example.mygrill.core.GrillView
import com.example.mygrill.core.Source
import com.example.mygrill.data.BeaconScanner
import com.example.mygrill.data.BleProblem
import com.example.mygrill.data.Channel
import com.example.mygrill.data.CommandStatus
import com.example.mygrill.data.CookSummary
import com.example.mygrill.data.EndReason
import com.example.mygrill.data.GrillRepository
import com.example.mygrill.data.UiState
import com.example.mygrill.ui.theme.G
import com.example.mygrill.ui.theme.LiveNumeral
import com.example.mygrill.ui.theme.Numeric
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class Stage { NONE, READY, STARTING, COOKING }

/**
 * One screen with a fixed frame: the grill, its heat card (always in the same place), then what
 * the moment needs: the planner (ready), the start in flight, or the cook under way. Connection
 * lives in a pill up top; the one big action is always at the bottom; heat and stop ask first.
 */
@Composable
fun DashboardScreen(state: UiState, repo: GrillRepository, onLogin: () -> Unit, onSettings: () -> Unit, onFindThermometer: () -> Unit) {
    val snackbar = remember { SnackbarHostState() }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { repo.restart() }
    val enableBt = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { repo.restart() }
    // Alerts need Android's yes (13+). Asked once, on first open; settings can switch them off.
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { repo.markNotificationsAsked() }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && repo.shouldAskNotifications()) {
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    val view = state.view
    val cooking = running(view?.phase)
    val stage = when {
        cooking -> Stage.COOKING
        // A start in flight: show it starting instead of the planner snapping back for a few seconds.
        state.startingSince != null -> Stage.STARTING
        view != null -> Stage.READY
        else -> Stage.NONE
    }
    val probes = probeEntries(state)
    val planner = remember { PlannerState(state.lastRequest, null, editing = false) }
    var editPlanner by remember { mutableStateOf<PlannerState?>(null) }
    var confirm by rememberSaveable { mutableStateOf<String?>(null) } // "start" | "update"
    LaunchedEffect(cooking) { if (!cooking) editPlanner = null }

    CommandFeedback(state.command, snackbar)
    val autoStopped = stringResource(R.string.auto_stopped)
    LaunchedEffect(state.autoStoppedAt) { if (state.autoStoppedAt != null) snackbar.showSnackbar(autoStopped) }
    val unconfirmed = stringResource(R.string.start_unconfirmed)
    LaunchedEffect(state.startUnconfirmedAt) { if (state.startUnconfirmedAt != null) snackbar.showSnackbar(unconfirmed) }

    HeatBackground(view?.phase?.heating == true) {
        Scaffold(
            containerColor = Color.Transparent,
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = { ActionBar(state, repo, stage, onIgnite = { confirm = "start" }) },
        ) { inner ->
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = inner.calculateBottomPadding())
                    .padding(horizontal = 16.dp),
            ) {
                // The header scrolls away with the page: nothing sits over the grill or the readings.
                TopBar(state, repo, onSettings)
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Problems(
                        state,
                        onGrant = { permissions.launch(BeaconScanner.permissions()) },
                        onEnableBluetooth = { enableBt.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) },
                        onLogin = onLogin,
                        onSettings = onSettings,
                    )
                    GrillHero(view, state.unitsF, state.canWake, plan = panelPlan(planner, probes))
                    AnimatedVisibility(state.waitingForLid) { LidBanner() }
                    if (view != null) HeatCard(state, view, onSkip = repo::skipPreheat)
                    // The grill and its heat card stay put; only what's under them changes.
                    AnimatedContent(
                        targetState = stage,
                        transitionSpec = {
                            (fadeIn(tween(220)) + slideInVertically(tween(220)) { it / 10 }) togetherWith fadeOut(tween(120)) using SizeTransform(clip = false)
                        },
                        label = "stage",
                    ) { s ->
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            when (s) {
                                Stage.STARTING -> StartingCard(state, onCancel = repo::cancelStart, summary = heatSummary(planner.request(probes), state.unitsF) + " · " + finishSummary(planner.request(probes), planner.autoStop(probes, state.carryoverC), probes, state.unitsF))
                                Stage.COOKING -> if (view != null) CookingNow(
                                    state, view, probes, editPlanner,
                                    onEdit = { field ->
                                        editPlanner = if (editPlanner != null && field == null) null
                                        else (editPlanner ?: PlannerState(repo.currentRequest(), state.autoStop, editing = true)).also { it.focus = field ?: it.focus }
                                    },
                                    onUpdate = { confirm = "update" },
                                )
                                Stage.READY -> {
                                    state.lastCook?.let { FinishedCard(it, state, probes, onClose = repo::dismissLastCook) }
                                    // Only probes that are plugged in; adding a Tempwise lives in settings.
                                    probes.filter { it.tempC != null }.takeIf { it.isNotEmpty() }?.let { ProbeStrip(it, state.unitsF, onAddThermometer = null) }
                                    Card(padding = 14) {
                                        Text(stringResource(R.string.choose_program), style = MaterialTheme.typography.labelLarge, color = G.textSecondary, modifier = Modifier.padding(start = 2.dp, bottom = 8.dp))
                                        CookPlanner(planner, probes, state.unitsF, state.carryoverC)
                                    }
                                }
                                Stage.NONE -> Unit
                            }
                        }
                    }
                    VSpace(8)
                }
            }
            // Content scrolls under a see-through status bar: give the clock a ground to sit on.
            Box(Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars).background(G.groundTop.copy(alpha = 0.92f)))
        }
    }

    val haptics = LocalView.current
    val edit = editPlanner
    when {
        confirm == "start" -> CookConfirmDialog(
            planner.request(probes), planner.autoStop(probes, state.carryoverC), probes, state.unitsF, editing = false,
            onConfirm = {
                haptics.haptic(Haptic.Confirm)
                confirm = null
                repo.startCook(planner.request(probes), planner.autoStop(probes, state.carryoverC))
            },
            onDismiss = { confirm = null },
        )
        confirm == "update" && edit != null -> CookConfirmDialog(
            edit.request(probes), edit.autoStop(probes, state.carryoverC), probes, state.unitsF, editing = true,
            onConfirm = {
                haptics.haptic(Haptic.Confirm)
                confirm = null
                repo.updateCook(edit.request(probes), edit.autoStop(probes, state.carryoverC))
                editPlanner = null
            },
            onDismiss = { confirm = null },
        )
    }
}

/**
 * Every probe the app can read, named so it's obvious which is which: the wireless Tempwise probes
 * by colour (white, black; first, since they're the ones in use), then the grill's wired ports as
 * internal 1 and 2. Probes in the base or unplugged carry no reading and stay out of the lists.
 */
@Composable
fun probeEntries(state: UiState): List<ProbeEntry> {
    val ext = state.external?.let { e ->
        val reading = e.reading?.takeIf { e.connected }
        (0..1).map { i ->
            val key = "ext$i"
            ProbeEntry(key, externalName(state, i), reading?.probesC?.getOrNull(i), state.autoStop?.takeIf { it.probeKey == key }?.targetC, Source.BLE)
        }
    }.orEmpty()
    val ninja = (0..1).map { i ->
        val p = state.view?.probes?.getOrNull(i)
        val key = "ninja$i"
        ProbeEntry(key, stringResource(R.string.probe_n, i + 1), p?.tempC, state.autoStop?.takeIf { it.probeKey == key }?.targetC ?: p?.targetC, p?.source)
    }
    return ext + ninja
}

/** Slot [i] of the Tempwise base by its colour, as the owner set it. */
@Composable
fun externalName(state: UiState, i: Int): String =
    stringResource(if ((i == 0) == state.extWhiteFirst) R.string.probe_white else R.string.probe_black)

/** What the photo's display shows while the grill waits: the program being put together. */
@Composable
private fun panelPlan(p: PlannerState, probes: List<ProbeEntry>): PanelPlan {
    val r = p.request(probes)
    return PanelPlan(
        mode = r.mode,
        heat = if (r.mode == CookMode.GRILL) listOf("LO", "MED", "HI")[(r.temp - 1).coerceIn(0, 2)] else "${r.temp}",
        time = ((r.seconds + 59) / 60).let { m -> "%d:%02d".format(m / 60, m % 60) },
        smoke = r.smoke,
    )
}

/** Grill mode's target in °C even when a channel reports the level (1–3). */
private fun setpointC(view: GrillView): Int? {
    val sp = view.setpoint ?: return null
    return if (view.mode == CookMode.GRILL && sp in 1..3) CookSpecs.GRILL_LEVEL_C[sp - 1] else sp
}

// ------------------------------------------------------------------ heat card

/**
 * The grill's own sensors, always under the photo in a fixed order, so nothing jumps: the grill
 * sensor the panel heats to (live over Bluetooth too), the air and the smoke box (cloud only, "—"
 * without fresh data; the smoke box reads without pellets too, so it's muted unless Woodfire is on).
 * While the grill warms up the card grows: the grill is the big number, with the bar and the skip.
 */
@Composable
private fun HeatCard(state: UiState, view: GrillView, onSkip: () -> Unit) {
    val warming = view.phase == CookPhase.PREHEAT || view.phase == CookPhase.IGNITION
    AnimatedContent(
        targetState = warming,
        transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(140)) using SizeTransform(clip = false) },
        label = "heat",
    ) { big ->
        if (big) WarmingPanel(state, view, onSkip) else HeatTiles(state, view)
    }
}

/**
 * The grill's heat in one quiet line under the photo, the way it was: the grill always (its target
 * beside it while cooking), the air once the cloud reports it, the smoke box only with Woodfire on.
 */
@Composable
private fun HeatTiles(state: UiState, view: GrillView) {
    val f = state.unitsF
    val target = setpointC(view)?.takeIf { running(view.phase) }
    val cells = buildList {
        add(Triple(stringResource(R.string.temp_grill), view.grillC, target?.let { "/" + temp(it.toDouble(), f, withUnit = false) }
            ?: stringResource(R.string.cooling).takeIf { state.lastCook != null && (view.grillC ?: 0.0) > 50 }))
        view.airC?.let { add(Triple(stringResource(R.string.temp_air), it, null)) }
        view.smokeC?.takeIf { view.smoke == true }?.let { add(Triple(stringResource(R.string.temp_smoke), it, null)) }
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(G.surface.copy(alpha = 0.62f)).padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        cells.forEachIndexed { i, (label, c, sub) ->
            if (i > 0) Box(Modifier.size(4.dp).clip(CircleShape).background(G.textMuted.copy(alpha = 0.5f)))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(label, style = MaterialTheme.typography.labelSmall, color = G.textSecondary, maxLines = 1)
                Row(verticalAlignment = Alignment.Bottom) {
                    RollingNumber(temp(c, f), c?.toInt(), MaterialTheme.typography.titleLarge.merge(Numeric), G.text)
                    if (sub != null) Text(" " + sub, style = MaterialTheme.typography.labelMedium.merge(Numeric), color = G.textMuted, modifier = Modifier.padding(bottom = 3.dp))
                }
            }
        }
    }
}

/** Preheat (or Woodfire ignition): the grill's climb to its target, with the skip right here. */
@Composable
private fun WarmingPanel(state: UiState, view: GrillView, onSkip: () -> Unit) {
    val f = state.unitsF
    val igniting = view.phase == CookPhase.IGNITION
    val target = setpointC(view)
    val busy = state.command is CommandStatus.Busy
    Card(padding = 16) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (igniting) stringResource(R.string.igniting) else target?.let { stringResource(R.string.heating_to, temp(it.toDouble(), f, withUnit = false)) } ?: stringResource(R.string.preheat_title),
                style = MaterialTheme.typography.titleMedium.merge(Numeric), color = G.text, modifier = Modifier.weight(1f),
            )
            state.preheatEtaSec?.let { s ->
                Text(stringResource(R.string.eta_short, etaText(s)), style = MaterialTheme.typography.labelLarge, color = G.textSecondary)
            }
        }
        RollingNumber(temp(view.grillC, f, withUnit = false), view.grillC?.toInt(), LiveNumeral, G.text)
        VSpace(8)
        val now = view.grillC
        if (!igniting && now != null && target != null) {
            val from = state.preheatFromC?.coerceAtMost(now) ?: 20.0
            Bar(((now - from) / (target - from).coerceAtLeast(1.0)).toFloat().coerceIn(0f, 1f), G.heatFill)
        } else {
            LinearProgressIndicator(Modifier.fillMaxWidth(), color = G.heatFill, trackColor = G.track)
        }
        VSpace(12)
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (view.airC != null) {
                MiniReading(stringResource(R.string.temp_air), view.airC, f)
                Spacer(Modifier.width(14.dp))
            }
            if (view.smoke == true && view.smokeC != null) MiniReading(stringResource(R.string.temp_smoke), view.smokeC, f)
            Spacer(Modifier.weight(1f))
            FilledTonalButton(
                onClick = onSkip, enabled = state.canControl && !busy,
                shape = RoundedCornerShape(14.dp), modifier = Modifier.heightIn(min = 44.dp),
            ) {
                Icon(painterResource(R.drawable.ic_skip), contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(if (igniting) R.string.skip_ignition else R.string.skip_preheat))
            }
        }
    }
}

@Composable
private fun MiniReading(label: String, c: Double?, unitsF: Boolean) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = G.textSecondary)
        Text(temp(c, unitsF), style = MaterialTheme.typography.titleSmall.merge(Numeric), color = if (c != null) G.text else G.textMuted)
    }
}

// ------------------------------------------------------------------ cook under way

@Composable
private fun CookingNow(
    state: UiState,
    view: GrillView,
    probes: List<ProbeEntry>,
    editPlanner: PlannerState?,
    onEdit: (PlanField?) -> Unit,
    onUpdate: () -> Unit,
) {
    val f = state.unitsF
    val warming = view.phase == CookPhase.PREHEAT || view.phase == CookPhase.IGNITION
    val auto = state.autoStop
    val lead = probes.firstOrNull { it.key == auto?.probeKey }
        ?: probes.firstOrNull { it.tempC != null && it.targetC != null }
    if (warming) {
        // While the grill warms up its heat is the story; the meat waits in one quiet line.
        Card(padding = 14) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (lead != null) {
                    Text(lead.label, style = MaterialTheme.typography.labelLarge, color = G.textSecondary, modifier = Modifier.weight(1f))
                    Text(temp(lead.tempC, f, withUnit = false), style = MaterialTheme.typography.headlineSmall.merge(Numeric), color = G.text)
                    lead.targetC?.let {
                        Text(" · " + stringResource(R.string.pull_at) + temp((it - state.carryoverC).toDouble(), f, withUnit = false), style = MaterialTheme.typography.bodyMedium.merge(Numeric), color = G.textSecondary)
                    }
                } else {
                    Text(stringResource(R.string.time_title), style = MaterialTheme.typography.labelLarge, color = G.textSecondary, modifier = Modifier.weight(1f))
                    Text(hm(((view.secondsSet ?: 0) + 59) / 60), style = MaterialTheme.typography.headlineSmall.merge(Numeric), color = G.text)
                    Text(" · " + stringResource(R.string.timer_after_preheat), style = MaterialTheme.typography.bodyMedium, color = G.textSecondary)
                }
            }
        }
    } else {
        if (lead != null) {
            ProbeLiveCard(
                lead, f, state.carryoverC,
                etaSec = state.pullEtaSec?.takeIf { auto?.probeKey == lead.key },
                doneness = lead.targetC?.let { donenessName(it) },
                footer = auto?.let {
                    stringResource(R.string.auto_stop_footer, temp(it.pullC.toDouble(), f, withUnit = false)) +
                        (view.secondsLeft?.let { s -> " · " + stringResource(R.string.cap_footer, hm((s + 59) / 60)) } ?: "")
                },
            )
        }
        // A thermometer cook's timer is only its cap; a timed cook's is the whole story.
        if (auto == null && view.secondsLeft != null) TimeCard(view.secondsLeft, view.secondsSet, state.nowMillis)
    }
    val others = probes.filter { it.key != lead?.key && it.tempC != null }
    if (others.isNotEmpty()) ProbeStrip(others, f, onAddThermometer = null)
    ChangeCard(state, probes, editPlanner, onEdit, onUpdate)
}

/**
 * Changes mid-cook look exactly like the start: the same readout cells, read-only until tapped;
 * a tap opens the planner in place with that number selected. Update stays off until something
 * actually changed.
 */
@Composable
private fun ChangeCard(state: UiState, probes: List<ProbeEntry>, editPlanner: PlannerState?, onEdit: (PlanField?) -> Unit, onUpdate: () -> Unit) {
    Card(padding = 14) {
        val p = editPlanner
        if (p == null) {
            // Closed: one quiet line. Nothing in it moves until the owner asks to change something.
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onEdit(null) }.heightIn(min = 44.dp).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(painterResource(R.drawable.ic_edit), contentDescription = null, tint = G.primary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.change_while_cooking), style = MaterialTheme.typography.titleSmall, color = G.text, modifier = Modifier.weight(1f))
                Icon(painterResource(R.drawable.ic_chevron_down), contentDescription = null, tint = G.textMuted, modifier = Modifier.size(18.dp))
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.change_title, stringResource(p.mode.label())),
                    style = MaterialTheme.typography.titleSmall, color = G.text, modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { onEdit(null) }) { Text(stringResource(R.string.cancel)) }
            }
            VSpace(6)
            CookPlanner(p, probes, state.unitsF, state.carryoverC)
            VSpace(12)
            Button(
                onClick = onUpdate,
                enabled = p.touched.isNotEmpty() && state.canControl && state.command !is CommandStatus.Busy,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text(stringResource(R.string.cook_update), style = MaterialTheme.typography.titleMedium) }
        }
    }
}

/**
 * The grill holds the cook until its lid is shut, and says nothing else meanwhile: without this the
 * screen just spins. Loud on purpose, the one warning on the page.
 */
@Composable
private fun LidBanner() {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(G.heatContainer).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(G.heatFill), contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.ic_lid), contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.lid_open_title), style = MaterialTheme.typography.titleMedium, color = G.onHeatContainer)
            Text(stringResource(R.string.lid_open_text), style = MaterialTheme.typography.bodyMedium, color = G.onHeatContainer)
        }
    }
}

/**
 * Between the tap and the grill's first report: what was sent, how long it has been, and, once the
 * grill is slow to answer, what to check and a way out instead of an endless spinner.
 */
@Composable
private fun StartingCard(state: UiState, summary: String, onCancel: () -> Unit) {
    val sent = state.command is CommandStatus.Done
    val elapsed = ((state.nowMillis - (state.startingSince ?: state.nowMillis)) / 1000).toInt().coerceAtLeast(0)
    val slow = elapsed >= SLOW_START_S && !state.waitingForLid
    Card(padding = 16) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(22.dp), color = if (state.waitingForLid) G.heatFill else G.primary, strokeWidth = 2.5.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(if (sent) R.string.starting_waiting else R.string.starting_sending), style = MaterialTheme.typography.titleMedium, color = G.text)
                Text(summary, style = MaterialTheme.typography.bodyMedium.merge(Numeric), color = G.textSecondary)
            }
            Text(stringResource(R.string.starting_elapsed, countdown(elapsed)), style = MaterialTheme.typography.labelMedium.merge(Numeric), color = G.textMuted)
        }
        VSpace(12)
        LinearProgressIndicator(Modifier.fillMaxWidth(), color = if (state.waitingForLid) G.heatFill else G.primary, trackColor = G.track)
        AnimatedVisibility(slow) {
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_warning), contentDescription = null, tint = G.heat, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.starting_slow), style = MaterialTheme.typography.bodySmall, color = G.textSecondary, modifier = Modifier.weight(1f))
                TextButton(onClick = onCancel, enabled = state.command !is CommandStatus.Busy) { Text(stringResource(R.string.starting_cancel)) }
            }
        }
    }
}

/** Seconds without a word from the grill before the starting card says what to check. */
private const val SLOW_START_S = 15

/**
 * The end of a cook stays on screen until closed or the next start: what stopped it and when,
 * the meat now against the moment the heat stopped (its real rest-time climb), and how long it took.
 */
@Composable
private fun FinishedCard(c: CookSummary, state: UiState, probes: List<ProbeEntry>, onClose: () -> Unit) {
    val f = state.unitsF
    val clock = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(c.endedAt))
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(G.okContainer).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = G.onOkContainer, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                when (c.reason) {
                    EndReason.AUTO_STOP -> stringResource(R.string.finished_auto, temp(c.pullC?.toDouble(), f, withUnit = false), ltr(clock))
                    EndReason.MANUAL -> stringResource(R.string.finished_manual, ltr(clock))
                    EndReason.FINISHED -> stringResource(R.string.finished_done, ltr(clock))
                },
                style = MaterialTheme.typography.titleMedium.merge(Numeric), color = G.onOkContainer,
            )
        }
        val probe = probes.firstOrNull { it.key == c.probeKey }
        if (probe != null) {
            VSpace(10)
            Row {
                Text(stringResource(R.string.probe_now, probe.label), style = MaterialTheme.typography.bodyMedium, color = G.onOkContainer, modifier = Modifier.weight(1f))
                val rise = if (probe.tempC != null && c.probeAtEndC != null) probe.tempC - c.probeAtEndC else null
                Text(
                    temp(probe.tempC, f, withUnit = false) + (rise?.let { " · " + stringResource(if (it >= 0) R.string.rose_resting else R.string.fell_resting, ltr((if (it >= 0) "+" else "") + delta(it, f))) } ?: ""),
                    style = MaterialTheme.typography.titleSmall.merge(Numeric), color = G.onOkContainer,
                )
            }
        }
        c.startedAt?.let { start ->
            VSpace(6)
            Row {
                Text(stringResource(R.string.cook_time), style = MaterialTheme.typography.bodyMedium, color = G.onOkContainer, modifier = Modifier.weight(1f))
                Text(hm(((c.endedAt - start) / 60_000).toInt()), style = MaterialTheme.typography.titleSmall.merge(Numeric), color = G.onOkContainer)
            }
        }
        VSpace(10)
        Box(
            Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(12.dp)).background(G.onOkContainer.copy(alpha = 0.12f)).clickable(onClick = onClose),
            contentAlignment = Alignment.Center,
        ) { Text(stringResource(R.string.close), style = MaterialTheme.typography.labelLarge, color = G.onOkContainer) }
    }
}

// ------------------------------------------------------------------ top bar

@Composable
private fun TopBar(state: UiState, repo: GrillRepository, onSettings: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(start = 4.dp, top = 6.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Wordmark(Modifier.weight(1f))
        ConnectionButton(state, onRefresh = repo::refreshNow)
        Spacer(Modifier.width(8.dp))
        RoundControl(R.drawable.ic_settings, stringResource(R.string.settings), G.textSecondary, onClick = onSettings)
        Spacer(Modifier.width(8.dp))
        PowerButton(state, repo)
    }
}

/** "Ninja" heavy in the hood's blue, "Grill" light: a wordmark rather than a label. */
@Composable
private fun Wordmark(modifier: Modifier) {
    val ink = androidx.compose.ui.graphics.Brush.linearGradient(listOf(G.primaryHi, G.primary))
    Text(
        androidx.compose.ui.text.buildAnnotatedString {
            withStyle(androidx.compose.ui.text.SpanStyle(brush = ink, fontWeight = androidx.compose.ui.text.font.FontWeight.Black, letterSpacing = (-0.5).sp)) { append("Ninja") }
            withStyle(androidx.compose.ui.text.SpanStyle(color = G.textSecondary, fontWeight = androidx.compose.ui.text.font.FontWeight.Light)) { append(" Grill") }
        },
        style = MaterialTheme.typography.headlineSmall,
        modifier = modifier,
        maxLines = 1,
    )
}

/** A round raised button for the top bar. */
@Composable
private fun RoundControl(icon: Int, description: String, tint: Color, onClick: () -> Unit) {
    val press = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Box(
        Modifier.size(44.dp).control(CircleShape, false, press)
            .clickable(interactionSource = press, indication = androidx.compose.material3.ripple(), onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(painterResource(icon), contentDescription = description, tint = tint, modifier = Modifier.size(20.dp)) }
}

/**
 * The grill's own power button, in the corner like on any device: asleep, it wakes the panel;
 * cooking, it stops the cook; idle, it explains (a remote "sleep" exists in the firmware but its
 * command isn't known yet, and the panel sleeps by itself after ~10 idle minutes).
 */
@Composable
private fun PowerButton(state: UiState, repo: GrillRepository) {
    val view = LocalView.current
    var dialog by remember { mutableStateOf<String?>(null) }
    val phase = state.view?.phase
    val tint = when {
        state.view == null || phase == CookPhase.OFF -> G.textMuted
        running(phase) -> G.danger
        else -> G.ok
    }
    RoundControl(R.drawable.ic_power, stringResource(R.string.power), tint) {
        view.haptic(Haptic.Tap)
        dialog = when {
            running(phase) -> "stop"
            state.canWake -> "wake"
            else -> "sleep"
        }
    }
    when (dialog) {
        "stop" -> StopDialog(onStop = { dialog = null; view.haptic(Haptic.Confirm); repo.stopByOwner() }, onDismiss = { dialog = null })
        "wake" -> WakeDialog(onWake = { dialog = null; view.haptic(Haptic.Confirm); repo.wake() }, onDismiss = { dialog = null })
        "sleep" -> AlertDialog(
            onDismissRequest = { dialog = null },
            containerColor = G.surface,
            icon = { Icon(painterResource(R.drawable.ic_power), contentDescription = null, tint = G.primary) },
            title = { Text(stringResource(R.string.sleep_title), color = G.text) },
            text = { Text(stringResource(R.string.sleep_hint), style = MaterialTheme.typography.bodyMedium, color = G.textSecondary) },
            confirmButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.got_it)) } },
        )
    }
}

/**
 * Connection at a glance: a dot (green when the grill is heard, grey when off, pulsing while
 * searching) and the two channels lit when live. A tap opens the details; nothing stays on screen.
 */
@Composable
private fun ConnectionButton(state: UiState, onRefresh: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    val v = state.view
    val bleLive = v != null && Source.BLE in v.sources && state.channel != Channel.CLOUD
    val cloudLive = v?.cloudConnected == true && state.channel != Channel.BLE
    Box {
        Row(
            Modifier.clip(CircleShape).background(if (open) G.primaryContainer else G.surface.copy(alpha = 0.7f))
                .clickable { open = true }.heightIn(min = 44.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when {
                v == null -> Dot(G.textMuted, 8, pulsing = true)
                v.phase == CookPhase.OFF -> Dot(G.textMuted, 8)
                else -> Dot(G.ok, 8)
            }
            Spacer(Modifier.width(7.dp))
            Icon(painterResource(R.drawable.ic_bluetooth), contentDescription = stringResource(R.string.source_ble), tint = if (bleLive) G.ble else G.textMuted, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(3.dp))
            Icon(painterResource(R.drawable.ic_cloud), contentDescription = stringResource(R.string.source_cloud), tint = if (cloudLive) G.cloud else G.textMuted, modifier = Modifier.size(17.dp))
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            shape = RoundedCornerShape(20.dp),
            containerColor = G.surface,
        ) {
            ConnectionDetails(state, onRefresh = { onRefresh(); open = false })
        }
    }
}

@Composable
private fun ConnectionDetails(state: UiState, onRefresh: () -> Unit) {
    val v = state.view
    Column(Modifier.width(300.dp).padding(horizontal = 14.dp, vertical = 2.dp)) {
        val heard = v != null && Source.BLE in v.sources
        ConnLine(
            R.drawable.ic_bluetooth, G.ble, stringResource(R.string.source_ble),
            when {
                state.channel == Channel.CLOUD -> stringResource(R.string.conn_disabled)
                state.bleProblem == BleProblem.BLUETOOTH_OFF -> stringResource(R.string.ble_phone_off)
                heard -> stringResource(R.string.ble_heard, ago(v?.beaconAgeMs))
                else -> stringResource(R.string.ble_not_heard)
            },
            live = heard && state.channel != Channel.CLOUD,
        )
        HorizontalDivider(color = G.surfaceAlt)
        ConnLine(
            R.drawable.ic_cloud, G.cloud, stringResource(R.string.source_cloud),
            when {
                state.channel == Channel.BLE -> stringResource(R.string.conn_disabled)
                !state.loggedIn -> stringResource(R.string.cloud_no_account)
                v?.cloudConnected == true -> stringResource(R.string.cloud_connected_ago, ago(v.cloudUpdatedAgeMs))
                v?.cloudConnected == false -> stringResource(R.string.cloud_offline)
                else -> "—"
            },
            live = v?.cloudConnected == true && state.channel != Channel.BLE,
        )
        HorizontalDivider(color = G.surfaceAlt)
        ConnLine(
            R.drawable.ic_flame, G.textSecondary, v?.model?.let { ltr(it) } ?: stringResource(R.string.detail_model),
            stringResource(
                when (state.channel) {
                    Channel.AUTO -> R.string.conn_commands_auto
                    Channel.BLE -> R.string.conn_commands_ble
                    Channel.CLOUD -> R.string.conn_commands_cloud
                },
            ),
            live = null,
        )
        TextButton(onClick = onRefresh, modifier = Modifier.align(Alignment.End)) {
            Icon(painterResource(R.drawable.ic_refresh), contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.conn_refresh))
        }
    }
}

@Composable
private fun ConnLine(icon: Int, tint: Color, title: String, subtitle: String, live: Boolean?) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(tint.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (live != null) {
                    Dot(if (live) G.ok else G.textMuted, 7)
                    Spacer(Modifier.width(6.dp))
                }
                Text(title, style = MaterialTheme.typography.bodyLarge, color = G.text)
            }
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = G.textSecondary)
        }
    }
}

// ------------------------------------------------------------------ setup problems

/**
 * Only what actually blocks something. Bluetooth switched off on the phone is just a note while
 * the cloud carries everything (the owner woke and ran the grill that way); without the cloud it
 * is the one real problem, and says so.
 */
@Composable
private fun Problems(state: UiState, onGrant: () -> Unit, onEnableBluetooth: () -> Unit, onLogin: () -> Unit, onSettings: () -> Unit) {
    if (state.demo) return
    val cloudCarries = state.channel == Channel.AUTO && state.loggedIn && state.view?.cloudConnected == true
    if (state.channel != Channel.CLOUD) {
        when (state.bleProblem) {
            BleProblem.NO_PERMISSION -> Banner(stringResource(R.string.need_bt_permission), stringResource(R.string.grant), onGrant)
            BleProblem.BLUETOOTH_OFF ->
                if (cloudCarries) Banner(stringResource(R.string.bt_off_cloud), stringResource(R.string.enable_bt), onEnableBluetooth, info = true)
                else Banner(stringResource(R.string.bt_off_alone), stringResource(R.string.enable_bt), onEnableBluetooth)
            BleProblem.NO_KEY -> Banner(stringResource(R.string.no_key), stringResource(R.string.settings), onSettings)
            BleProblem.UNSUPPORTED, null -> Unit
        }
    }
    if (state.channel != Channel.BLE) {
        when {
            state.sessionExpired -> Banner(stringResource(R.string.session_expired), stringResource(R.string.login_now), onLogin)
            !state.loggedIn -> Banner(stringResource(R.string.not_logged_in), stringResource(R.string.login_now), onLogin)
            state.cloudError != null -> Banner(stringResource(R.string.cloud_error, state.cloudError), null, null, error = true)
        }
    }
}

@Composable
private fun Banner(text: String, action: String?, onAction: (() -> Unit)?, error: Boolean = false, info: Boolean = false) {
    val bg = when {
        error -> G.dangerContainer
        info -> G.surface.copy(alpha = 0.8f)
        else -> G.primaryContainer
    }
    val fg = when {
        error -> G.onDangerContainer
        info -> G.textSecondary
        else -> G.onPrimaryContainer
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(bg).padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp).heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(if (info) R.drawable.ic_bluetooth else R.drawable.ic_warning), contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = fg, modifier = Modifier.weight(1f))
        if (action != null && onAction != null) TextButton(onClick = onAction) { Text(action, color = if (info) G.primary else fg) }
    }
}

// ------------------------------------------------------------------ actions

/**
 * The bottom bar, always under the thumb, one action at a time. Cooking (or a start the grill has
 * taken): Stop, asks once. Sending a start: a busy button. Ready: light the grill with the plan
 * above (asks once). Panel off: switch it on (asks once). Unheard: the reason, no button.
 */
@Composable
private fun ActionBar(state: UiState, repo: GrillRepository, stage: Stage, onIgnite: () -> Unit) {
    val phase = state.view?.phase
    val busy = state.command is CommandStatus.Busy
    val view = LocalView.current
    var confirmStop by remember { mutableStateOf(false) }
    var confirmWake by remember { mutableStateOf(false) }
    if (confirmStop) {
        StopDialog(
            onStop = { confirmStop = false; view.haptic(Haptic.Confirm); repo.stopByOwner() },
            onDismiss = { confirmStop = false },
        )
    }
    if (confirmWake) {
        WakeDialog(
            onWake = { confirmWake = false; view.haptic(Haptic.Confirm); repo.wake() },
            onDismiss = { confirmWake = false },
        )
    }
    Column(
        Modifier
            .fillMaxWidth()
            .background(G.bg.copy(alpha = 0.94f))
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        val startTaken = stage == Stage.STARTING && state.command is CommandStatus.Done
        when {
            stage == Stage.COOKING || startTaken -> Button(
                onClick = { view.haptic(Haptic.Tap); confirmStop = true },
                enabled = state.canControl || startTaken,
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = G.danger, contentColor = G.onDanger),
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) { BarLabel(R.drawable.ic_stop, stringResource(R.string.stop), busy) }
            stage == Stage.STARTING -> Button(
                onClick = {}, enabled = false, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().height(56.dp),
            ) { BarLabel(R.drawable.ic_play, stringResource(R.string.starting_sending), busy = true) }
            state.canControl -> Button(
                onClick = { view.haptic(Haptic.Tap); onIgnite() },
                enabled = !busy,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) { BarLabel(R.drawable.ic_play, stringResource(R.string.ignite), busy) }
            // Panel switched itself off: switch it back on from here, no trip to the grill.
            state.canWake -> {
                val press = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                Row(
                    Modifier.fillMaxWidth().height(56.dp).control(RoundedCornerShape(16.dp), false, press, enabled = !busy)
                        .clickable(interactionSource = press, indication = androidx.compose.material3.ripple(), enabled = !busy) { view.haptic(Haptic.Tap); confirmWake = true },
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(32.dp).clip(CircleShape).background(G.danger.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                        if (busy) CircularProgressIndicator(Modifier.size(18.dp), color = G.danger, strokeWidth = 2.dp)
                        else Icon(painterResource(R.drawable.ic_power), contentDescription = null, tint = G.danger, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.wake), style = MaterialTheme.typography.titleMedium, color = G.danger)
                }
            }
            else -> Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(G.surfaceAlt).heightIn(min = 56.dp).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(painterResource(R.drawable.ic_warning), contentDescription = null, tint = G.textMuted, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    stringResource(if (phase == CookPhase.OFF) R.string.action_off else R.string.action_unreachable),
                    style = MaterialTheme.typography.bodyMedium, color = G.textSecondary,
                )
            }
        }
    }
}

/** Waking asks once too: it reaches the grill from anywhere (Bluetooth or cloud), so no stray taps. */
@Composable
private fun WakeDialog(onWake: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = G.surface,
        icon = { Icon(painterResource(R.drawable.ic_power), contentDescription = null, tint = G.primary) },
        title = { Text(stringResource(R.string.confirm_wake_title), color = G.text) },
        text = { Text(stringResource(R.string.confirm_wake_hint), style = MaterialTheme.typography.bodyMedium, color = G.textSecondary) },
        confirmButton = { Button(onClick = onWake) { Text(stringResource(R.string.wake_short)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Stopping asks once, so a pocket tap never ends a cook; the stop itself is the big red button. */
@Composable
private fun StopDialog(onStop: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = G.surface,
        icon = { Icon(painterResource(R.drawable.ic_stop), contentDescription = null, tint = G.danger) },
        title = { Text(stringResource(R.string.confirm_stop_title), color = G.text) },
        text = { Text(stringResource(R.string.confirm_stop_hint), style = MaterialTheme.typography.bodyMedium, color = G.textSecondary) },
        confirmButton = {
            Button(onClick = onStop, colors = ButtonDefaults.buttonColors(containerColor = G.danger, contentColor = G.onDanger)) {
                Text(stringResource(R.string.stop))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.keep_cooking)) } },
    )
}

@Composable
private fun BarLabel(icon: Int, label: String, busy: Boolean) {
    if (busy) {
        CircularProgressIndicator(Modifier.size(20.dp), color = androidx.compose.material3.LocalContentColor.current, strokeWidth = 2.dp)
    } else {
        Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(20.dp))
    }
    Spacer(Modifier.width(10.dp))
    Text(label, style = MaterialTheme.typography.titleMedium)
}

/** Results land as a snackbar: "Done via Bluetooth" or what went wrong. A start shows itself instead. */
@Composable
private fun CommandFeedback(command: CommandStatus?, host: SnackbarHostState) {
    val view = LocalView.current
    val done = (command as? CommandStatus.Done)?.takeIf { it.kind != com.example.mygrill.data.CommandKind.START }?.let {
        stringResource(R.string.cmd_done_via, stringResource(if (it.via == Source.BLE) R.string.source_ble else R.string.source_cloud))
    }
    val failed = (command as? CommandStatus.Failed)?.let { stringResource(R.string.cmd_failed, it.message) }
    LaunchedEffect(command?.at, command?.javaClass) {
        when {
            done != null -> { view.haptic(Haptic.Confirm); host.showSnackbar(done) }
            failed != null -> { view.haptic(Haptic.Reject); host.showSnackbar(failed) }
        }
    }
}
