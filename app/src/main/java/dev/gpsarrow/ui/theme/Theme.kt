package dev.gpsarrow.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// High contrast, sunlight-readable. This is an app people use outdoors with wet hands and
// sunglasses on, so the palette is deliberately loud rather than tasteful.
private val Amber = Color(0xFFFFB300)
private val AmberDark = Color(0xFFC68400)
private val Ink = Color(0xFF0E1013)
private val Slate = Color(0xFF1B1F24)
private val Good = Color(0xFF4CAF50)

private val DarkScheme = darkColorScheme(
    primary = Amber,
    onPrimary = Ink,
    secondary = Good,
    background = Ink,
    onBackground = Color(0xFFECEFF1),
    surface = Slate,
    onSurface = Color(0xFFECEFF1),
    surfaceVariant = Color(0xFF262B31),
    onSurfaceVariant = Color(0xFFB0BEC5),
    error = Color(0xFFFF6E6E),
)

private val LightScheme = lightColorScheme(
    primary = AmberDark,
    onPrimary = Color.White,
    secondary = Color(0xFF2E7D32),
    background = Color(0xFFFAFAFA),
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    error = Color(0xFFB3261E),
)

private val AppTypography = Typography(
    displayLarge = TextStyle(fontSize = 72.sp, fontWeight = FontWeight.Bold),
    headlineMedium = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun GpsArrowTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        typography = AppTypography,
        content = content,
    )
}
