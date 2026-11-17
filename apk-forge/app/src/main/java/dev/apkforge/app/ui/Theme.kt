package dev.apkforge.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp

val Accent = Color(0xFF4FC1FF)
val AccentWarm = Color(0xFFFFB547)
val Danger = Color(0xFFFF6B6B)
val Ok = Color(0xFF7BD88F)

private val colors = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF00243A),
    primaryContainer = Color(0xFF0E3A55),
    onPrimaryContainer = Color(0xFFCBE8FF),
    secondary = AccentWarm,
    onSecondary = Color(0xFF2E1A00),
    secondaryContainer = Color(0xFF4A3410),
    onSecondaryContainer = Color(0xFFFFDDB0),
    tertiary = Ok,
    background = Color(0xFF0F1115),
    onBackground = Color(0xFFE2E4E9),
    surface = Color(0xFF0F1115),
    onSurface = Color(0xFFE2E4E9),
    surfaceVariant = Color(0xFF1C2028),
    onSurfaceVariant = Color(0xFFA9AFBC),
    surfaceContainer = Color(0xFF171A20),
    surfaceContainerHigh = Color(0xFF1D2128),
    surfaceContainerHighest = Color(0xFF242932),
    surfaceContainerLow = Color(0xFF14171C),
    outline = Color(0xFF3A404C),
    outlineVariant = Color(0xFF2A2F38),
    error = Danger,
)

val Mono = FontFamily.Monospace

@Composable
fun ApkForgeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = colors,
        typography = Typography(
            bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
        ),
        content = content,
    )
}
