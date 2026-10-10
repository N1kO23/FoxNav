package app.nullpointr.foxnav.shared.road

import app.nullpointr.foxnav.shared.FoxNavJson
import app.nullpointr.foxnav.shared.geo.GeoPoint
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** The road the car is on, as far as the map knows. */
data class RoadInfo(val name: String?, val speedLimitKmh: Int?)

/**
 * Finds the current road without a route (free drive) with Valhalla's `trace_attributes`: the last
 * few GPS fixes are matched to the road network, and the edge under the newest one is the road.
 */
object TraceAttributes {
    /** Request body for `POST {routing}/trace_attributes`; [recent] is oldest first. */
    fun request(recent: List<GeoPoint>): String = buildJsonObject {
        putJsonArray("shape") {
            for (point in recent) addJsonObject { put("lat", point.latitude); put("lon", point.longitude) }
        }
        put("costing", "auto")
        put("shape_match", "map_snap")
        putJsonObject("filters") {
            put("action", "include")
            putJsonArray("attributes") {
                add("edge.speed_limit")
                add("edge.names")
                add("matched.edge_index")
            }
        }
    }.toString()

    /** The road under the newest point, or null when it couldn't be matched to a road. */
    fun parseCurrentRoad(json: String): RoadInfo? {
        val response = FoxNavJson.decodeFromString(Response.serializer(), json)
        val edgeIndex = response.matchedPoints.lastOrNull()?.edgeIndex ?: return null
        val edge = response.edges.getOrNull(edgeIndex) ?: return null
        return RoadInfo(name = edge.names.firstOrNull(), speedLimitKmh = edge.speedLimit?.takeIf { it in 1..200 })
    }

    @Serializable
    private class Response(
        val edges: List<Edge> = emptyList(),
        @SerialName("matched_points") val matchedPoints: List<MatchedPoint> = emptyList(),
    )

    @Serializable
    private class Edge(@SerialName("speed_limit") val speedLimit: Int? = null, val names: List<String> = emptyList())

    @Serializable
    private class MatchedPoint(@SerialName("edge_index") val edgeIndex: Int? = null)
}
