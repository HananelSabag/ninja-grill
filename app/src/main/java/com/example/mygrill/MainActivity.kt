package com.example.mygrill

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.mygrill.data.GrillRepository
import com.example.mygrill.data.MonitorService
import com.example.mygrill.ui.DashboardScreen
import com.example.mygrill.ui.LoginScreen
import com.example.mygrill.ui.SettingsScreen
import com.example.mygrill.ui.ThermometerFinder
import com.example.mygrill.ui.theme.MyGrillTheme
import com.example.mygrill.ui.theme.isDark

class MainActivity : ComponentActivity() {
    private val repo by lazy { GrillRepository.get(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        importDebugKey(intent)
        setContent {
            val state by repo.state.collectAsStateWithLifecycle()
            val dark = isDark(state.themeMode)
            // A cook seen while the app is open keeps being watched after it closes.
            LaunchedEffect(state.cookActive) { if (state.cookActive) MonitorService.start(this@MainActivity) }
            // Status and navigation bar icons follow the in-app theme, not only the system's.
            LaunchedEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            MyGrillTheme(dark) {
                // The app speaks Hebrew whatever the phone's language, so lay it out right-to-left too.
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) { App(repo) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        importDebugKey(intent)
    }

    override fun onStart() {
        super.onStart()
        repo.hold("ui")
    }

    override fun onStop() {
        repo.release("ui")
        super.onStop()
    }

    /**
     * Debug builds only: load the BLE key bundle from the computer, so it never has to be typed:
     *   adb shell am start -n com.example.mygrill/.MainActivity --es key_bundle <base64> --es grill_address <MAC>
     */
    private fun importDebugKey(intent: Intent?) {
        if (!BuildConfig.DEBUG) return
        // Debug only: a made-up grill to look at the screens (--es demo idle|preheat|cooking|timer|done|off|real).
        intent?.getStringExtra(EXTRA_DEMO)?.let { repo.setDemo(it.takeIf { n -> n != "real" }); intent.removeExtra(EXTRA_DEMO) }
        intent?.getStringExtra(EXTRA_THEME)?.let { t -> runCatching { repo.setThemeMode(com.example.mygrill.data.ThemeMode.valueOf(t.uppercase())) }; intent.removeExtra(EXTRA_THEME) }
        val bundle = intent?.getStringExtra(EXTRA_KEY) ?: return
        val error = repo.importKey(bundle, intent.getStringExtra(EXTRA_ADDRESS))
        intent.removeExtra(EXTRA_KEY)
        val text = if (error == null) getString(R.string.key_saved) else getString(R.string.key_invalid, error)
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val EXTRA_KEY = "key_bundle"
        const val EXTRA_ADDRESS = "grill_address"
        const val EXTRA_DEMO = "demo"
        const val EXTRA_THEME = "theme"
    }
}

private enum class Screen { HOME, SETTINGS, FINDER, LOGIN }

@Composable
private fun App(repo: GrillRepository) {
    val state by repo.state.collectAsStateWithLifecycle()
    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
    LaunchedEffect(state.loggedIn) { if (state.loggedIn && screen == Screen.LOGIN) screen = Screen.HOME }
    if (!state.onboarded) {
        LoginScreen(state, repo::login, onSkip = repo::skipLogin, onClose = null)
        return
    }
    when (screen) {
        Screen.HOME -> DashboardScreen(
            state, repo,
            onLogin = { screen = Screen.LOGIN },
            onSettings = { screen = Screen.SETTINGS },
            onFindThermometer = { screen = Screen.FINDER },
        )
        Screen.SETTINGS -> SettingsScreen(
            state, repo,
            onBack = { screen = Screen.HOME },
            onLogin = { screen = Screen.LOGIN },
            onFindThermometer = { screen = Screen.FINDER },
        )
        Screen.FINDER -> ThermometerFinder(repo, onClose = { screen = Screen.SETTINGS })
        Screen.LOGIN -> LoginScreen(state, repo::login, onSkip = null, onClose = { screen = Screen.HOME })
    }
}
