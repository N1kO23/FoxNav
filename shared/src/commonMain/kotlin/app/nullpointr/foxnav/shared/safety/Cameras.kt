package app.nullpointr.foxnav.shared.safety

import app.nullpointr.foxnav.shared.FoxNavJson
import app.nullpointr.foxnav.shared.convoy.Fix
import app.nullpointr.foxnav.shared.convoy.project
import app.nullpointr.foxnav.shared.geo.GeoPoint
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.floor

/** A region's speed cameras, as servers publish them (protocol/SPEC.md §3.1). */
@Serializable
data class CameraPack(
    val format: Int,
    val region: String,
    val generated: String? = null,
    val attribution: String? = null,
    val cameras: List<Camera> = emptyList(),
) {
    companion object {
        /** The format this code reads. */
        const val FORMAT = 1

        /** Reads a pack; cameras of kinds this version doesn't know are left out. */
        fun parse(json: String): CameraPack {
            val pack = FoxNavJson.decodeFromString(serializer(), json)
            require(pack.format == FORMAT) { "Camera pack format ${pack.format}, this app reads $FORMAT" }
            return pack.copy(cameras = pack.cameras.filter { it.kind in Camera.KINDS })
        }
    }
}

@Serializable
data class Camera(
    val id: String,
    val lat: Double,
    val lon: Double,
    val kind: String = SPEED,
    /** Travel directions it checks, degrees clockwise from north; empty: unknown, so any. */
    val bearings: List<Double> = emptyList(),
    /** km/h, when known. */
    val maxspeed: Int? = null,
) {
    val point: GeoPoint get() = GeoPoint(lat, lon)

    /** Whether it checks traffic travelling towards [headingDeg]. */
    fun watches(headingDeg: Double): Boolean = bearings.isEmpty() || bearings.any { angleBetween(it, headingDeg) <= DIRECTION_TOLERANCE_DEG }

    companion object {
        const val SPEED = "speed"
        const val RED_LIGHT = "red_light"
        val KINDS = setOf(SPEED, RED_LIGHT)
    }
}

/** A camera coming up, and how far it is along the road. */
data class CameraAhead(val camera: Camera, val distanceM: Double)

/**
 * Finds the next camera on the road ahead that checks our direction. Along [route] while
 * navigating; in free drive, or off the route, within a narrow cone around our heading. The
 * faster we go, the further ahead it looks, so warnings come about [LOOKAHEAD_S] before a camera.
 */
class CameraWatch(cameras: List<Camera>) {
    private val cells: Map<Long, List<Camera>> = cameras.groupBy { cellOf(it.lat, it.lon) }

    fun ahead(me: Fix, route: List<GeoPoint>? = null): CameraAhead? {
        if (me.speedMps < MIN_SPEED_MPS) return null // parked or crawling: nothing to warn about
        val lookahead = (me.speedMps * LOOKAHEAD_S).coerceIn(MIN_LOOKAHEAD_M, MAX_LOOKAHEAD_M)
        val nearby = near(me.point).filter { me.point.distanceTo(it.point) <= lookahead }
        if (nearby.isEmpty()) return null

        val mine = route?.let { project(it, me.point) }?.takeIf { it.offM <= ON_ROUTE_M }
        val found = if (route != null && mine != null) {
            nearby.mapNotNull { camera ->
                val at = project(route, camera.point) ?: return@mapNotNull null
                val along = at.alongM - mine.alongM
                if (at.offM <= ON_ROAD_M && along > 0 && along <= lookahead && camera.watches(at.bearingDeg)) CameraAhead(camera, along) else null
            }
        } else {
            nearby.mapNotNull { camera ->
                val inView = angleBetween(me.point.bearingTo(camera.point), me.headingDeg) <= CONE_DEG
                if (inView && camera.watches(me.headingDeg)) CameraAhead(camera, me.point.distanceTo(camera.point)) else null
            }
        }
        return found.minByOrNull { it.distanceM }
    }

    /** Cameras in the grid cells around [point]: wide enough for the longest lookahead. */
    private fun near(point: GeoPoint): List<Camera> {
        val row = floor(point.latitude / CELL_DEG).toLong()
        val column = floor(point.longitude / CELL_DEG).toLong()
        return (row - 1..row + 1).flatMap { r -> (column - 2..column + 2).flatMap { c -> cells[key(r, c)].orEmpty() } }
    }

    private companion object {
        // 0.02° is about 2.2 km north–south and, at Nordic latitudes, 1 km or so east–west.
        const val CELL_DEG = 0.02

        fun cellOf(lat: Double, lon: Double) = key(floor(lat / CELL_DEG).toLong(), floor(lon / CELL_DEG).toLong())

        fun key(row: Long, column: Long) = row * 1_000_000 + column
    }
}

/** Smallest angle between two bearings, 0–180°. */
fun angleBetween(a: Double, b: Double): Double = abs(((a - b) % 360 + 540) % 360 - 180)

const val LOOKAHEAD_S = 25.0
private const val MIN_SPEED_MPS = 1.5
private const val MIN_LOOKAHEAD_M = 300.0
private const val MAX_LOOKAHEAD_M = 1_500.0
private const val CONE_DEG = 30.0
private const val DIRECTION_TOLERANCE_DEG = 45.0

/** Cameras further than this from the route are on another road. */
private const val ON_ROAD_M = 30.0

/** Further than this from the route, we're off it: look along the heading instead. */
private const val ON_ROUTE_M = 60.0
