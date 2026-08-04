package com.example.ui.theme
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
private val DarkColorScheme = darkColorScheme(
    primary = NeonGreen,
    secondary = NeonGreenDim,
    tertiary = NeonGreen,
    background = PureBlack,
    surface = NearBlackSurface,
    onPrimary = PureBlack,
    onSecondary = PureBlack,
    onBackground = NeonGreen,
    onSurface = NeonGreen,
    surfaceVariant = NearBlackSurface.copy(alpha = 0.8f),
    onSurfaceVariant = TerminalTextSecondary,
    outline = TerminalDivider
)
private val LightColorScheme = lightColorScheme(
    primary = NeonGreen,
    secondary = NeonGreenDim,
    tertiary = NeonGreen,
    background = PureBlack, // Pure black terminal layout is requested so let's default to the dark theme colors
    surface = NearBlackSurface,
    onPrimary = PureBlack,
    onSecondary = PureBlack,
    onBackground = NeonGreen,
    onSurface = NeonGreen,
    surfaceVariant = NearBlackSurface.copy(alpha = 0.8f),
    onSurfaceVariant = TerminalTextSecondary,
    outline = TerminalDivider
)
@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false, // Set to false to enforce our custom theme colors exactly like the screenshots
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
