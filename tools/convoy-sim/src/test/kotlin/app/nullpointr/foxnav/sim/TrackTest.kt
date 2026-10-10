package app.nullpointr.foxnav.sim

import app.nullpointr.foxnav.shared.convoy.moved
import app.nullpointr.foxnav.shared.geo.GeoPoint
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TrackTest {
    @Test
    fun decodesPolylines() {
        // The example from Google's polyline documentation (precision 5).
        val points = decodePolyline("_p~iF~ps|U_ulLnnqC_mqNvxq`@", precision = 5)
        assertEquals(listOf(GeoPoint(38.5, -120.2), GeoPoint(40.7, -120.95), GeoPoint(43.252, -126.453)), points)
    }

    @Test
    fun drivesThereAndBack() {
        val start = GeoPoint(60.1699, 24.9384)
        val track = Track(listOf(start, start.moved(1_000.0, 90.0)))
        assertNear(1_000.0, track.length)

        val out = track.poseAt(250.0)
        assertNear(250.0, start.distanceTo(out.point))
        assertNear(90.0, out.headingDeg)

        val back = track.poseAt(1_750.0)
        assertNear(250.0, start.distanceTo(back.point))
        assertNear(270.0, back.headingDeg)

        // The next lap starts over.
        assertNear(250.0, start.distanceTo(track.poseAt(2_250.0).point))
    }

    private fun assertNear(expected: Double, actual: Double) = assertTrue(abs(expected - actual) < 1.0, "expected $expected, was $actual")
}
