package com.amayra.maya.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Dark-first Maya palette
val Bg = Color(0xFF0B0E14)
val Surface = Color(0xFF141926)
val SurfaceHigh = Color(0xFF1C2233)
val Primary = Color(0xFF7C4DFF)
val PrimaryDim = Color(0xFF512DA8)
val Accent = Color(0xFF00E5C7)
val TextMain = Color(0xFFE8EAF2)
val TextDim = Color(0xFF9AA3B5)
val Danger = Color(0xFFFF5470)
val Warn = Color(0xFFFFB454)
val Ok = Color(0xFF4CE0A6)

private val MayaColors = darkColorScheme(
    primary = Primary,
    onPrimary = Color.White,
    secondary = Accent,
    background = Bg,
    onBackground = TextMain,
    surface = Surface,
    onSurface = TextMain,
    surfaceVariant = SurfaceHigh,
    onSurfaceVariant = TextDim,
    error = Danger,
    outline = TextDim
)

private val MayaTypography = Typography(
    headlineMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 26.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 21.sp),
    bodyMedium = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
    labelMedium = TextStyle(fontSize = 11.sp, letterSpacing = 0.8.sp, fontWeight = FontWeight.Medium)
)

@Composable
fun MayaTheme(content: @Composable () -> Unit) {
    // Dark-first companion: always dark, independent of system setting.
    MaterialTheme(
        colorScheme = MayaColors,
        typography = MayaTypography,
        content = content
    )
}
