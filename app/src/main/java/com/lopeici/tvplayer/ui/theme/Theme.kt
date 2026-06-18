package com.lopeici.tvplayer.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Accent = Color(0xFF1B98E0)

private val DarkColors = darkColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    background = Color(0xFF0D1B2A),
    onBackground = Color(0xFFE6EDF3),
    surface = Color(0xFF152436),
    onSurface = Color(0xFFE6EDF3),
)

private val LightColors = lightColorScheme(
    primary = Accent,
    onPrimary = Color.White,
)

@Composable
fun TvPlayerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}
