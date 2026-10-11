package app.nullpointr.foxnav.drive

import org.maplibre.spatialk.geojson.Position
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A route as drawn and driven: its corners rounded off a little, like a car takes them.
 *
 * Map data draws a bend as a few straight pieces, and at navigation zoom each joint shows as a
 * point. Each corner is cut [cornerM] before and after it (at most half of either neighbouring
 * piece, so roundings never overlap) and joined by a curve. Distances along the route stay those
 * of the original line, as Ferrostar measures them, so the car keeps time with Ferrostar's camera
 * and only swings through the curves with the line.
 */
class RoutePath(private val points: List<Position>, cornerM: Double = CORNER_M) {
    private val along = buildList {
        add(0.0)
        for (index in 1 until points.size) add(last() + distance(points[index - 1], points[index]))
    }
    private val length = along.last()

    /** How far before and after each point the rounding starts and ends; 0 where the line runs straight on. */
    private val cuts = DoubleArray(points.size) { index ->
        if (index == 0 || index == points.lastIndex) return@DoubleArray 0.0
        val before = along[index] - along[index - 1]
        val after = along[index + 1] - along[index]
        if (turn(index) < MIN_TURN_RAD || before == 0.0 || after == 0.0) 0.0 else min(cornerM, min(before, after) / 2)
    }

    /** The rounded line, to draw. */
    val rounded: List<Position> by lazy {
        if (points.size < 3) return@lazy points
        buildList {
            add(points.first())
            for (index in 1 until points.lastIndex) {
                val cut = cuts[index]
                if (cut == 0.0) {
                    add(points[index])
                    continue
                }
                val steps = ceil(turn(index) / STEP_RAD).toInt().coerceIn(2, MAX_STEPS)
                for (step in 0..steps) add(curve(index, step.toDouble() / steps))
            }
            add(points.last())
        }
    }

    /** Metres along the route to the nearest point on it, as Ferrostar measures (the unrounded line). */
    fun project(position: Position): Double {
        var best = Double.POSITIVE_INFINITY
        var progress = 0.0
        for (index in 0 until points.lastIndex) {
            val start = points[index]
            val end = points[index + 1]
            val scaleX = METRES_PER_DEGREE * cos(Math.toRadians((start.latitude + end.latitude + position.latitude) / 3.0))
            val segmentX = (end.longitude - start.longitude) * scaleX
            val segmentY = (end.latitude - start.latitude) * METRES_PER_DEGREE
            val pointX = (position.longitude - start.longitude) * scaleX
            val pointY = (position.latitude - start.latitude) * METRES_PER_DEGREE
            val lengthSquared = segmentX * segmentX + segmentY * segmentY
            val t = if (lengthSquared == 0.0) 0.0 else ((pointX * segmentX + pointY * segmentY) / lengthSquared).coerceIn(0.0, 1.0)
            val dx = pointX - segmentX * t
            val dy = pointY - segmentY * t
            val distanceSquared = dx * dx + dy * dy
            if (distanceSquared < best) {
                best = distanceSquared
                progress = along[index] + sqrt(lengthSquared) * t
            }
        }
        return progress
    }

    /** The point [progress] metres along, on the rounded line. */
    fun positionAt(progress: Double): Position {
        val at = progress.coerceIn(0.0, length)
        val index = segmentAt(at)
        // In the rounding at either end of this piece?
        for (corner in intArrayOf(index, index + 1)) {
            val cut = cuts[corner]
            if (cut > 0.0 && at >= along[corner] - cut && at <= along[corner] + cut) {
                return curve(corner, (at - (along[corner] - cut)) / (2 * cut))
            }
        }
        return straightAt(at, index)
    }

    /** Along the line from a little behind to a little ahead: steady through the bends. */
    fun bearingAt(progress: Double): Double {
        val at = progress.coerceIn(0.0, length)
        val behind = (at - BEARING_BEHIND_M).coerceAtLeast(0.0)
        val ahead = (at + BEARING_AHEAD_M).coerceAtMost(length)
        if (ahead <= behind) {
            val index = segmentAt(at)
            return bearing(points[index], points[index + 1])
        }
        return bearing(positionAt(behind), positionAt(ahead))
    }

    private fun straightAt(at: Double, index: Int): Position {
        val start = points[index]
        val end = points[index + 1]
        val segment = along[index + 1] - along[index]
        if (segment == 0.0) return start
        val t = (at - along[index]) / segment
        return Position(start.longitude + (end.longitude - start.longitude) * t, start.latitude + (end.latitude - start.latitude) * t)
    }

    /** The rounding at point [index], from where it leaves the line before ([s] = 0) to where it rejoins it (1). */
    private fun curve(index: Int, s: Double): Position {
        val cut = cuts[index]
        val enter = straightAt(along[index] - cut, segmentAt(along[index] - cut))
        val corner = points[index]
        val leave = straightAt(along[index] + cut, index)
        // A quadratic Bézier with the corner as its control point: tangent to both pieces.
        val a = (1 - s) * (1 - s)
        val b = 2 * (1 - s) * s
        val c = s * s
        return Position(
            a * enter.longitude + b * corner.longitude + c * leave.longitude,
            a * enter.latitude + b * corner.latitude + c * leave.latitude,
        )
    }

    /** How sharply the route turns at point [index], in radians: 0 straight on, π a U-turn. */
    private fun turn(index: Int): Double {
        val scaleX = cos(Math.toRadians(points[index].latitude))
        val inX = (points[index].longitude - points[index - 1].longitude) * scaleX
        val inY = points[index].latitude - points[index - 1].latitude
        val outX = (points[index + 1].longitude - points[index].longitude) * scaleX
        val outY = points[index + 1].latitude - points[index].latitude
        val lengths = sqrt(inX * inX + inY * inY) * sqrt(outX * outX + outY * outY)
        if (lengths == 0.0) return 0.0
        return acos(((inX * outX + inY * outY) / lengths).coerceIn(-1.0, 1.0))
    }

    /** The piece [progress] metres along is on: called every frame, so a binary search. */
    private fun segmentAt(progress: Double): Int {
        val found = along.binarySearch(progress)
        val index = if (found >= 0) found - 1 else -found - 2
        return index.coerceIn(0, max(0, points.lastIndex - 1))
    }

    private fun distance(a: Position, b: Position): Double {
        val dy = (b.latitude - a.latitude) * METRES_PER_DEGREE
        val dx = (b.longitude - a.longitude) * METRES_PER_DEGREE * cos(Math.toRadians((a.latitude + b.latitude) / 2.0))
        return sqrt(dx * dx + dy * dy)
    }

    private fun bearing(start: Position, end: Position): Double {
        val dx = (end.longitude - start.longitude) * cos(Math.toRadians((start.latitude + end.latitude) / 2.0))
        val dy = end.latitude - start.latitude
        return (Math.toDegrees(atan2(dx, dy)) + 360.0) % 360.0
    }

    private companion object {
        /**
         * Small: visible at navigation zoom, where a metre is a couple of pixels, but at a junction the
         * line still meets the road it turns into, and the car leaves the camera's point by 4 m at most.
         */
        const val CORNER_M = 12.0
        const val MIN_TURN_RAD = 0.035 // 2°: straight enough
        const val STEP_RAD = 0.2 // about 11° of turn per drawn piece
        const val MAX_STEPS = 12

        // Ferrostar's own values, so the car and the camera agree.
        const val BEARING_BEHIND_M = 5.0
        const val BEARING_AHEAD_M = 15.0
        const val METRES_PER_DEGREE = 111_320.0
    }
}
