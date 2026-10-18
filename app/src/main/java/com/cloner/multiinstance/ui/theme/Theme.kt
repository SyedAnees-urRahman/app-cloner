package com.cloner.multiinstance.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val AppColorScheme = darkColorScheme(
    primary = Accent,
    onPrimary = OnAccent,
    primaryContainer = AccentContainer,
    onPrimaryContainer = OnAccentContainer,
    secondary = TextSecondary,
    onSecondary = Background,
    secondaryContainer = SurfaceVariant,
    onSecondaryContainer = TextPrimary,
    background = Background,
    onBackground = TextPrimary,
    surface = Surface,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceVariant,
    onSurfaceVariant = TextSecondary,
    surfaceTint = Accent,
    error = ErrorRed,
    onError = Background,
    errorContainer = ErrorContainer,
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Outline,
    outlineVariant = OutlineVariant,
    scrim = Color.Black,
    surfaceDim = Color(0xFF06080D),
    surfaceBright = Color(0xFF1B2130),
    surfaceContainerLowest = Color(0xFF06080D),
    surfaceContainerLow = Color(0xFF0D1119),
    surfaceContainer = Surface,
    surfaceContainerHigh = Color(0xFF151B29),
    surfaceContainerHighest = SurfaceVariant
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun AppClonerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AppColorScheme,
        typography = Typography(),
        shapes = AppShapes,
        content = content
    )
}
