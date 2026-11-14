package com.example.mygrill.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.example.mygrill.R
import com.example.mygrill.data.LoginPhase
import com.example.mygrill.data.UiState
import com.example.mygrill.ui.theme.G
import kotlin.math.roundToInt

/** First run: what an owner needs before any of this works, said once and plainly. */
@Composable
private fun Needs() {
    Card(padding = 16) {
        Text(stringResource(R.string.welcome_need_title), style = MaterialTheme.typography.titleSmall, color = G.text)
        listOf(R.string.welcome_need_grill, R.string.welcome_need_account, R.string.welcome_need_together).forEach { id ->
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.Top) {
                Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = G.primary, modifier = Modifier.size(18.dp).padding(top = 2.dp))
                Spacer(Modifier.width(10.dp))
                Text(stringResource(id), style = MaterialTheme.typography.bodyMedium, color = G.textSecondary)
            }
        }
    }
}

/**
 * Ninja account sign-in. The password goes straight to SharkNinja's sign-in server and is never
 * stored; only the returned token is kept (Keystore-encrypted). [onSkip] (first run only) starts in
 * Bluetooth-only mode: the app works without an account.
 */
@Composable
fun LoginScreen(state: UiState, onLogin: (String, String) -> Unit, onSkip: (() -> Unit)?, onClose: (() -> Unit)?) {
    var email by rememberSaveable { mutableStateOf(state.email.orEmpty()) }
    var password by remember { mutableStateOf("") }
    val busy = state.login == LoginPhase.Busy
    val failed = state.login as? LoginPhase.Failed
    val shake = rememberShake(failed?.at)
    val view = LocalView.current
    LaunchedEffect(failed?.at) { if (failed != null) view.haptic(Haptic.Reject) }
    if (onClose != null) BackHandler(onBack = onClose)
    val submit = {
        if (!busy && email.isNotBlank() && password.isNotEmpty()) {
            view.haptic(Haptic.Tap)
            onLogin(email, password)
        }
    }
    val fieldColors = TextFieldDefaults.colors(
        focusedContainerColor = G.surface, unfocusedContainerColor = G.surface, disabledContainerColor = G.surface,
        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, disabledIndicatorColor = Color.Transparent,
        focusedLabelColor = G.primary, unfocusedLabelColor = G.textSecondary, cursorColor = G.primary,
        focusedTextColor = G.text, unfocusedTextColor = G.text,
    )

    HeatBackground(heating = false) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Image(painterResource(R.drawable.grill_closed), contentDescription = null, modifier = Modifier.width(220.dp))
            Text(stringResource(R.string.login_title), style = MaterialTheme.typography.headlineSmall, color = G.text, textAlign = TextAlign.Center)
            Text(stringResource(R.string.login_subtitle), style = MaterialTheme.typography.bodyLarge, color = G.textSecondary, textAlign = TextAlign.Center)
            if (onSkip != null) Needs()
            VSpace(4)
            TextField(
                value = email, onValueChange = { email = it }, label = { Text(stringResource(R.string.email)) },
                singleLine = true, enabled = !busy, shape = RoundedCornerShape(14.dp), colors = fieldColors,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth(),
            )
            TextField(
                value = password, onValueChange = { password = it }, label = { Text(stringResource(R.string.password)) },
                singleLine = true, enabled = !busy, shape = RoundedCornerShape(14.dp), colors = fieldColors,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = submit,
                enabled = !busy && email.isNotBlank() && password.isNotEmpty(),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().height(56.dp).offset { IntOffset(shake.value.roundToInt(), 0) },
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(22.dp), color = G.onPrimary, strokeWidth = 2.5.dp)
                else Text(stringResource(R.string.login), style = MaterialTheme.typography.titleMedium)
            }
            if (failed != null) {
                Text(
                    if (failed.badCredentials) stringResource(R.string.login_failed_credentials) else stringResource(R.string.login_failed_other, failed.detail),
                    style = MaterialTheme.typography.bodyMedium, color = G.danger, textAlign = TextAlign.Center,
                )
            }
            Text(stringResource(R.string.login_privacy), style = MaterialTheme.typography.bodySmall, color = G.textMuted, textAlign = TextAlign.Center)
            if (onSkip != null) {
                TextButton(onClick = onSkip, enabled = !busy) { Text(stringResource(R.string.login_skip)) }
                Text(stringResource(R.string.login_skip_hint), style = MaterialTheme.typography.bodySmall, color = G.textMuted, textAlign = TextAlign.Center)
            }
            if (onClose != null) TextButton(onClick = onClose, enabled = !busy) { Text(stringResource(R.string.back)) }
        }
    }
}
