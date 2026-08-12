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

private val EditorialColorScheme = darkColorScheme(
    primary = Color.White,
    onPrimary = Color.Black,
    primaryContainer = EditorialGlassSurface,
    onPrimaryContainer = Color.White,
    secondary = ActiveGreen,
    onSecondary = Color.Black,
    tertiary = SteamCyan,
    background = EditorialBackground,
    onBackground = TextPrimaryDark,
    surface = EditorialSurfaceDark,
    onSurface = TextPrimaryDark,
    surfaceVariant = EditorialGlassSurface,
    onSurfaceVariant = TextSecondaryDark,
    outline = EditorialGlassBorder,
    error = ErrorRed
)

@Composable
fun DepotDownloaderTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = EditorialColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
