package io.github.aleixrodriala.noteai.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.aleixrodriala.noteai.data.ThemeMode

/** Colors Material doesn't have a slot for. */
@Immutable
data class ExtraColors(val record: Color, val onRecord: Color, val subtle: Color)

val LocalExtraColors = staticCompositionLocalOf { ExtraColors(Color(0xFFE5484D), Color.White, Color(0xFFF4F4F5)) }

// Ink on paper: near-black on white, one red for "recording". Calm and typographic.
private val Light = lightColorScheme(
    primary = Color(0xFF111113),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEDEDEF),
    onPrimaryContainer = Color(0xFF111113),
    secondary = Color(0xFF3F3F46),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF1F1F3),
    onSecondaryContainer = Color(0xFF18181B),
    background = Color.White,
    onBackground = Color(0xFF111113),
    surface = Color.White,
    onSurface = Color(0xFF111113),
    surfaceVariant = Color(0xFFF4F4F5),
    onSurfaceVariant = Color(0xFF71717A),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFAFAFA),
    surfaceContainer = Color(0xFFF4F4F5),
    surfaceContainerHigh = Color(0xFFEEEEF0),
    surfaceContainerHighest = Color(0xFFE8E8EA),
    outline = Color(0xFFD4D4D8),
    outlineVariant = Color(0xFFEAEAEC),
    error = Color(0xFFD93036),
    onError = Color.White,
    errorContainer = Color(0xFFFDECEC),
    onErrorContainer = Color(0xFF8A1C20),
    inverseSurface = Color(0xFF18181B),
    inverseOnSurface = Color(0xFFF4F4F5),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFF4F4F5),
    onPrimary = Color(0xFF111113),
    primaryContainer = Color(0xFF27272A),
    onPrimaryContainer = Color(0xFFF4F4F5),
    secondary = Color(0xFFD4D4D8),
    onSecondary = Color(0xFF18181B),
    secondaryContainer = Color(0xFF232326),
    onSecondaryContainer = Color(0xFFF4F4F5),
    background = Color(0xFF0B0B0C),
    onBackground = Color(0xFFF4F4F5),
    surface = Color(0xFF0B0B0C),
    onSurface = Color(0xFFF4F4F5),
    surfaceVariant = Color(0xFF1C1C1F),
    onSurfaceVariant = Color(0xFFA1A1AA),
    surfaceContainerLowest = Color(0xFF070708),
    surfaceContainerLow = Color(0xFF121214),
    surfaceContainer = Color(0xFF1C1C1F),
    surfaceContainerHigh = Color(0xFF232326),
    surfaceContainerHighest = Color(0xFF2B2B2F),
    outline = Color(0xFF3F3F46),
    outlineVariant = Color(0xFF27272A),
    error = Color(0xFFFF6369),
    onError = Color(0xFF2B0507),
    errorContainer = Color(0xFF3B1214),
    onErrorContainer = Color(0xFFFFB4B6),
    inverseSurface = Color(0xFFF4F4F5),
    inverseOnSurface = Color(0xFF18181B),
)

private val base = Typography()
private val AppTypography = Typography(
    displayLarge = base.displayLarge.copy(fontWeight = FontWeight.Light, letterSpacing = (-1).sp),
    headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.Bold, fontSize = 32.sp, lineHeight = 38.sp, letterSpacing = (-0.6).sp),
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = (-0.5).sp),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 23.sp, letterSpacing = (-0.1).sp),
    bodyLarge = base.bodyLarge.copy(fontSize = 17.sp, lineHeight = 27.sp, letterSpacing = 0.sp),
    bodyMedium = base.bodyMedium.copy(fontSize = 15.sp, lineHeight = 21.sp, letterSpacing = 0.sp),
    labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
)

private val AppShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun NoteTheme(mode: ThemeMode = ThemeMode.SYSTEM, dynamic: Boolean = false, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val scheme: ColorScheme = when {
        dynamic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> Dark
        else -> Light
    }
    val extra = ExtraColors(
        record = if (dark) Color(0xFFFF5A5F) else Color(0xFFE5484D),
        onRecord = Color.White,
        subtle = scheme.surfaceContainer,
    )
    androidx.compose.runtime.CompositionLocalProvider(LocalExtraColors provides extra) {
        MaterialTheme(colorScheme = scheme, typography = AppTypography, shapes = AppShapes, content = content)
    }
}

val TabularNumbers = TextStyle(fontFeatureSettings = "tnum")
