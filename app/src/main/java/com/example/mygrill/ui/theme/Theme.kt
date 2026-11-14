package com.example.mygrill.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.example.mygrill.data.ThemeMode

/**
 * Colour roles for the app. The page is a sky: clear daytime blue fading to a pale horizon (a night
 * sky in dark), with soft clouds and a slow sun, and the grill photo lit in front of it. Light,
 * friendly tones throughout: azure is the primary, orange appears only where there is heat, red
 * only on Stop, green only when something is done. Light is the default (read beside a grill in daylight).
 */
@Immutable
data class GrillColors(
    val dark: Boolean,
    val bg: Color,
    /** Top of the sky: the deepest blue, the page fades down from it. */
    val groundTop: Color,
    /** The horizon at the bottom of the page. */
    val groundBottom: Color,
    /** The studio light behind the grill, white when idle and warm while it cooks. */
    val spot: Color,
    val spotWarm: Color,
    /** The soft frame around the page's edges. */
    val vignette: Color,
    /** The sun (the moon at night) drifting along the top of the sky. */
    val sun: Color,
    /** Soft clouds across the sky. */
    val haze: Color,
    /** Controls are raised (info surfaces stay flat): a gentle top-to-bottom tone, a lit top edge, a tinted shadow. */
    val controlTop: Color,
    val controlBottom: Color,
    val controlEdge: Color,
    val controlShadow: Color,
    /** The lighter top of a selected (primary) control's gradient. */
    val primaryHi: Color,
    val surface: Color,
    val surfaceAlt: Color,
    val surfaceStrong: Color,
    val text: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val heat: Color,
    val heatFill: Color,
    val heatContainer: Color,
    val onHeatContainer: Color,
    val ok: Color,
    val okContainer: Color,
    val onOkContainer: Color,
    val danger: Color,
    val onDanger: Color,
    val dangerContainer: Color,
    val onDangerContainer: Color,
    val ble: Color,
    val cloud: Color,
    val track: Color,
)

val LightGrill = GrillColors(
    dark = false,
    bg = Color(0xFFD8EEFF),
    groundTop = Color(0xFF8FD0FF),
    groundBottom = Color(0xFFF4FAFF),
    spot = Color(0xE6FFFFFF),
    spotWarm = Color(0xE6FFEBD9),
    vignette = Color(0x0A0B3A66),
    sun = Color(0xB3FFFBEA),
    haze = Color(0xB8FFFFFF),
    controlTop = Color(0xFFFFFFFF),
    controlBottom = Color(0xFFEEF6FD),
    controlEdge = Color(0xF2FFFFFF),
    controlShadow = Color(0xFF2B6CB0),
    primaryHi = Color(0xFF5AB2F6),
    surface = Color(0xFFFFFFFF),
    surfaceAlt = Color(0xFFF0F7FE),
    surfaceStrong = Color(0xFFDCEBF8),
    text = Color(0xFF0F2A44),
    textSecondary = Color(0xFF3F5B78),
    textMuted = Color(0xFF5E7893),
    primary = Color(0xFF1F7AD1),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDDEFFF),
    onPrimaryContainer = Color(0xFF0B3D6E),
    heat = Color(0xFFD24A0E),
    heatFill = Color(0xFFFF8A3D),
    heatContainer = Color(0xFFFFF0E4),
    onHeatContainer = Color(0xFF8A3410),
    ok = Color(0xFF0B8A4E),
    okContainer = Color(0xFFE2F8EC),
    onOkContainer = Color(0xFF0D5132),
    danger = Color(0xFFE5372B),
    onDanger = Color(0xFFFFFFFF),
    dangerContainer = Color(0xFFFFE6E3),
    onDangerContainer = Color(0xFF7A1F17),
    ble = Color(0xFF1F6FEB),
    cloud = Color(0xFF7A4CE0),
    track = Color(0xFFE1EEF9),
)

val DarkGrill = GrillColors(
    dark = true,
    bg = Color(0xFF0C1B2E),
    groundTop = Color(0xFF16375F),
    groundBottom = Color(0xFF070F1A),
    spot = Color(0x732E5B8A),
    spotWarm = Color(0x807A3A12),
    vignette = Color(0x80000000),
    sun = Color(0x40BFDFFF),
    haze = Color(0x14FFFFFF),
    controlTop = Color(0xFF22384F),
    controlBottom = Color(0xFF182A3D),
    controlEdge = Color(0x1FFFFFFF),
    controlShadow = Color(0xFF000000),
    primaryHi = Color(0xFFA9D8FF),
    surface = Color(0xFF112235),
    surfaceAlt = Color(0xFF172B41),
    surfaceStrong = Color(0xFF213851),
    text = Color(0xFFEEF5FC),
    textSecondary = Color(0xFFAFC3D8),
    textMuted = Color(0xFF8AA2BA),
    primary = Color(0xFF7CC2FF),
    onPrimary = Color(0xFF062441),
    primaryContainer = Color(0xFF173B5E),
    onPrimaryContainer = Color(0xFFD8ECFF),
    heat = Color(0xFFFFB27A),
    heatFill = Color(0xFFFF8F4D),
    heatContainer = Color(0xFF4A2512),
    onHeatContainer = Color(0xFFFFD9BF),
    ok = Color(0xFF5BE49B),
    okContainer = Color(0xFF113A26),
    onOkContainer = Color(0xFFC0F5D8),
    danger = Color(0xFFFF5A52),
    onDanger = Color(0xFFFFFFFF),
    dangerContainer = Color(0xFF4C1614),
    onDangerContainer = Color(0xFFFFCDC9),
    ble = Color(0xFF8EBBFF),
    cloud = Color(0xFFC3AEFF),
    track = Color(0xFF22374D),
)

val LocalGrillColors = staticCompositionLocalOf { LightGrill }

/** Shorthand for the current colours inside composables. */
val G: GrillColors
    @Composable @ReadOnlyComposable get() = LocalGrillColors.current

/** Tabular figures: temperatures and countdowns that change don't make the text jump. */
val Numeric = TextStyle(fontFeatureSettings = "tnum")

/** The live probe reading during a cook: the largest number on screen, sized to share it. */
val LiveNumeral = TextStyle(fontSize = 52.sp, lineHeight = 54.sp, fontWeight = FontWeight.Medium, letterSpacing = (-1.5).sp, fontFeatureSettings = "tnum")

private val AppTypography = Typography().run {
    copy(
        displayMedium = displayMedium.copy(fontWeight = FontWeight.Medium, letterSpacing = (-1).sp),
        headlineMedium = headlineMedium.copy(fontWeight = FontWeight.Medium),
        headlineSmall = headlineSmall.copy(fontWeight = FontWeight.Medium),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.Medium),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.Medium),
        labelLarge = labelLarge.copy(fontWeight = FontWeight.Medium),
    )
}

@Composable
fun isDark(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

@Composable
fun MyGrillTheme(dark: Boolean, content: @Composable () -> Unit) {
    val c = if (dark) DarkGrill else LightGrill
    val scheme = if (dark) {
        darkColorScheme(
            primary = c.primary, onPrimary = c.onPrimary,
            primaryContainer = c.primaryContainer, onPrimaryContainer = c.onPrimaryContainer,
            secondaryContainer = c.primaryContainer, onSecondaryContainer = c.onPrimaryContainer,
            background = c.bg, onBackground = c.text,
            surface = c.surface, onSurface = c.text, surfaceVariant = c.surfaceAlt, onSurfaceVariant = c.textSecondary,
            surfaceContainerLowest = c.surface, surfaceContainerLow = c.surface, surfaceContainer = c.surfaceAlt,
            surfaceContainerHigh = c.surfaceAlt, surfaceContainerHighest = c.surfaceStrong,
            outline = c.textMuted, outlineVariant = c.surfaceStrong, error = c.danger, onError = c.onDanger,
        )
    } else {
        lightColorScheme(
            primary = c.primary, onPrimary = c.onPrimary,
            primaryContainer = c.primaryContainer, onPrimaryContainer = c.onPrimaryContainer,
            secondaryContainer = c.primaryContainer, onSecondaryContainer = c.onPrimaryContainer,
            background = c.bg, onBackground = c.text,
            surface = c.surface, onSurface = c.text, surfaceVariant = c.surfaceAlt, onSurfaceVariant = c.textSecondary,
            surfaceContainerLowest = c.surface, surfaceContainerLow = c.surface, surfaceContainer = c.surfaceAlt,
            surfaceContainerHigh = c.surfaceAlt, surfaceContainerHighest = c.surfaceStrong,
            outline = c.textMuted, outlineVariant = c.surfaceStrong, error = c.danger, onError = c.onDanger,
        )
    }
    CompositionLocalProvider(LocalGrillColors provides c) {
        MaterialTheme(colorScheme = scheme, typography = AppTypography, content = content)
    }
}
