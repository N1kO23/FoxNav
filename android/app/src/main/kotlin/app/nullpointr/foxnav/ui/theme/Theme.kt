package app.nullpointr.foxnav.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import app.nullpointr.foxnav.R
import app.nullpointr.foxnav.settings.Accent
import app.nullpointr.foxnav.settings.RouteColor
import app.nullpointr.foxnav.settings.Settings
import app.nullpointr.foxnav.shared.theme.ColorMath
import app.nullpointr.foxnav.shared.theme.Palette
import app.nullpointr.foxnav.shared.theme.PaletteColors
import app.nullpointr.foxnav.shared.theme.Tokens

// "Tokyo night drive", and the other palettes in design/tokens.json (the source of truth), which
// drivers choose in settings. Every Material role is derived from the palette's few colours, so no
// component falls back to Material's default purple or tints surfaces by elevation.

/** A colour as Compose has it, from 0xRRGGBB. */
fun rgb(value: Int): Color = Color(0xFF000000.toInt() or value)

/** Every Material role, from a palette's colours for [night] or day. */
private fun colorScheme(c: PaletteColors, night: Boolean): ColorScheme {
    fun mix(from: String, to: String, amount: Double) = rgb(ColorMath.mix(c[from], c[to], amount))
    fun mix(from: String, to: Int, amount: Double) = rgb(ColorMath.mix(c[from], to, amount))
    val ground = rgb(c[Tokens.GROUND])
    val raised = rgb(c[Tokens.RAISED])
    val text = rgb(c[Tokens.TEXT])
    val black = 0x000000
    val white = 0xFFFFFF
    val base = if (night) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = rgb(c[Tokens.BRAND]),
        onPrimary = rgb(c[Tokens.ON_BRAND]),
        // Floating buttons are panels, not brand blobs.
        primaryContainer = raised,
        onPrimaryContainer = text,
        inversePrimary = rgb(c[Tokens.BRAND]),
        secondary = rgb(c[Tokens.ROUTE]), // route, live data
        onSecondary = rgb(c[Tokens.ON_ROUTE]),
        secondaryContainer = if (night) mix(Tokens.GROUND, Tokens.ROUTE, 0.15) else mix(Tokens.RAISED, Tokens.ROUTE, 0.18),
        onSecondaryContainer = if (night) mix(Tokens.ROUTE, white, 0.7) else mix(Tokens.ROUTE, black, 0.55),
        tertiary = rgb(c[Tokens.WARNING]),
        onTertiary = rgb(c[Tokens.ON_WARNING]),
        tertiaryContainer = if (night) mix(Tokens.GROUND, Tokens.WARNING, 0.2) else mix(Tokens.RAISED, Tokens.WARNING, 0.3),
        onTertiaryContainer = if (night) mix(Tokens.WARNING, white, 0.45) else mix(Tokens.WARNING, black, 0.75),
        background = ground,
        onBackground = text,
        surface = raised,
        onSurface = text,
        surfaceVariant = mix(Tokens.RAISED, Tokens.LINE, 0.5),
        onSurfaceVariant = rgb(c[Tokens.TEXT_SECONDARY]),
        surfaceTint = raised,
        inverseSurface = text,
        inverseOnSurface = ground,
        error = rgb(c[Tokens.DANGER]),
        onError = rgb(ColorMath.onColor(c[Tokens.DANGER], dark = c[Tokens.GROUND].takeIf { night } ?: ColorMath.INK)),
        errorContainer = if (night) mix(Tokens.GROUND, Tokens.DANGER, 0.18) else mix(Tokens.RAISED, Tokens.DANGER, 0.12),
        onErrorContainer = if (night) mix(Tokens.DANGER, white, 0.55) else mix(Tokens.DANGER, black, 0.55),
        outline = rgb(c[Tokens.LINE]),
        outlineVariant = mix(Tokens.RAISED, Tokens.LINE, 0.5),
        scrim = Color.Black,
        surfaceBright = if (night) mix(Tokens.RAISED, Tokens.LINE, 0.7) else raised,
        surfaceDim = if (night) ground else mix(Tokens.GROUND, Tokens.LINE, 0.4),
        surfaceContainerLowest = if (night) ground else raised,
        surfaceContainerLow = if (night) mix(Tokens.GROUND, Tokens.RAISED, 0.5) else mix(Tokens.RAISED, Tokens.GROUND, 0.3),
        surfaceContainer = raised,
        surfaceContainerHigh = if (night) mix(Tokens.RAISED, Tokens.LINE, 0.35) else mix(Tokens.RAISED, Tokens.GROUND, 0.6),
        surfaceContainerHighest = if (night) mix(Tokens.RAISED, Tokens.LINE, 0.7) else ground,
    )
}

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

/** Whether the app is in its night colours: the map follows it. */
val LocalNight = compositionLocalOf { true }

/** The colours the app is in: its palette's for day or night, with the driver's accent and route colour. */
val LocalThemeColors = compositionLocalOf<PaletteColors?> { null }

/** The driving displays' size (speedometer, maneuver card…), a multiple of their design size. */
val LocalHudScale = compositionLocalOf { 1f }

@Composable
fun FoxNavTheme(night: Boolean, colors: PaletteColors, content: @Composable () -> Unit) {
    val scheme = remember(colors, night) { colorScheme(colors, night) }
    CompositionLocalProvider(LocalNight provides night, LocalThemeColors provides colors) {
        MaterialTheme(colorScheme = scheme, typography = FoxNavTypography, content = content)
    }
}

/**
 * [palette]'s colours for [night] or day as the driver set them up: with their accent and route
 * colour, each adjusted to read well on that palette's backgrounds.
 */
fun themeColors(palette: Palette, night: Boolean, settings: Settings): PaletteColors {
    val base = palette.colors(night)
    val changes = HashMap<String, Int>()
    val accent = when (settings.accent) {
        Accent.THEME -> null
        Accent.CUSTOM -> ColorMath.withContrast(settings.customAccent, base[Tokens.RAISED], 3.0)
        else -> accentColors(settings.accent, night).first.toArgb() and 0xFFFFFF
    }
    if (accent != null) {
        changes[Tokens.BRAND] = accent
        changes[Tokens.BRAND_TEXT] = ColorMath.withContrast(accent, base[Tokens.GROUND], 4.5)
        changes[Tokens.ON_BRAND] = ColorMath.onColor(accent, dark = ColorMath.INK)
    }
    if (settings.routeColor == RouteColor.CUSTOM) {
        val route = ColorMath.withContrast(settings.customRoute, base[Tokens.MAP_LAND], 3.0)
        changes[Tokens.ROUTE] = route
        changes[Tokens.ON_ROUTE] = ColorMath.onColor(route)
    }
    return if (changes.isEmpty()) base else base.with(changes)
}

/** The accent and the text on it, night and day: night ones glow, day ones hold up in sunlight. */
fun accentColors(accent: Accent, night: Boolean): Pair<Color, Color> {
    val white = Color(0xFFFFFFFF)
    val ink = Color(0xFF0B0D12)
    return when (accent) {
        Accent.THEME, Accent.CUSTOM, Accent.VERMILION -> (if (night) Color(0xFFE5462E) else Color(0xFFD93A22)) to white
        Accent.SAKURA -> (if (night) Color(0xFFFF6FA5) else Color(0xFFD23C78)) to (if (night) ink else white)
        Accent.OCEAN -> (if (night) Color(0xFF4C8DFF) else Color(0xFF2563D9)) to white
        Accent.LIME -> (if (night) Color(0xFF9BD84A) else Color(0xFF4E8A12)) to (if (night) ink else white)
        Accent.VIOLET -> (if (night) Color(0xFFB79BFF) else Color(0xFF6C4BD6)) to (if (night) ink else white)
        Accent.MONO -> (if (night) Color(0xFFE8EAF0) else Color(0xFF16171A)) to (if (night) ink else white)
    }
}
