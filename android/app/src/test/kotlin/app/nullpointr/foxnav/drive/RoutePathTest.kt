package app.nullpointr.foxnav.drive

import org.maplibre.spatialk.geojson.Position
import kotlin.math.cos
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RoutePathTest {
    private val latitude = 60.17
    private val metresPerDegree = 111_320.0

    /** A point [east] and [north] metres from a fixed spot in Helsinki. */
    private fun at(east: Double, north: Double) =
        Position(24.94 + east / (metresPerDegree * cos(Math.toRadians(latitude))), latitude + north / metresPerDegree)

    private fun metres(a: Position, b: Position): Double {
        val dx = (b.longitude - a.longitude) * metresPerDegree * cos(Math.toRadians(latitude))
        val dy = (b.latitude - a.latitude) * metresPerDegree
        return sqrt(dx * dx + dy * dy)
    }

    /** North 100 m, then a right-angle turn east for 100 m. */
    private val corner = listOf(at(0.0, 0.0), at(0.0, 100.0), at(100.0, 100.0))

    @Test
    fun aStraightRoadStaysAsItIs() {
        val straight = listOf(at(0.0, 0.0), at(0.0, 50.0), at(0.0, 100.0))
        assertEquals(straight, RoutePath(straight).rounded)
    }

    @Test
    fun aCornerIsRoundedOffALittle() {
        val rounded = RoutePath(corner, cornerM = 8.0).rounded
        assertEquals(corner.first(), rounded.first())
        assertEquals(corner.last(), rounded.last())
        assertTrue(corner[1] !in rounded)
        // The curve runs inside the corner, about 2.8 m from it at most for a right angle and 8 m cuts.
        val nearest = rounded.minOf { metres(it, corner[1]) }
        assertEquals(8.0 * 0.5 * sqrt(0.5), nearest, 0.1)
        // And starts and ends 8 m from it, on the road.
        assertTrue(rounded.all { metres(it, corner[1]) <= 8.01 || metres(it, corner[1]) >= 99.0 })
    }

    @Test
    fun theCarDrivesOnTheDrawnLine() {
        val path = RoutePath(corner, cornerM = 8.0)
        val drawn = path.rounded
        for (progress in listOf(50.0, 93.0, 96.0, 100.0, 104.0, 107.0, 150.0)) {
            val car = path.positionAt(progress)
            // Within a few centimetres of the drawn line (which draws the curve in short pieces).
            val off = drawn.zipWithNext().minOf { (a, b) -> distanceToSegment(car, a, b) }
            assertTrue(off < 0.1, "$off m off the line at $progress m")
        }
        // Away from the corner, exactly where Ferrostar has it.
        assertEquals(0.0, metres(path.positionAt(50.0), at(0.0, 50.0)), 0.01)
        assertEquals(0.0, metres(path.positionAt(150.0), at(50.0, 100.0)), 0.01)
    }

    @Test
    fun distancesAlongAreFerrostarsUnroundedOnes() {
        val path = RoutePath(corner)
        assertEquals(150.0, path.project(at(50.0, 103.0)), 0.1)
        assertEquals(30.0, path.project(at(-2.0, 30.0)), 0.1)
    }

    @Test
    fun shortPiecesGetSmallerRoundings() {
        // A zigzag of 6 m pieces: each rounding takes at most half a piece, so none overlap.
        val zigzag = (0..6).map { at(if (it % 2 == 0) 0.0 else 3.0, it * 6.0) }
        val rounded = RoutePath(zigzag).rounded
        val norths = rounded.map { (it.latitude - latitude) * metresPerDegree }
        assertTrue(norths.zipWithNext().all { (a, b) -> b >= a - 1e-6 }, "the line never runs backwards")
    }

    @Test
    fun theCarPointsAlongTheCurve() {
        val path = RoutePath(corner)
        assertEquals(0.0, path.bearingAt(50.0), 0.5)
        assertEquals(90.0, path.bearingAt(150.0), 0.5)
        // At the corner, between the two roads; nearer the new one, as it looks further ahead than behind (like Ferrostar).
        val midway = path.bearingAt(100.0)
        assertTrue(midway in 45.0..85.0, "$midway°")
    }

    private fun distanceToSegment(point: Position, a: Position, b: Position): Double {
        val scale = metresPerDegree * cos(Math.toRadians(latitude))
        val px = (point.longitude - a.longitude) * scale
        val py = (point.latitude - a.latitude) * metresPerDegree
        val sx = (b.longitude - a.longitude) * scale
        val sy = (b.latitude - a.latitude) * metresPerDegree
        val lengthSquared = sx * sx + sy * sy
        val t = if (lengthSquared == 0.0) 0.0 else ((px * sx + py * sy) / lengthSquared).coerceIn(0.0, 1.0)
        val dx = px - sx * t
        val dy = py - sy * t
        return sqrt(dx * dx + dy * dy)
    }
}
