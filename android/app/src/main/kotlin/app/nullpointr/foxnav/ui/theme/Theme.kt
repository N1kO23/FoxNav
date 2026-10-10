package app.nullpointr.foxnav.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// "Tokyo night drive" colours. They mirror design/tokens.json, the source of truth;
// keep the two in sync until the theme is generated from the tokens.

private val NightColors = darkColorScheme(
    primary = Color(0xFFE5462E), // brand
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFF35D6F0), // route
    onSecondary = Color(0xFF0B0D12),
    tertiary = Color(0xFFFFB020), // warning
    onTertiary = Color(0xFF1A1204),
    background = Color(0xFF0B0D12), // ground
    onBackground = Color(0xFFE8EAF0),
    surface = Color(0xFF141821), // raised
    onSurface = Color(0xFFE8EAF0),
    onSurfaceVariant = Color(0xFF8A93A6), // text-secondary
    outline = Color(0xFF232A38), // line
    error = Color(0xFFFF4D4D), // danger
)

private val DayColors = lightColorScheme(
    primary = Color(0xFFD93A22),
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFF0E88A6),
    onSecondary = Color(0xFFFFFFFF),
    tertiary = Color(0xFFFFB020),
    onTertiary = Color(0xFF1A1204),
    background = Color(0xFFF4F1EA),
    onBackground = Color(0xFF16171A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF16171A),
    onSurfaceVariant = Color(0xFF5B6070),
    outline = Color(0xFFDAD4C6),
    error = Color(0xFFC62828),
)

@Composable
fun FoxNavTheme(night: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (night) NightColors else DayColors, content = content)
}
