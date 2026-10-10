package app.nullpointr.foxnav.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import app.nullpointr.foxnav.R

// "Tokyo night drive". The base colours mirror design/tokens.json (the source of truth); the
// container and surface variants are derived from them. Every Material role is set so no
// component falls back to Material's default purple or tints surfaces by elevation.

private val NightColors = darkColorScheme(
    primary = Color(0xFFE5462E), // brand
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF141821), // raised: floating buttons are panels, not brand blobs
    onPrimaryContainer = Color(0xFFE8EAF0),
    inversePrimary = Color(0xFFD93A22),
    secondary = Color(0xFF35D6F0), // route, live data
    onSecondary = Color(0xFF0B0D12),
    secondaryContainer = Color(0xFF0F2E36),
    onSecondaryContainer = Color(0xFFBDF2FB),
    tertiary = Color(0xFFFFB020), // warning
    onTertiary = Color(0xFF1A1204),
    tertiaryContainer = Color(0xFF3A2A08),
    onTertiaryContainer = Color(0xFFFFD58A),
    background = Color(0xFF0B0D12), // ground
    onBackground = Color(0xFFE8EAF0),
    surface = Color(0xFF141821), // raised
    onSurface = Color(0xFFE8EAF0),
    surfaceVariant = Color(0xFF1B202B),
    onSurfaceVariant = Color(0xFF8A93A6), // text-secondary
    surfaceTint = Color(0xFF141821),
    inverseSurface = Color(0xFFE8EAF0),
    inverseOnSurface = Color(0xFF0B0D12),
    error = Color(0xFFFF4D4D), // danger
    onError = Color(0xFF0B0D12),
    errorContainer = Color(0xFF3A1416),
    onErrorContainer = Color(0xFFFFB3AE),
    outline = Color(0xFF232A38), // line
    outlineVariant = Color(0xFF1B2130),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF1E2430),
    surfaceDim = Color(0xFF0B0D12),
    surfaceContainerLowest = Color(0xFF0B0D12),
    surfaceContainerLow = Color(0xFF10131A),
    surfaceContainer = Color(0xFF141821),
    surfaceContainerHigh = Color(0xFF181D27),
    surfaceContainerHighest = Color(0xFF1E2430),
)

private val DayColors = lightColorScheme(
    primary = Color(0xFFD93A22),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFFFFF),
    onPrimaryContainer = Color(0xFF16171A),
    inversePrimary = Color(0xFFE5462E),
    secondary = Color(0xFF0E88A6),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD3ECF2),
    onSecondaryContainer = Color(0xFF06404F),
    tertiary = Color(0xFFFFB020),
    onTertiary = Color(0xFF1A1204),
    tertiaryContainer = Color(0xFFFFE7B8),
    onTertiaryContainer = Color(0xFF3A2A08),
    background = Color(0xFFF4F1EA),
    onBackground = Color(0xFF16171A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF16171A),
    surfaceVariant = Color(0xFFEDE9DF),
    onSurfaceVariant = Color(0xFF5B6070),
    surfaceTint = Color(0xFFFFFFFF),
    inverseSurface = Color(0xFF16171A),
    inverseOnSurface = Color(0xFFF4F1EA),
    error = Color(0xFFC62828),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFCE4E2),
    onErrorContainer = Color(0xFF5C1010),
    outline = Color(0xFFDAD4C6),
    outlineVariant = Color(0xFFE8E3D7),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFE8E3D7),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFBFAF6),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFF7F4EE),
    surfaceContainerHighest = Color(0xFFF1EDE4),
)

/** HUD numerals and headings: speeds, distances, times. */
val ChakraPetch = FontFamily(
    Font(R.font.chakra_petch_medium, FontWeight.Medium),
    Font(R.font.chakra_petch_semibold, FontWeight.SemiBold),
    Font(R.font.chakra_petch_bold, FontWeight.Bold),
)

/** Interface text (a variable font: one file, every weight). */
val PlexSans = FontFamily(
    listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold).map { weight ->
        Font(R.font.ibm_plex_sans, weight, variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)))
    },
)

private val FoxNavTypography = Typography().run {
    fun TextStyle.ui() = copy(fontFamily = PlexSans)
    fun TextStyle.hud() = copy(fontFamily = ChakraPetch, fontWeight = FontWeight.Bold)
    Typography(
        displayLarge = displayLarge.hud(),
        displayMedium = displayMedium.hud(),
        displaySmall = displaySmall.hud(),
        headlineLarge = headlineLarge.hud(),
        headlineMedium = headlineMedium.hud(),
        headlineSmall = headlineSmall.hud(),
        titleLarge = titleLarge.ui().copy(fontWeight = FontWeight.SemiBold),
        titleMedium = titleMedium.ui().copy(fontWeight = FontWeight.SemiBold),
        titleSmall = titleSmall.ui(),
        bodyLarge = bodyLarge.ui(),
        bodyMedium = bodyMedium.ui(),
        bodySmall = bodySmall.ui(),
        labelLarge = labelLarge.ui().copy(fontWeight = FontWeight.SemiBold),
        labelMedium = labelMedium.ui(),
        labelSmall = labelSmall.ui(),
    )
}

@Composable
fun FoxNavTheme(night: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (night) NightColors else DayColors, typography = FoxNavTypography, content = content)
}
