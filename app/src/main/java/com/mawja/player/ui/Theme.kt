package com.mawja.player.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.mawja.player.data.ThemeMode

// MyPlayer's teal + paper + amber idea, reworked into a more vivid Mawja palette.
private val Jade = Color(0xFF0B7A75)
private val JadeBright = Color(0xFF39D6B1)
private val Coral = Color(0xFFE76F51)
private val Amber = Color(0xFFF2B84B)
private val Paper = Color(0xFFFFF8EE)
private val Ink = Color(0xFF1B1814)
private val Night = Color(0xFF0C1716)

private val MawjaDarkColors = darkColorScheme(
    primary = JadeBright,
    onPrimary = Color(0xFF003A35),
    primaryContainer = Color(0xFF075E59),
    onPrimaryContainer = Color(0xFFB9F5E3),
    secondary = Color(0xFFFF9E83),
    onSecondary = Color(0xFF4A160D),
    secondaryContainer = Color(0xFF723222),
    onSecondaryContainer = Color(0xFFFFDAD0),
    tertiary = Color(0xFFFFD166),
    onTertiary = Color(0xFF392A00),
    background = Night,
    onBackground = Color(0xFFF7EFE5),
    surface = Color(0xFF13231F),
    onSurface = Color(0xFFF7EFE5),
    surfaceVariant = Color(0xFF24423A),
    onSurfaceVariant = Color(0xFFB8D5C9),
    outline = Color(0xFF709A8B),
    error = Color(0xFFFFB4AB),
)

private val MawjaLightColors = lightColorScheme(
    primary = Jade,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC4F1E2),
    onPrimaryContainer = Color(0xFF003B35),
    secondary = Coral,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFD9CF),
    onSecondaryContainer = Color(0xFF4A160D),
    tertiary = Amber,
    onTertiary = Color(0xFF3A2A00),
    background = Paper,
    onBackground = Ink,
    surface = Color(0xFFFFFCF7),
    onSurface = Ink,
    surfaceVariant = Color(0xFFE3F1EB),
    onSurfaceVariant = Color(0xFF3E5D54),
    outline = Color(0xFF6D8E83),
    error = Color(0xFFBA1A1A),
)

@Composable
fun MawjaTheme(themeMode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    MaterialTheme(colorScheme = if (dark) MawjaDarkColors else MawjaLightColors, content = content)
}
