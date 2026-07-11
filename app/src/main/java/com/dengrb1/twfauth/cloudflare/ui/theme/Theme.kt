package com.dengrb1.twfauth.cloudflare.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.dengrb1.twfauth.cloudflare.ui.model.ThemePreference

private val BrandPurple = Color(0xFF6C4BEF)
private val BrandPurpleLight = Color(0xFF9F8CFF)
private val Ink = Color(0xFF1B1B20)
private val Cloud = Color(0xFFF8F7FC)

private val LightColors = lightColorScheme(
    primary = BrandPurple,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE7DEFF),
    onPrimaryContainer = Color(0xFF21005D),
    secondary = Color(0xFF625B71),
    background = Cloud,
    surface = Color.White,
    surfaceVariant = Color(0xFFE8E5EE),
    onBackground = Ink,
    error = Color(0xFFBA1A1A),
)

private val DarkColors = darkColorScheme(
    primary = BrandPurpleLight,
    onPrimary = Color(0xFF2D136B),
    primaryContainer = Color(0xFF49329B),
    onPrimaryContainer = Color(0xFFE7DEFF),
    secondary = Color(0xFFCBC2DB),
    background = Color(0xFF121216),
    surface = Color(0xFF1B1B20),
    surfaceVariant = Color(0xFF48454E),
    error = Color(0xFFFFB4AB),
)

@Composable
fun TwoFactorTheme(
    preference: ThemePreference = ThemePreference.System,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val dark = when (preference) {
        ThemePreference.System -> isSystemInDarkTheme()
        ThemePreference.Light -> false
        ThemePreference.Dark -> true
    }
    val context = LocalContext.current
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && dark -> dynamicDarkColorScheme(context)
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicLightColorScheme(context)
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}
