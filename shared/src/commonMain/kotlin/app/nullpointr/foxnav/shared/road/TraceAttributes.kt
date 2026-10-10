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

/**
 * The road the car is on, as far as the map knows: its OpenStreetMap way, and the country it's in
 * (ISO 3166-1 alpha-2).
 */
data class RoadInfo(val name: String?, val speedLimitKmh: Int?, val countryCode: String? = null, val wayId: Long? = null)

/**
 * Finds the current road with Valhalla's `trace_attributes`: the last few GPS fixes are matched to
 * the road network, and the edge under the newest one is the road.
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
                add("edge.way_id")
                add("edge.names")
                add("matched.edge_index")
                add("node.admin_index")
                add("admin.country_code")
            }
        }
    }.toString()

    /** The road under the newest point, or null when it couldn't be matched to a road. */
    fun parseCurrentRoad(json: String): RoadInfo? {
        val response = FoxNavJson.decodeFromString(Response.serializer(), json)
        val edgeIndex = response.matchedPoints.lastOrNull()?.edgeIndex ?: return null
        val edge = response.edges.getOrNull(edgeIndex) ?: return null
        val country = edge.endNode?.adminIndex?.let { response.admins.getOrNull(it) }?.countryCode?.takeIf { it.length == 2 }
        return RoadInfo(
            name = edge.names.firstOrNull(),
            speedLimitKmh = edge.speedLimit?.takeIf { it in 1..200 },
            countryCode = country,
            wayId = edge.wayId?.takeIf { it > 0 },
        )
    }

    @Serializable
    private class Response(
        val edges: List<Edge> = emptyList(),
        val admins: List<Admin> = emptyList(),
        @SerialName("matched_points") val matchedPoints: List<MatchedPoint> = emptyList(),
    )

    @Serializable
    private class Edge(
        @SerialName("speed_limit") val speedLimit: Int? = null,
        @SerialName("way_id") val wayId: Long? = null,
        val names: List<String> = emptyList(),
        @SerialName("end_node") val endNode: Node? = null,
    )

    @Serializable
    private class Node(@SerialName("admin_index") val adminIndex: Int? = null)

    @Serializable
    private class Admin(@SerialName("country_code") val countryCode: String? = null)

    @Serializable
    private class MatchedPoint(@SerialName("edge_index") val edgeIndex: Int? = null)
}
