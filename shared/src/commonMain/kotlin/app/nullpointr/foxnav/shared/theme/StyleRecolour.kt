package app.nullpointr.foxnav.shared.theme

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * A map style in a driver's palette and label size. FoxNav's styles keep a template of every
 * paint property that takes a palette colour in their layers' metadata (protocol/SPEC.md §1),
 * `"foxnav:paint": {"fill-color": "@map-water"}`, with each colour as "@token". Other styles keep
 * their colours.
 */
object StyleRecolour {
    private const val PAINT = "foxnav:paint"

    /** [style] with each template filled from [colors] (when given), and label text [labelScale] times as big. */
    fun apply(style: String, colors: PaletteColors?, labelScale: Double = 1.0): String {
        if (colors == null && labelScale == 1.0) return style
        val root = Json.parseToJsonElement(style).jsonObject
        val layers = root["layers"]?.jsonArray ?: return style
        val changed = JsonArray(layers.map { layer -> recolour(layer.jsonObject, colors, labelScale) })
        return JsonObject(root + ("layers" to changed)).toString()
    }

    /** Whether [style] has templates to fill: one of FoxNav's own. */
    fun hasTemplates(style: String): Boolean = "\"$PAINT\"" in style

    private fun recolour(layer: JsonObject, colors: PaletteColors?, labelScale: Double): JsonObject {
        var result = layer
        val templates = (layer["metadata"] as? JsonObject)?.get(PAINT) as? JsonObject
        if (colors != null && templates != null) {
            val paint = (layer["paint"] as? JsonObject).orEmpty()
            result = JsonObject(result + ("paint" to JsonObject(paint + templates.mapValues { (_, template) -> fill(template, colors) })))
        }
        val layout = layer["layout"] as? JsonObject
        val size = layout?.get("text-size")
        if (labelScale != 1.0 && size != null) {
            result = JsonObject(result + ("layout" to JsonObject(layout + ("text-size" to scaled(size, labelScale)))))
        }
        return result
    }

    private fun fill(template: JsonElement, colors: PaletteColors): JsonElement = when (template) {
        is JsonArray -> JsonArray(template.map { fill(it, colors) })
        is JsonPrimitive -> {
            val token = template.content.takeIf { template.isString && it.startsWith("@") }?.removePrefix("@")
            if (token != null && token in Tokens.ALL) JsonPrimitive(ColorMath.hex(colors[token])) else template
        }
        else -> template
    }

    /**
     * A text size [factor] times as big. Zoom curves (`interpolate`, `step`) must stay outermost,
     * so their outputs are scaled; anything else is multiplied.
     */
    private fun scaled(size: JsonElement, factor: Double): JsonElement {
        if (size is JsonPrimitive && !size.isString && size.booleanOrNull == null) {
            return size.doubleOrNull?.let { JsonPrimitive(round(it * factor)) } ?: size
        }
        if (size is JsonArray && size.isNotEmpty()) {
            val operator = (size[0] as? JsonPrimitive)?.takeIf { it.isString }?.content
            // ["interpolate", type, input, stop, output, …] and ["step", input, output, stop, output, …].
            val firstOutput = when (operator) {
                "interpolate", "interpolate-hcl", "interpolate-lab" -> 4
                "step" -> 2
                else -> null
            }
            if (firstOutput != null) {
                return JsonArray(size.mapIndexed { index, part -> if (index >= firstOutput && (index - firstOutput) % 2 == 0) scaled(part, factor) else part })
            }
        }
        return JsonArray(listOf(JsonPrimitive("*"), size, JsonPrimitive(factor)))
    }

    private fun round(value: Double): Double = kotlin.math.round(value * 100) / 100
}

private fun JsonObject?.orEmpty(): JsonObject = this ?: JsonObject(emptyMap())
