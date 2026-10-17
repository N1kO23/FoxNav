package app.nullpointr.foxnav.shared.road

import app.nullpointr.foxnav.shared.FoxNavJson
import app.nullpointr.foxnav.shared.geo.GeoPoint
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Which places a car can drive to, with Valhalla's `sources_to_targets` (a time-distance matrix):
 * one request for many places, which the phone's Valhalla answers from the road data it has.
 */
object Reachability {
    /** The request body for driving from [from] to each of [targets]. */
    fun request(from: GeoPoint, targets: List<GeoPoint>): String = buildJsonObject {
        putJsonArray("sources") { addJsonObject { put("lat", from.latitude); put("lon", from.longitude) } }
        putJsonArray("targets") {
            for (target in targets) addJsonObject { put("lat", target.latitude); put("lon", target.longitude) }
        }
        put("costing", "auto")
    }.toString()

    /**
     * For each of [count] targets, whether the answer has a way there: Valhalla gives no distance
     * where there's none. Reads both of Valhalla's answer formats; null if it's neither.
     */
    fun parse(json: String, count: Int): List<Boolean>? {
        val matrix = FoxNavJson.parseToJsonElement(json).jsonObject["sources_to_targets"] ?: return null
        val reached = BooleanArray(count)
        when (matrix) {
            // Verbose: one row per source, each target's {distance, to_index}.
            is JsonArray -> for (cell in matrix.firstOrNull()?.jsonArray.orEmpty()) {
                val fields = cell as? JsonObject ?: continue
                val index = fields["to_index"]?.toString()?.toIntOrNull() ?: continue
                if (index in 0 until count) reached[index] = fields["distance"].let { it != null && it !is JsonNull }
            }
            // Concise: {"distances": [[…]]}, null where there's no way.
            is JsonObject -> matrix["distances"]?.jsonArray?.firstOrNull()?.jsonArray?.forEachIndexed { index, distance ->
                if (index < count) reached[index] = distance !is JsonNull
            }
            else -> return null
        }
        return reached.toList()
    }
}
