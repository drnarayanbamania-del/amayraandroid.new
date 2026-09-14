package com.amayra.scanner.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Brand palette from the spec: blue #1469E8 primary, white surfaces, black text.
// Accents come from the Bamania's Tech logo (neon blue/red/purple on black).
val BrandBlue = Color(0xFF1469E8)
val BrandBlueDark = Color(0xFF0D4FB8)
val BrandBlueContainer = Color(0xFFDCE7FD)
val NeonBlue = Color(0xFF2979FF)
val NeonPurple = Color(0xFF7C4DFF)
val NeonRed = Color(0xFFFF2D55)
val NeonGreen = Color(0xFF00C853)
val InkBlack = Color(0xFF0B0B0C)
val HeaderDarkTop = Color(0xFF07080D)
val HeaderDarkBottom = Color(0xFF101A38)
val SurfaceLight = Color(0xFFFFFFFF)
val BgLight = Color(0xFFF4F6FA)
val SurfaceDark = Color(0xFF171A20)
val BgDark = Color(0xFF0E1013)

private val LightColors = lightColorScheme(
    primary = BrandBlue,
    onPrimary = Color.White,
    primaryContainer = BrandBlueContainer,
    onPrimaryContainer = Color(0xFF062A66),
    secondary = BrandBlueDark,
    background = BgLight,
    onBackground = InkBlack,
    surface = SurfaceLight,
    onSurface = InkBlack,
    surfaceVariant = Color(0xFFEDF1F7),
    onSurfaceVariant = Color(0xFF44474E)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFAAC7FF),
    onPrimary = Color(0xFF0A305F),
    primaryContainer = BrandBlueDark,
    onPrimaryContainer = BrandBlueContainer,
    secondary = BrandBlue,
    background = BgDark,
    onBackground = Color(0xFFE2E2E9),
    surface = SurfaceDark,
    onSurface = Color(0xFFE2E2E9),
    surfaceVariant = Color(0xFF41474D),
    onSurfaceVariant = Color(0xFFC2C7CF)
)

@Composable
fun ScannerTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}
