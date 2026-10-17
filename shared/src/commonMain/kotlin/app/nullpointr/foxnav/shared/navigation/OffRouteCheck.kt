package app.nullpointr.foxnav.shared.navigation

import app.nullpointr.foxnav.shared.geo.GeoPoint
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot

/** A position fix, as [OffRouteCheck] reads it. Course and speed may be missing; they're worked out from the last fix then. */
data class Fix(
    val point: GeoPoint,
    /** Horizontal accuracy in metres. */
    val accuracyM: Double,
    /** Degrees clockwise from north. */
    val courseDeg: Double? = null,
    val speedMps: Double? = null,
    val timeMs: Long,
)

/** Where the car is, as far as its route goes. */
sealed interface RouteCheck {
    data object OnRoute : RouteCheck

    /** Further from the route than it allows. */
    data class OffRoute(val distanceM: Double) : RouteCheck

    /** On the route's road, but driving against it: a wrong turn onto it, or turning back. */
    data class WrongWay(val distanceM: Double) : RouteCheck
}

/**
 * Whether the car has left its route, for rerouting. Two ways to leave it:
 * - **Off the line:** more than [maxDistanceM] from it, by a fix accurate to [maxAccuracyM] or better.
 * - **The wrong way along it:** at [minWrongWaySpeedMps] or more, heading at least [wrongWayAngleDeg]
 *   off the line's direction, for [wrongWayMs] and [minWrongWayM]. A distance check alone never
 *   sees a driver who has turned round, since they stay on the line.
 *
 * Keeps what it has seen of the route it checks: [reset] it for a new route.
 */
class OffRouteCheck(
    private val maxDistanceM: Double = 50.0,
    private val maxAccuracyM: Double = 25.0,
    private val minWrongWaySpeedMps: Double = 4.0,
    private val wrongWayAngleDeg: Double = 120.0,
    private val wrongWayMs: Long = 4_000,
    private val minWrongWayM: Double = 40.0,
) {
    private var last: Fix? = null
    private var wrongSince: Fix? = null

    fun reset() {
        last = null
        wrongSince = null
    }

    /** Checks [fix] against [line], the stretch of route around and ahead of the car. */
    fun check(line: List<GeoPoint>, fix: Fix): RouteCheck {
        val previous = last
        last = fix
        if (line.size < 2 || fix.accuracyM > maxAccuracyM) {
            wrongSince = null
            return RouteCheck.OnRoute
        }
        val segments = distances(line, fix.point)
        val nearest = segments.minOf { it.first }
        if (nearest > maxDistanceM) {
            wrongSince = null
            return RouteCheck.OffRoute(nearest)
        }

        val moved = previous?.let { it.point.distanceTo(fix.point) } ?: 0.0
        val seconds = previous?.let { (fix.timeMs - it.timeMs) / 1000.0 } ?: 0.0
        val speed = fix.speedMps ?: (if (seconds > 0) moved / seconds else null)
        val course = fix.courseDeg ?: previous?.takeIf { moved >= MIN_COURSE_MOVE_M }?.point?.bearingTo(fix.point)
        if (speed == null || course == null || speed < minWrongWaySpeedMps) {
            wrongSince = null
            return RouteCheck.OnRoute
        }
        // Where the line doubles back on itself (out along a road and back), either way along it is right.
        val against = segments.filter { it.first <= nearest + PARALLEL_M }
            .all { (_, index) -> angle(course, line[index].bearingTo(line[index + 1])) >= wrongWayAngleDeg }
        if (!against) {
            wrongSince = null
            return RouteCheck.OnRoute
        }
        val since = wrongSince ?: fix.also { wrongSince = it }
        val long = fix.timeMs - since.timeMs >= wrongWayMs && since.point.distanceTo(fix.point) >= minWrongWayM
        return if (long) RouteCheck.WrongWay(nearest) else RouteCheck.OnRoute
    }

    /** The distance from [point] to each segment of [line], with the segment's index. */
    private fun distances(line: List<GeoPoint>, point: GeoPoint): List<Pair<Double, Int>> {
        // Metres on a flat map around the point: plenty accurate over a few hundred metres.
        val metresPerLon = METRES_PER_DEGREE * cos(point.latitude * PI / 180)
        fun x(p: GeoPoint) = (p.longitude - point.longitude) * metresPerLon
        fun y(p: GeoPoint) = (p.latitude - point.latitude) * METRES_PER_DEGREE
        return line.zipWithNext().mapIndexedNotNull { index, (a, b) ->
            val ax = x(a)
            val ay = y(a)
            val dx = x(b) - ax
            val dy = y(b) - ay
            val length = dx * dx + dy * dy
            if (length == 0.0) return@mapIndexedNotNull null
            val t = (-(ax * dx + ay * dy) / length).coerceIn(0.0, 1.0)
            hypot(ax + t * dx, ay + t * dy) to index
        }.ifEmpty { listOf(line.first().distanceTo(point) to 0) }
    }

    private companion object {
        const val METRES_PER_DEGREE = 111_195.0

        /** Course from movement only over at least this far: GPS jitter makes nonsense of shorter steps. */
        const val MIN_COURSE_MOVE_M = 5.0

        /** Segments this close to the nearest count as the same road. */
        const val PARALLEL_M = 10.0

        fun angle(a: Double, b: Double): Double = abs(((a - b) % 360 + 540) % 360 - 180)
    }
}
