package com.example.mygrill.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.example.mygrill.BuildConfig
import com.example.mygrill.R
import com.example.mygrill.data.Channel
import com.example.mygrill.data.GrillRepository
import com.example.mygrill.data.ThemeMode
import com.example.mygrill.data.UiState
import com.example.mygrill.ui.theme.G
import com.example.mygrill.ui.theme.Numeric

/**
 * Settings as one list grouped by topic: a row per setting with its icon, name and current value.
 * Short choices are made on the row itself; longer ones open a small panel from the bottom.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(state: UiState, repo: GrillRepository, onBack: () -> Unit, onLogin: () -> Unit, onFindThermometer: () -> Unit) {
    BackHandler(onBack = onBack)
    var panel by rememberSaveable { mutableStateOf<String?>(null) } // "channel" | "key" | "thermo" | "account"
    HeatBackground(heating = false) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            FilledTonalIconButton(onClick = onBack, modifier = Modifier.size(44.dp)) {
                Icon(painterResource(R.drawable.ic_back), contentDescription = stringResource(R.string.back), modifier = Modifier.size(20.dp))
            }
            Text(
                stringResource(R.string.settings), style = MaterialTheme.typography.headlineMedium, color = G.text,
                modifier = Modifier.padding(start = 4.dp, top = 10.dp, bottom = 4.dp),
            )

            Section(stringResource(R.string.section_cooking)) {
                SettingRow(R.drawable.ic_bell, stringResource(R.string.settings_notify), stringResource(R.string.settings_notify_hint)) {
                    Switch(checked = state.notify, onCheckedChange = repo::setNotify)
                }
                SettingRow(R.drawable.ic_thermo, stringResource(R.string.carryover_title), stringResource(R.string.carryover_hint)) {
                    MiniStepper(
                        ltr("+${delta(state.carryoverC.toDouble(), state.unitsF)}"),
                        onMinus = { repo.setCarryover(state.carryoverC - 1) },
                        onPlus = { repo.setCarryover(state.carryoverC + 1) },
                    )
                }
                SettingRow(R.drawable.ic_ruler, stringResource(R.string.settings_units), null) {
                    MiniSegmented(listOf(false to ltr("°C"), true to ltr("°F")), state.unitsF, repo::setUnitsF)
                }
            }

            Section(stringResource(R.string.section_grill)) {
                SettingRow(
                    R.drawable.ic_bluetooth, stringResource(R.string.settings_channel), stringResource(R.string.channel_hint),
                    onClick = { panel = "channel" },
                ) { Value(channelName(state.channel)) }
                SettingRow(R.drawable.ic_key, stringResource(R.string.settings_ble_key), state.grillAddress?.let { ltr(it) }, onClick = { panel = "key" }) {
                    if (state.keyLoaded) Chip(stringResource(R.string.key_ok), G.okContainer, G.onOkContainer)
                    else Chip(stringResource(R.string.key_missing), G.heatContainer, G.onHeatContainer)
                }
                val ext = state.external
                SettingRow(
                    R.drawable.ic_thermo, stringResource(R.string.settings_external),
                    ext?.let { (it.name ?: stringResource(R.string.ext_title)) } ?: stringResource(R.string.ext_none_short),
                    onClick = { if (ext == null) onFindThermometer() else panel = "thermo" },
                ) {
                    if (ext == null) Text(stringResource(R.string.ext_add), style = MaterialTheme.typography.labelLarge, color = G.primary)
                    else Value(stringResource(if (ext.connected) R.string.ext_connected else R.string.ext_searching))
                }
            }

            Section(stringResource(R.string.section_general)) {
                SettingRow(R.drawable.ic_sun, stringResource(R.string.settings_theme), null) {
                    MiniSegmented(
                        listOf(
                            ThemeMode.LIGHT to stringResource(R.string.theme_light),
                            ThemeMode.DARK to stringResource(R.string.theme_dark),
                            ThemeMode.SYSTEM to stringResource(R.string.theme_system_short),
                        ),
                        state.themeMode, repo::setThemeMode,
                    )
                }
                SettingRow(
                    R.drawable.ic_user, stringResource(R.string.settings_account),
                    if (state.loggedIn) state.email else stringResource(R.string.account_signed_out),
                    onClick = { panel = "account" },
                ) { Value(stringResource(if (state.loggedIn) R.string.account_connected else R.string.account_signed_out)) }
            }

            Text(
                stringResource(R.string.version, BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.labelMedium, color = G.textMuted,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 16.dp),
            )
        }
    }

    panel?.let { which ->
        ModalBottomSheet(
            onDismissRequest = { panel = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = G.surface,
        ) {
            Column(
                Modifier.fillMaxWidth().imePadding().padding(horizontal = 20.dp).padding(bottom = 24.dp).navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (which) {
                    "channel" -> ChannelPanel(state, repo)
                    "key" -> KeyPanel(state, repo, onDone = { panel = null })
                    "thermo" -> ThermoPanel(state, repo, onFind = { panel = null; onFindThermometer() }, onDone = { panel = null })
                    "account" -> AccountPanel(state, repo, onLogin = { panel = null; onLogin() })
                }
            }
        }
    }
}

// ------------------------------------------------------------------ panels

@Composable
private fun ChannelPanel(state: UiState, repo: GrillRepository) {
    PanelTitle(stringResource(R.string.settings_channel))
    Segmented(
        listOf(
            Channel.AUTO to stringResource(R.string.channel_auto),
            Channel.BLE to stringResource(R.string.channel_ble),
            Channel.CLOUD to stringResource(R.string.channel_cloud),
        ),
        state.channel, repo::setChannel,
    )
    Text(
        stringResource(
            when (state.channel) {
                Channel.AUTO -> R.string.channel_auto_hint
                Channel.BLE -> R.string.channel_ble_hint
                Channel.CLOUD -> R.string.channel_cloud_hint
            },
        ),
        style = MaterialTheme.typography.bodyMedium, color = G.textSecondary,
    )
}

@Composable
private fun KeyPanel(state: UiState, repo: GrillRepository, onDone: () -> Unit) {
    var bundle by remember { mutableStateOf("") }
    var address by remember { mutableStateOf(state.grillAddress.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    val view = LocalView.current
    PanelTitle(stringResource(R.string.settings_ble_key))
    Text(
        if (state.keyLoaded) stringResource(R.string.key_loaded, state.grillAddress ?: "—") else stringResource(R.string.key_missing),
        style = MaterialTheme.typography.bodyLarge, color = if (state.keyLoaded) G.ok else G.heat,
    )
    Field(bundle, { bundle = it }, stringResource(R.string.key_paste_hint), singleLine = false)
    Field(address, { address = it }, stringResource(R.string.grill_address_hint), singleLine = true, caps = true)
    error?.let { Text(stringResource(R.string.key_invalid, it), style = MaterialTheme.typography.bodySmall, color = G.danger) }
    Button(
        onClick = {
            error = repo.importKey(bundle, address.ifBlank { null })
            view.haptic(if (error == null) Haptic.Confirm else Haptic.Reject)
            if (error == null) onDone()
        },
        enabled = bundle.isNotBlank(),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().height(52.dp),
    ) { Text(stringResource(R.string.save)) }
}

@Composable
private fun ThermoPanel(state: UiState, repo: GrillRepository, onFind: () -> Unit, onDone: () -> Unit) {
    val ext = state.external ?: return
    PanelTitle(ext.name ?: stringResource(R.string.ext_title))
    Text(ltr(ext.address), style = MaterialTheme.typography.bodyMedium, color = G.textSecondary)
    val r = ext.reading?.takeIf { ext.connected }
    if (r == null) {
        Text(stringResource(R.string.ext_not_heard), style = MaterialTheme.typography.bodyMedium, color = G.textSecondary)
    } else {
        (0..1).forEach { i ->
            val where = when {
                r.docked.getOrNull(i) == true -> stringResource(R.string.ext_in_base)
                r.probesC.getOrNull(i) != null -> temp(r.probesC[i], state.unitsF)
                else -> "—"
            }
            Text(
                externalName(state, i) + ": " + where + (r.probeBatteryPct.getOrNull(i)?.let { " · " + stringResource(R.string.ext_battery, it) } ?: ""),
                style = MaterialTheme.typography.bodyLarge.merge(Numeric), color = G.text,
            )
        }
        r.baseBatteryPct?.let { Text(stringResource(R.string.ext_base_battery, it), style = MaterialTheme.typography.bodyMedium, color = G.textSecondary) }
    }
    // The base's slots aren't colour-coded: the owner says which colour sits in slot 1.
    FilledTonalButton(onClick = repo::swapExternalColours, shape = RoundedCornerShape(14.dp), modifier = Modifier.height(48.dp)) {
        Text(stringResource(R.string.ext_swap))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(onClick = onFind, shape = RoundedCornerShape(14.dp), modifier = Modifier.height(48.dp)) { Text(stringResource(R.string.ext_find)) }
        FilledTonalButton(onClick = { repo.clearExternal(); onDone() }, shape = RoundedCornerShape(14.dp), modifier = Modifier.height(48.dp)) {
            Text(stringResource(R.string.ext_remove))
        }
    }
}

@Composable
private fun AccountPanel(state: UiState, repo: GrillRepository, onLogin: () -> Unit) {
    PanelTitle(stringResource(R.string.settings_account))
    Text(
        if (state.loggedIn) stringResource(R.string.account_signed_in, state.email.orEmpty()) else stringResource(R.string.account_signed_out),
        style = MaterialTheme.typography.bodyLarge, color = G.text,
    )
    Text(stringResource(R.string.account_hint), style = MaterialTheme.typography.bodyMedium, color = G.textSecondary)
    if (state.loggedIn) {
        FilledTonalButton(onClick = repo::logout, shape = RoundedCornerShape(14.dp), modifier = Modifier.height(48.dp)) { Text(stringResource(R.string.logout)) }
    } else {
        Button(onClick = onLogin, shape = RoundedCornerShape(14.dp), modifier = Modifier.height(48.dp)) { Text(stringResource(R.string.login_now)) }
    }
}

@Composable
private fun PanelTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, color = G.text)
}

@Composable
private fun channelName(c: Channel): String = stringResource(
    when (c) {
        Channel.AUTO -> R.string.channel_auto
        Channel.BLE -> R.string.channel_ble
        Channel.CLOUD -> R.string.channel_cloud
    },
)

// ------------------------------------------------------------------ rows

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Text(title, style = MaterialTheme.typography.labelLarge, color = G.textSecondary, modifier = Modifier.padding(start = 8.dp, top = 18.dp, bottom = 6.dp))
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(G.surface).padding(vertical = 4.dp)) { content() }
}

/** One setting: icon tile, name (and a line under it), and its value or control at the end. */
@Composable
private fun SettingRow(icon: Int, title: String, subtitle: String?, onClick: (() -> Unit)? = null, trailing: @Composable () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .heightIn(min = 60.dp)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(11.dp)).background(G.primaryContainer), contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), contentDescription = null, tint = G.onPrimaryContainer, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = G.text)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = G.textSecondary, maxLines = 2)
        }
        Spacer(Modifier.width(8.dp))
        trailing()
        if (onClick != null) {
            Spacer(Modifier.width(4.dp))
            Icon(painterResource(R.drawable.ic_chevron_end), contentDescription = null, tint = G.textMuted, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun Value(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = G.textSecondary, maxLines = 1)
}

@Composable
private fun Chip(text: String, container: Color, content: Color) {
    Text(
        text, style = MaterialTheme.typography.labelLarge, color = content,
        modifier = Modifier.clip(CircleShape).background(container).padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/** A small segmented choice that fits at the end of a row. */
@Composable
private fun <T> MiniSegmented(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    val view = LocalView.current
    Row(Modifier.clip(RoundedCornerShape(11.dp)).background(G.surfaceAlt).padding(3.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        options.forEach { (value, label) ->
            val on = value == selected
            val bg by animateColorAsState(if (on) G.primary else Color.Transparent, tween(180), label = "mini")
            val fg by animateColorAsState(if (on) G.onPrimary else G.textSecondary, tween(180), label = "miniFg")
            Box(
                Modifier.heightIn(min = 36.dp).clip(RoundedCornerShape(9.dp)).background(bg)
                    .clickable(role = Role.RadioButton) { view.haptic(Haptic.Tap); onSelect(value) }.padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center,
            ) { Text(label, style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 1) }
        }
    }
}

@Composable
private fun MiniStepper(value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(11.dp)).background(G.surfaceAlt), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clickable(role = Role.Button, onClick = onMinus), contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.ic_minus), contentDescription = "-", tint = G.text, modifier = Modifier.size(16.dp))
        }
        Text(value, style = MaterialTheme.typography.titleMedium.merge(Numeric), color = G.text)
        Box(Modifier.size(40.dp).clickable(role = Role.Button, onClick = onPlus), contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.ic_plus), contentDescription = "+", tint = G.text, modifier = Modifier.size(16.dp))
        }
    }
}

/** Filled text field without the Material underline or outline: a tonal well, nothing else. */
@Composable
fun Field(value: String, onChange: (String) -> Unit, label: String, singleLine: Boolean, caps: Boolean = false) {
    TextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 2,
        maxLines = if (singleLine) 1 else 4,
        shape = RoundedCornerShape(14.dp),
        keyboardOptions = KeyboardOptions(
            capitalization = if (caps) KeyboardCapitalization.Characters else KeyboardCapitalization.None,
            autoCorrectEnabled = false,
        ),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = G.surfaceAlt, unfocusedContainerColor = G.surfaceAlt, disabledContainerColor = G.surfaceAlt,
            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, disabledIndicatorColor = Color.Transparent,
            focusedLabelColor = G.primary, unfocusedLabelColor = G.textSecondary, cursorColor = G.primary,
            focusedTextColor = G.text, unfocusedTextColor = G.text,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}
