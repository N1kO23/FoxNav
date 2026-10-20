package app.nullpointr.foxnav.ui

import androidx.compose.ui.graphics.toArgb
import app.nullpointr.foxnav.settings.Accent
import app.nullpointr.foxnav.settings.RouteColor
import app.nullpointr.foxnav.settings.Settings
import app.nullpointr.foxnav.shared.theme.ColorMath
import app.nullpointr.foxnav.shared.theme.Palettes
import app.nullpointr.foxnav.shared.theme.Tokens
import app.nullpointr.foxnav.ui.theme.accentColors
import app.nullpointr.foxnav.ui.theme.themeColors
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The colours the app uses: the palette's, with the driver's accent and route colour, readable night and day. */
class ThemeColorsTest {
    private val palettes = Palettes.parse(File("../../design/tokens.json").readText())
    private val neon = palettes.single { it.id == "shibuya-neon" }

    @Test
    fun theThemesOwnColoursByDefault() {
        for (night in listOf(true, false)) assertEquals(neon.colors(night), themeColors(neon, night, Settings()))
    }

    @Test
    fun aPresetAccentReplacesTheThemes() {
        val ocean = themeColors(neon, night = false, Settings(accent = Accent.OCEAN))
        assertEquals(accentColors(Accent.OCEAN, night = false).first.toArgb() and 0xFFFFFF, ocean[Tokens.BRAND])
        assertEquals(neon.day[Tokens.ROUTE], ocean[Tokens.ROUTE])
    }

    @Test
    fun ownColoursAreAdjustedToRead() {
        // A pale yellow-green accent and a pale cyan route: fine at night, too faint by day as they are.
        val settings = Settings(accent = Accent.CUSTOM, customAccent = 0xD8F5A2, routeColor = RouteColor.CUSTOM, customRoute = 0xA8F0FF)
        for (palette in palettes) {
            for (night in listOf(true, false)) {
                val colors = themeColors(palette, night, settings)
                val what = "${palette.id} ${if (night) "night" else "day"}"
                assertTrue(ColorMath.contrast(colors[Tokens.BRAND], colors[Tokens.RAISED]) >= 3, "$what: accent")
                assertTrue(ColorMath.contrast(colors[Tokens.BRAND_TEXT], colors[Tokens.GROUND]) >= 4.5, "$what: accent text")
                assertTrue(ColorMath.contrast(colors[Tokens.ON_BRAND], colors[Tokens.BRAND]) >= 3, "$what: text on the accent")
                assertTrue(ColorMath.contrast(colors[Tokens.ROUTE], colors[Tokens.MAP_LAND]) >= 3, "$what: route")
                assertTrue(ColorMath.contrast(colors[Tokens.ON_ROUTE], colors[Tokens.ROUTE]) >= 3, "$what: text on the route colour")
            }
        }
        // Where it already reads, it stays as picked.
        assertEquals(0xA8F0FF, themeColors(palettes.first(), night = true, settings)[Tokens.ROUTE])
    }
}
