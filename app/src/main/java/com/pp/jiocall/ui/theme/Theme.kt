package com.pp.jiocall.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColors = darkColorScheme(
    primary = GooglePrimary,
    onPrimary = GoogleOnPrimary,
    primaryContainer = GooglePrimaryContainer,
    onPrimaryContainer = GoogleOnPrimaryContainer,
    secondary = GoogleSecondary,
    onSecondary = GoogleOnPrimary,
    secondaryContainer = GoogleSecondaryContainer,
    onSecondaryContainer = Color(0xFFC8F5FF),
    tertiary = Color(0xFF9CA8FF),
    background = GoogleBgDark,
    onBackground = GoogleTextPrimary,
    surface = GoogleSurfaceDark,
    onSurface = GoogleTextPrimary,
    surfaceVariant = GoogleSurfaceVariant,
    onSurfaceVariant = GoogleTextSecondary,
    surfaceContainerLowest = GoogleBgDark,
    surfaceContainerLow = GoogleSurfaceDark,
    surfaceContainer = GoogleSurfaceContainer,
    surfaceContainerHigh = GoogleSurfaceContainerHigh,
    surfaceContainerHighest = GoogleSurfaceContainerHighest,
    outline = GoogleOutline,
    outlineVariant = GoogleOutlineVariant,
    error = GoogleCallEnd,
    onError = Color(0xFF2A0A0A),
    errorContainer = Color(0xFF4A1414),
    onErrorContainer = Color(0xFFFFD9D9),
    inverseSurface = GoogleTextPrimary,
    inverseOnSurface = GoogleSurfaceDark,
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF1A73E8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3E3FD),
    onPrimaryContainer = Color(0xFF041E49),
    secondary = Color(0xFF1E8E3E),
    onSecondary = Color.White,
    background = Color(0xFFFBFBFD),
    onBackground = Color(0xFF1A1C1E),
    surface = Color(0xFFFBFBFD),
    onSurface = Color(0xFF1A1C1E),
    onSurfaceVariant = Color(0xFF5F6368),
    surfaceVariant = Color(0xFFEFF2F7),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF4F6FA),
    surfaceContainer = Color(0xFFEFF2F7),
    surfaceContainerHigh = Color(0xFFE7EBF2),
    outline = Color(0xFF74777F),
    outlineVariant = Color(0xFFE0E4EC),
    error = Color(0xFFD93025),
)

@Composable
fun JioCallTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColors else LightColors
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !darkTheme
            controller.isAppearanceLightNavigationBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content,
    )
}

/** Kept for compatibility */
@Composable
fun JioCallLightTheme(content: @Composable () -> Unit) = JioCallTheme(darkTheme = false, content = content)

@Composable
fun currentIsDark(): Boolean = isSystemInDarkTheme()
