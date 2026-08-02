package com.ciphershare.android.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Pulled directly from CipherShare (desktop) Themes/Colors.xaml, so the Android app matches
 * the desktop app's look exactly rather than approximating it. The desktop app is dark-only,
 * so this app is too - there's no light variant to fall back to.
 */
object CipherShareColors {
    val Background = Color(0xFF0D1117)
    val Surface = Color(0xFF161B22)
    val SurfaceHover = Color(0xFF1C2128)
    val Border = Color(0xFF30363D)
    val BorderHover = Color(0xFF484F58)
    val TextPrimary = Color(0xFFE6EDF3)
    val TextSecondary = Color(0xFFB1BAC4)
    val TextMuted = Color(0xFF8B949E)
    val Accent = Color(0xFF22D3EE)
    val AccentHover = Color(0xFF67E8F9)
    val Success = Color(0xFF3FB950)
    val Danger = Color(0xFFF85149)
    val DangerHover = Color(0xFFFF7B72)
    val Warning = Color(0xFFD29922)
}

private val CipherShareDarkScheme = darkColorScheme(
    background = CipherShareColors.Background,
    surface = CipherShareColors.Surface,
    surfaceVariant = CipherShareColors.SurfaceHover,
    primary = CipherShareColors.Accent,
    onPrimary = CipherShareColors.Background,
    secondary = CipherShareColors.TextSecondary,
    onSecondary = CipherShareColors.Background,
    onBackground = CipherShareColors.TextPrimary,
    onSurface = CipherShareColors.TextPrimary,
    onSurfaceVariant = CipherShareColors.TextSecondary,
    error = CipherShareColors.Danger,
    onError = CipherShareColors.TextPrimary,
    outline = CipherShareColors.Border,
    outlineVariant = CipherShareColors.BorderHover
)

private val CipherShareTypography = Typography(
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 22.sp, color = CipherShareColors.TextPrimary),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 17.sp, color = CipherShareColors.TextPrimary),
    bodyLarge = TextStyle(fontSize = 15.sp, color = CipherShareColors.TextPrimary),
    bodyMedium = TextStyle(fontSize = 14.sp, color = CipherShareColors.TextSecondary),
    bodySmall = TextStyle(fontSize = 12.sp, color = CipherShareColors.TextMuted),
    labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, color = CipherShareColors.TextPrimary)
)

@Composable
fun CipherShareTheme(content: @Composable () -> Unit) {
    // Dark-only, mirroring the desktop app (which has no light theme either).
    MaterialTheme(
        colorScheme = CipherShareDarkScheme,
        typography = CipherShareTypography,
        content = content
    )
}
