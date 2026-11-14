package com.example.mygrill.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.CompositionLocalProvider
import com.example.mygrill.R
import com.example.mygrill.core.Tempwise
import com.example.mygrill.data.FoundDevice
import com.example.mygrill.data.GattEvent
import com.example.mygrill.data.GrillRepository
import com.example.mygrill.ui.theme.G
import kotlinx.coroutines.flow.catch

/**
 * Finds the owner's wireless thermometer. A Tempwise base (listed first) is taken as is: it
 * broadcasts its readings, so nothing needs connecting. Anything else opens a raw view (GATT table
 * and live notifications in hex) for working out a new model.
 */
@Composable
fun ThermometerFinder(repo: GrillRepository, onClose: () -> Unit) {
    var picked by remember { mutableStateOf<FoundDevice?>(null) }
    BackHandler { if (picked != null) picked = null else onClose() }
    DisposableEffect(Unit) {
        repo.pauseExternal(true)
        onDispose { repo.pauseExternal(false) }
    }

    HeatBackground(heating = false) {
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.finder_title), style = MaterialTheme.typography.headlineSmall, color = G.text, modifier = Modifier.weight(1f))
                TextButton(onClick = { if (picked != null) picked = null else onClose() }) { Text(stringResource(R.string.back), color = G.textSecondary) }
            }
            val device = picked
            if (device == null) {
                // A Tempwise base broadcasts its readings: picking it is all it takes, no connection.
                DeviceList(repo) { d ->
                    if (Tempwise.looksLikeOne(d.name, d.serviceUuids)) {
                        repo.setExternal(d.address, d.name)
                        onClose()
                    } else {
                        picked = d
                    }
                }
            } else {
                Inspect(repo, device) {
                    repo.setExternal(device.address, device.name)
                    onClose()
                }
            }
        }
    }
}

@Composable
private fun DeviceList(repo: GrillRepository, onPick: (FoundDevice) -> Unit) {
    val found = remember { mutableStateMapOf<String, FoundDevice>() }
    LaunchedEffect(Unit) {
        repo.explorer.scanAll().catch { }.collect { found[it.address] = it }
    }
    Text(stringResource(R.string.finder_hint), style = MaterialTheme.typography.bodyMedium, color = G.textSecondary)
    val sorted = found.values.sortedWith(
        compareByDescending<FoundDevice> { Tempwise.looksLikeOne(it.name, it.serviceUuids) }.thenByDescending { it.rssi },
    )
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(sorted, key = { it.address }) { d ->
            val likely = Tempwise.looksLikeOne(d.name, d.serviceUuids)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (likely) G.primaryContainer else G.surface)
                    .clickable { onPick(d) }
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(d.name ?: stringResource(R.string.finder_unnamed), style = MaterialTheme.typography.titleMedium, color = G.text)
                    Text(ltr(d.address), style = MaterialTheme.typography.bodySmall, color = G.textMuted)
                    if (likely) Text(stringResource(R.string.finder_likely), style = MaterialTheme.typography.labelMedium, color = G.primary)
                }
                Text(ltr("${d.rssi} dBm"), style = MaterialTheme.typography.bodyMedium, color = G.textSecondary)
            }
        }
    }
}

@Composable
private fun Inspect(repo: GrillRepository, device: FoundDevice, onUse: () -> Unit) {
    var status by remember { mutableStateOf<String?>(null) }
    val services = remember { mutableStateListOf<String>() }
    val log = remember { mutableStateListOf<String>() }
    val connecting = stringResource(R.string.finder_connecting)
    val connected = stringResource(R.string.finder_connected)
    LaunchedEffect(device.address) {
        status = connecting
        repo.explorer.connect(device.address).catch { status = it.message }.collect { e ->
            when (e) {
                GattEvent.Connected -> status = connected
                is GattEvent.Services -> { services.clear(); services.addAll(e.lines) }
                is GattEvent.Notification -> {
                    val hex = e.value.joinToString(" ") { "%02x".format(it) }
                    log.add(0, "${e.uuid.substring(4, 8)}  $hex")
                    if (log.size > 40) log.removeAt(log.lastIndex)
                }
                is GattEvent.Closed -> status = e.reason
            }
        }
    }
    Text(device.name ?: stringResource(R.string.finder_unnamed), style = MaterialTheme.typography.titleLarge, color = G.text)
    Text(ltr(device.address) + "  ·  " + (status ?: ""), style = MaterialTheme.typography.bodySmall, color = G.textMuted)
    Button(
        onClick = onUse,
        colors = ButtonDefaults.buttonColors(containerColor = G.primary, contentColor = G.onPrimary),
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(R.string.finder_use)) }
    // Hex and UUIDs read left to right whatever the app's direction.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item { SectionTitle(stringResource(R.string.finder_live)) }
            items(log) { line ->
                Text(line, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), color = G.text)
            }
            item { Spacer(Modifier.width(1.dp)); SectionTitle(stringResource(R.string.finder_services)) }
            items(services) { line ->
                Text(line, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), color = G.textSecondary)
            }
        }
    }
}
