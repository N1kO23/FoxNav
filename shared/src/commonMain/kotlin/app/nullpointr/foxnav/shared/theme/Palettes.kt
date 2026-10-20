package app.nullpointr.foxnav.shared.theme

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The colours every palette defines, night and day (design/tokens.json, `color.palette`). */
object Tokens {
    const val GROUND = "ground"
    const val RAISED = "raised"
    const val LINE = "line"
    const val TEXT = "text"
    const val TEXT_SECONDARY = "text-secondary"
    const val BRAND = "brand"
    const val BRAND_TEXT = "brand-text"
    const val ON_BRAND = "on-brand"
    const val ROUTE = "route"
    const val ON_ROUTE = "on-route"
    const val WARNING = "warning"
    const val ON_WARNING = "on-warning"
    const val DANGER = "danger"
    const val MAP_LAND = "map-land"
    const val MAP_WATER = "map-water"
    const val MAP_PARK = "map-park"
    const val MAP_ROAD = "map-road"
    const val MAP_ROAD_MAJOR = "map-road-major"
    const val MAP_ROAD_CASING = "map-road-casing"
    const val MAP_ROAD_HIGHWAY = "map-road-highway"
    const val MAP_LABEL = "map-label"
    const val MAP_LABEL_MAJOR = "map-label-major"
    const val MAP_LABEL_HALO = "map-label-halo"
    const val MAP_BUILDING = "map-building"
    const val MAP_RAILWAY = "map-railway"
    const val MAP_BOUNDARY = "map-boundary"

    val ALL = listOf(
        GROUND, RAISED, LINE, TEXT, TEXT_SECONDARY, BRAND, BRAND_TEXT, ON_BRAND, ROUTE, ON_ROUTE, WARNING, ON_WARNING, DANGER,
        MAP_LAND, MAP_WATER, MAP_PARK, MAP_ROAD, MAP_ROAD_MAJOR, MAP_ROAD_CASING, MAP_ROAD_HIGHWAY, MAP_LABEL, MAP_LABEL_MAJOR,
        MAP_LABEL_HALO, MAP_BUILDING, MAP_RAILWAY, MAP_BOUNDARY,
    )
}

/** One set of a palette's colours, night or day, as 0xRRGGBB by token. */
class PaletteColors(private val colors: Map<String, Int>) {
    init {
        val missing = Tokens.ALL.filter { it !in colors }
        require(missing.isEmpty()) { "A palette without ${missing.joinToString()}" }
    }

    operator fun get(token: String): Int = colors.getValue(token)

    /** The same colours, with [changes] instead where they say so. */
    fun with(changes: Map<String, Int>): PaletteColors = PaletteColors(colors + changes)

    override fun equals(other: Any?): Boolean = other is PaletteColors && other.colors == colors

    override fun hashCode(): Int = colors.hashCode()
}

/** A colour theme drivers can choose: [night] and [day] sets of the same colours. */
data class Palette(val id: String, val name: String, val japanese: String, val night: PaletteColors, val day: PaletteColors) {
    fun colors(night: Boolean): PaletteColors = if (night) this.night else day
}

object Palettes {
    /** The palette the map styles are made with, and the one drivers start with. */
    const val DEFAULT = "tokyo-night"

    /** The palettes in design/tokens.json, in its order. */
    fun parse(tokensJson: String): List<Palette> {
        val palettes = Json.parseToJsonElement(tokensJson).jsonObject.getValue("color").jsonObject.getValue("palette").jsonObject
        return palettes.filterKeys { !it.startsWith("$") }.map { (id, element) ->
            val palette = element.jsonObject
            val names = palette["\$extensions"]?.jsonObject?.get("app.nullpointr.foxnav")?.jsonObject
            Palette(
                id = id,
                name = names?.get("name")?.jsonPrimitive?.content ?: id,
                japanese = names?.get("japanese")?.jsonPrimitive?.content.orEmpty(),
                night = colorsOf(palette.getValue("night").jsonObject),
                day = colorsOf(palette.getValue("day").jsonObject),
            )
        }
    }

    private fun colorsOf(set: JsonObject): PaletteColors =
        PaletteColors(set.mapValues { (token, value) -> ColorMath.parseHex(value.jsonObject.getValue("\$value").jsonPrimitive.content) ?: error("Not a colour: $token") })
}
