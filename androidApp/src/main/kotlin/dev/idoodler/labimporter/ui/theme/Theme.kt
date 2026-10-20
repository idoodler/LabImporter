package dev.idoodler.labimporter.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext

// Fallback palette (pre-Android 12, or dynamic colour off) seeded from the app
// icon's blue card (#2F6BFF) and pink heart (#FF375F).
private val LightFallback = lightColorScheme(
    primary = Color(0xFF2559D6),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDBE1FF),
    onPrimaryContainer = Color(0xFF00174B),
    secondary = Color(0xFF585E71),
    secondaryContainer = Color(0xFFDDE2F9),
    tertiary = Color(0xFFB4234A),
    tertiaryContainer = Color(0xFFFFD9DE),
    onTertiaryContainer = Color(0xFF3F0014),
    background = Color(0xFFFAF8FF),
    surface = Color(0xFFFAF8FF),
)

private val DarkFallback = darkColorScheme(
    primary = Color(0xFFB3C5FF),
    onPrimary = Color(0xFF002A77),
    primaryContainer = Color(0xFF0040A7),
    onPrimaryContainer = Color(0xFFDBE1FF),
    secondary = Color(0xFFC1C6DD),
    secondaryContainer = Color(0xFF414659),
    tertiary = Color(0xFFFFB2BE),
    tertiaryContainer = Color(0xFF900A35),
    onTertiaryContainer = Color(0xFFFFD9DE),
    background = Color(0xFF121318),
    surface = Color(0xFF121318),
)

/**
 * Material 3 Expressive theme with Material You dynamic colour (Android 12+):
 * the palette follows the user's wallpaper, like the system apps.
 */
@Composable
fun LabImporterTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkFallback
        else -> LightFallback
    }
    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        motionScheme = MotionScheme.expressive(),
    ) {
        // Screens draw their own category wash instead of sitting in a Surface,
        // so make onSurface the default content colour (otherwise text falls
        // back to black, which disappears in dark mode).
        CompositionLocalProvider(LocalContentColor provides colorScheme.onSurface, content = content)
    }
}

/**
 * Category colours are fixed (they encode meaning across both platforms), but
 * nudged toward the dynamic primary so they sit comfortably in any palette.
 */
fun ColorScheme.harmonize(color: Color): Color = lerp(color, primary, 0.15f)
