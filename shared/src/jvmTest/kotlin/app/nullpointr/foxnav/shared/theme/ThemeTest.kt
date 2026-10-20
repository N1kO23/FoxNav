package app.nullpointr.foxnav.shared.theme

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** The palettes in design/tokens.json, and the map styles in infra/styles recoloured with them. */
class ThemeTest {
    private val palettes = Palettes.parse(File("../design/tokens.json").readText())
    private val night = File("../infra/styles/night.json").readText()

    @Test
    fun everyPaletteHasEveryColourNightAndDay() {
        assertEquals(listOf("tokyo-night", "true-black", "high-contrast", "shibuya-neon", "kyoto-moss"), palettes.map { it.id })
        assertEquals(Palettes.DEFAULT, palettes.first().id)
        val tokyo = palettes.first()
        assertEquals("Tokyo night", tokyo.name)
        assertEquals("東京の夜", tokyo.japanese)
        assertEquals(0x0B0D12, tokyo.night[Tokens.GROUND])
        assertEquals(0xF4F1EA, tokyo.day[Tokens.GROUND])
    }

    @Test
    fun everyPaletteReads() {
        for (palette in palettes) {
            for (night in listOf(true, false)) {
                val colors = palette.colors(night)
                val what = "${palette.id} ${if (night) "night" else "day"}"
                assertTrue(ColorMath.contrast(colors[Tokens.TEXT], colors[Tokens.GROUND]) >= 7, "$what: text")
                assertTrue(ColorMath.contrast(colors[Tokens.TEXT_SECONDARY], colors[Tokens.RAISED]) >= 4.5, "$what: secondary text")
                assertTrue(ColorMath.contrast(colors[Tokens.ON_BRAND], colors[Tokens.BRAND]) >= 3, "$what: text on the accent")
                assertTrue(ColorMath.contrast(colors[Tokens.ROUTE], colors[Tokens.MAP_LAND]) >= 3, "$what: the route on the map")
                assertTrue(ColorMath.contrast(colors[Tokens.MAP_LABEL], colors[Tokens.MAP_LAND]) >= 3.5, "$what: map labels")
                // Warnings keep their colours, and the route never takes them.
                assertTrue(ColorMath.looksLikeWarning(colors[Tokens.WARNING]) && ColorMath.looksLikeWarning(colors[Tokens.DANGER]), what)
                assertFalse(ColorMath.looksLikeWarning(colors[Tokens.ROUTE]), "$what: the route")
            }
        }
    }

    @Test
    fun theDefaultPaletteGivesBackTheStyleAsItIs() {
        // The style is generated in Tokyo night: filling its templates with it changes nothing.
        val recoloured = StyleRecolour.apply(night, palettes.first().night)
        assertEquals(Json.parseToJsonElement(night), Json.parseToJsonElement(recoloured))
        assertTrue(StyleRecolour.hasTemplates(night))
    }

    @Test
    fun anotherPaletteRecoloursTheMap() {
        val neon = palettes.single { it.id == "shibuya-neon" }.night
        val layers = Json.parseToJsonElement(StyleRecolour.apply(night, neon)).jsonObject.getValue("layers").jsonArray.associateBy { it.jsonObject.getValue("id").jsonPrimitive.content }
        fun paint(layer: String, property: String) = layers.getValue(layer).jsonObject.getValue("paint").jsonObject.getValue(property)
        assertEquals(ColorMath.hex(neon[Tokens.MAP_LAND]), paint("background", "background-color").jsonPrimitive.content)
        assertEquals(ColorMath.hex(neon[Tokens.MAP_WATER]), paint("water", "fill-color").jsonPrimitive.content)
        // Inside expressions too: no colour of the old palette is left where a template was.
        val tokyo = palettes.first().night
        val left = layers.values.flatMap { layer ->
            val templates = (layer.jsonObject["metadata"] as? JsonObject)?.get("foxnav:paint") as? JsonObject ?: return@flatMap emptyList()
            templates.keys.map { property -> layer.jsonObject.getValue("paint").jsonObject.getValue(property).toString() }
        }.filter { painted -> Tokens.ALL.filter { it.startsWith("map-") }.any { ColorMath.hex(tokyo[it]) in painted && tokyo[it] != neon[it] } }
        assertEquals(emptyList(), left)
    }

    @Test
    fun labelsGrowWithTheTextSize() {
        val bigger = Json.parseToJsonElement(StyleRecolour.apply(night, null, labelScale = 1.5)).jsonObject.getValue("layers").jsonArray
        val before = Json.parseToJsonElement(night).jsonObject.getValue("layers").jsonArray
        val sizes = before.zip(bigger).mapNotNull { (old, new) ->
            val oldSize = (old.jsonObject["layout"] as? JsonObject)?.get("text-size") ?: return@mapNotNull null
            oldSize to (new.jsonObject["layout"] as JsonObject).getValue("text-size")
        }
        assertTrue(sizes.isNotEmpty())
        for ((old, new) in sizes) {
            assertNotEquals(old, new)
            // Zoom curves stay outermost, as MapLibre requires.
            if (old is JsonArray && old[0].jsonPrimitive.content in setOf("interpolate", "step")) assertEquals(old[0], (new as JsonArray)[0])
        }
        assertEquals(night, StyleRecolour.apply(night, null, labelScale = 1.0))
    }

    @Test
    fun aStyleWithoutTemplatesKeepsItsColours() {
        val plain = """{"version":8,"layers":[{"id":"background","type":"background","paint":{"background-color":"#123456"}}]}"""
        assertFalse(StyleRecolour.hasTemplates(plain))
        assertTrue("#123456" in StyleRecolour.apply(plain, palettes.last().night))
    }

    @Test
    fun colourMath() {
        assertEquals(21.0, ColorMath.contrast(0xFFFFFF, 0x000000), 0.01)
        assertEquals(ColorMath.INK, ColorMath.onColor(0xFFB020))
        assertEquals(ColorMath.WHITE, ColorMath.onColor(0x2563D9))
        assertEquals(0x4C8DFF, ColorMath.fromHsv(ColorMath.toHsv(0x4C8DFF).first, ColorMath.toHsv(0x4C8DFF).second, ColorMath.toHsv(0x4C8DFF).third))
        // A pale route darkens until it shows on a day map, keeping its hue.
        val dayLand = palettes.first().day[Tokens.MAP_LAND]
        val adjusted = ColorMath.withContrast(0x9BE7FF, dayLand, 3.0)
        assertTrue(ColorMath.contrast(adjusted, dayLand) >= 3.0)
        assertEquals(ColorMath.toHsv(0x9BE7FF).first, ColorMath.toHsv(adjusted).first, 2.0)
        // Amber, orange and red pass for warnings; cyan, pink, violet and grey don't.
        assertTrue(listOf(0xFFB020, 0xFF7A00, 0xE3242B, 0xFFE14D).all(ColorMath::looksLikeWarning))
        assertFalse(listOf(0x35D6F0, 0xFF2E88, 0xB79BFF, 0x8A93A6, 0x3A1416).any(ColorMath::looksLikeWarning))
        assertEquals(0x0E88A6, ColorMath.parseHex("#0e88a6"))
        assertEquals(null, ColorMath.parseHex("#0e88a"))
        assertEquals("#0E88A6", ColorMath.hex(0x0E88A6))
    }
}
