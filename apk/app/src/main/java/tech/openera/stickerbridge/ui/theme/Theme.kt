package tech.openera.stickerbridge.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import tech.openera.stickerbridge.data.ThemeMode

// Taken from the logo: pink -> blue -> green. Blue leads; pink and green are accents.
private val Blue = Color(0xFF1767DB)
private val BlueLight = Color(0xFF8CBBFF)

private val Light = lightColorScheme(
    primary = Blue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE2ECFF),
    onPrimaryContainer = Color(0xFF0A2A66),
    secondary = Color(0xFFC2306F),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFE3EF),
    onSecondaryContainer = Color(0xFF4A0A27),
    tertiary = Color(0xFF13874A),
    onTertiary = Color.White,
    background = Color(0xFFFBFBFD),
    onBackground = Color(0xFF111418),
    surface = Color(0xFFFBFBFD),
    onSurface = Color(0xFF111418),
    surfaceVariant = Color(0xFFF1F2F5),
    onSurfaceVariant = Color(0xFF4B5260),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF6F7F9),
    surfaceContainer = Color(0xFFF1F2F5),
    surfaceContainerHigh = Color(0xFFECEEF2),
    surfaceContainerHighest = Color(0xFFE6E8ED),
    outline = Color(0xFFD5D9E0),
    outlineVariant = Color(0xFFE7E9EE),
    error = Color(0xFFB42318),
    onError = Color.White,
)

private val Dark = darkColorScheme(
    primary = BlueLight,
    onPrimary = Color(0xFF07224F),
    primaryContainer = Color(0xFF1B3A73),
    onPrimaryContainer = Color(0xFFDCE8FF),
    secondary = Color(0xFFFF8DBE),
    onSecondary = Color(0xFF4A0A27),
    secondaryContainer = Color(0xFF5E1A3A),
    onSecondaryContainer = Color(0xFFFFD9E8),
    tertiary = Color(0xFF6FDDA0),
    onTertiary = Color(0xFF00391C),
    background = Color(0xFF111317),
    onBackground = Color(0xFFECEEF2),
    surface = Color(0xFF111317),
    onSurface = Color(0xFFECEEF2),
    surfaceVariant = Color(0xFF22252B),
    onSurfaceVariant = Color(0xFFA9AFBA),
    surfaceContainerLowest = Color(0xFF0C0E11),
    surfaceContainerLow = Color(0xFF171A1E),
    surfaceContainer = Color(0xFF1C1F24),
    surfaceContainerHigh = Color(0xFF23262C),
    surfaceContainerHighest = Color(0xFF2B2E35),
    outline = Color(0xFF3A3F48),
    outlineVariant = Color(0xFF2A2E35),
    error = Color(0xFFFF8A80),
    onError = Color(0xFF4A0400),
)

/** Colours Material doesn't name: success states. */
@Immutable
data class Extra(val success: Color, val successContainer: Color, val onSuccessContainer: Color)

val LocalExtra = staticCompositionLocalOf {
    Extra(Color(0xFF0F7A4A), Color(0xFFE6F5EC), Color(0xFF0B5C38))
}

private val LightExtra = Extra(Color(0xFF0F7A4A), Color(0xFFE6F5EC), Color(0xFF0B5C38))
private val DarkExtra = Extra(Color(0xFF6FDDA0), Color(0xFF16341F), Color(0xFFBFF2D2))

private val AppTypography = Typography().run {
    copy(
        headlineMedium = headlineMedium.copy(fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = (-0.4).sp),
        headlineSmall = headlineSmall.copy(fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 30.sp, letterSpacing = (-0.2).sp),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.Bold, fontSize = 22.sp, letterSpacing = (-0.2).sp),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
        titleSmall = titleSmall.copy(fontWeight = FontWeight.SemiBold),
        bodyLarge = bodyLarge.copy(fontSize = 16.sp, lineHeight = 23.sp),
        bodyMedium = bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
        labelLarge = labelLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
        labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp),
    )
}

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun AppTheme(mode: ThemeMode, wallpaperColors: Boolean, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    val ctx = LocalContext.current
    val scheme: ColorScheme = when {
        wallpaperColors && Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> Dark
        else -> Light
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    CompositionLocalProvider(LocalExtra provides if (dark) DarkExtra else LightExtra) {
        MaterialTheme(colorScheme = scheme, typography = AppTypography, shapes = AppShapes, content = content)
    }
}
