package com.nothing.mobilecore.ui

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/** Основной заливки глобуса на иконке. */
private val CyanPrimary = Color(0xFF28C6D9)

/** Светлые линии сетки / обводка на иконке. */
private val CyanAccent = Color(0xFF94E1EB)

private val LightColorScheme = lightColorScheme(
    primary = CyanPrimary,
    onPrimary = Color(0xFF001F24),
    primaryContainer = Color(0xFFB8EEF4),
    onPrimaryContainer = Color(0xFF001F24),
    secondary = Color(0xFF2A6A73),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = CyanAccent,
    onSecondaryContainer = Color(0xFF001F24),
    tertiary = Color(0xFF4A8B96),
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFF2FBFC),
    onBackground = Color(0xFF0D1F22),
    surface = Color(0xFFF2FBFC),
    onSurface = Color(0xFF0D1F22),
    surfaceVariant = Color(0xFFD0EEF2),
    onSurfaceVariant = Color(0xFF3E5356),
    outline = Color(0xFF6F8A8E),
    outlineVariant = Color(0xFFBFD9DE),
)

private val DarkColorScheme = darkColorScheme(
    primary = CyanAccent,
    onPrimary = Color(0xFF003038),
    primaryContainer = Color(0xFF004F5C),
    onPrimaryContainer = Color(0xFFB8EEF4),
    secondary = CyanPrimary,
    onSecondary = Color(0xFF001F24),
    secondaryContainer = Color(0xFF1A5C66),
    onSecondaryContainer = Color(0xFFB8EEF4),
    tertiary = Color(0xFF6DD4E5),
    onTertiary = Color(0xFF001F24),
    background = Color(0xFF0D1618),
    onBackground = Color(0xFFE0F6F9),
    surface = Color(0xFF0D1618),
    onSurface = Color(0xFFE0F6F9),
    surfaceVariant = Color(0xFF1E3539),
    onSurfaceVariant = Color(0xFFB0CDD2),
    outline = Color(0xFF7A959A),
    outlineVariant = Color(0xFF3A4F54),
)

@Composable
fun NothingVpnTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor =
                if (darkTheme) colorScheme.surface.toArgb() else colorScheme.primary.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }
    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
